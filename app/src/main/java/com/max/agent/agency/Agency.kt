package com.max.agent.agency

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.max.agent.core.MaxIdentity
import com.max.agent.github.GithubEngine
import com.max.agent.installer.ApkInstaller
import com.max.agent.models.ModelManager
import com.max.agent.safety.ActionLog
import com.max.agent.safety.Constitution
import com.max.agent.safety.Constitution.RiskLevel
import com.max.agent.safety.PermissionGate
import com.max.agent.scripting.ScriptingEngine
import com.max.agent.selffix.HotSwapper
import com.max.agent.system.SystemController
import com.max.agent.terminal.TerminalEngine
import com.max.agent.network.NetworkGuard
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

class Agency(
    private val context: Context,
    private val terminal: TerminalEngine,
    private val systemController: SystemController,
    private val permissionGate: PermissionGate,
    private val actionLog: ActionLog,
    private val modelManager: ModelManager,
    private val hotSwapper: HotSwapper,
    private val scriptingEngine: ScriptingEngine? = null,
    private val githubEngine: GithubEngine,
    private val apkInstaller: ApkInstaller,
    private val networkGuard: NetworkGuard,
    private val resourceMonitor: com.max.agent.system.ResourceMonitor? = null,
    private val selfCorrectionMachine: com.max.agent.selffix.SelfCorrectionMachine? = null,
    private val onSetVoice: ((String, Float, Float) -> Unit)? = null
) {
    private val maxVault = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "Max_Core"
    )

    init { if (!maxVault.exists()) maxVault.mkdirs() }

    enum class ActionType {
        SHELL_COMMAND, SET_VOLUME, SET_BRIGHTNESS, OPEN_SETTINGS_PANEL,
        RINGER_MODE, TOGGLE_INTERNET, LAUNCH_APP, GET_SYSTEM_STATE,
        SELF_DIAGNOSTIC, MODIFY_SYSTEM_PROMPT, ADD_RULE, WRITE_FILE, READ_FILE, LIST_DIR,
        EXECUTE_SCRIPT, SET_VOICE, GITHUB_READ_FILE, GITHUB_WRITE_FILE,
        GITHUB_TRIGGER_BUILD, INSTALL_APK, HOTSWAP_DEX, NONE
    }

    data class Action(
        val type: ActionType,
        val params: Map<String, String> = emptyMap(),
        val description: String,
        val riskLevel: RiskLevel,
        val requiresApproval: Boolean
    )

    data class ActionResult(
        val action: Action,
        val success: Boolean,
        val output: String = "",
        val error: String? = null,
        val isFatal: Boolean = false
    )

    private val _lastResult = MutableStateFlow<ActionResult?>(null)
    val lastResult: StateFlow<ActionResult?> = _lastResult

    fun parseAction(json: String): Action? = runCatching {
        val o = JSONObject(json)
        val type = ActionType.valueOf(o.optString("type", "NONE").uppercase())
        val params = mutableMapOf<String, String>()
        o.optJSONObject("params")?.let { p ->
            p.keys().forEach { k -> params[k] = p.optString(k) }
        }
        val risk = when (o.optString("risk", "Low").lowercase()) {
            "high" -> RiskLevel.High
            "medium", "med" -> RiskLevel.Medium
            else -> RiskLevel.Low
        }
        Action(
            type = type,
            params = params,
            description = o.optString("description", type.name),
            riskLevel = risk,
            requiresApproval = o.optBoolean("requiresApproval", risk != RiskLevel.Low)
        )
    }.getOrNull()

    suspend fun executeAction(action: Action): ActionResult {
        val requester = "LLM/${action.description.take(60)}"

        val readOnly = action.type in setOf(
            ActionType.NONE,
            ActionType.GET_SYSTEM_STATE,
            ActionType.SELF_DIAGNOSTIC,
            ActionType.READ_FILE,
            ActionType.LIST_DIR,
            ActionType.GITHUB_READ_FILE
        )

        if (permissionGate.isLockedDown()) {
            return failed(action, "Permission gate is locked down").also { record(it, requester) }
        }

        if (!readOnly && (action.riskLevel == RiskLevel.Medium || action.riskLevel == RiskLevel.High)) {
            val outcome = permissionGate.requestAndAwait(
                action = action.description,
                requestedBy = requester,
                reason = "Agent-initiated action of type ${action.type}",
                riskLevel = action.riskLevel,
                details = action.params.toString(),
                isCritical = action.riskLevel == RiskLevel.High
            )
            if (outcome != PermissionGate.Outcome.APPROVED) {
                return failed(action, "Owner ${outcome.name.lowercase()}").also { record(it, requester) }
            }
        }

        return try {
            withTimeout(30_000) { dispatch(action) }
        } catch (e: TimeoutCancellationException) {
            failed(action, "Timed out after 30s").copy(isFatal = true)
        } catch (e: Throwable) {
            failed(action, e.message ?: e.javaClass.simpleName)
        }.also {
            _lastResult.value = it
            record(it, requester)
        }
    }

    private suspend fun dispatch(action: Action): ActionResult = when (action.type) {
        ActionType.SHELL_COMMAND -> {
            val cmd = action.params["cmd"].orEmpty()
            if (cmd.isBlank()) failed(action, "Missing 'cmd'")
            else {
                val r = terminal.exec(cmd, timeoutMs = 25_000)
                ActionResult(action, r.success, r.output, if (!r.success) r.stderr else null)
            }
        }

        ActionType.SET_VOLUME -> {
            val pct = action.params["pct"]?.toIntOrNull()
            if (pct == null) failed(action, "Missing/invalid 'pct'")
            else {
                val stream = when (action.params["stream"]?.lowercase()) {
                    "ring" -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm" -> AudioManager.STREAM_ALARM
                    "voice" -> AudioManager.STREAM_VOICE_CALL
                    else -> AudioManager.STREAM_MUSIC
                }
                systemController.setVolumePct(stream, pct.coerceIn(0, 100))
                ActionResult(action, true, "Volume set to ${pct.coerceIn(0,100)}%")
            }
        }

        ActionType.SET_BRIGHTNESS -> {
            val pct = action.params["pct"]?.toIntOrNull()
            if (pct == null) failed(action, "Missing/invalid 'pct'")
            else if (!systemController.canWriteSettings()) failed(action, "WRITE_SETTINGS not granted")
            else { systemController.setBrightnessPct(pct.coerceIn(0,100)); ActionResult(action, true, "Brightness set to ${pct.coerceIn(0,100)}%") }
        }

        ActionType.OPEN_SETTINGS_PANEL -> {
            when (action.params["panel"]?.lowercase()) {
                "wifi" -> { systemController.openWifiPanel(); ActionResult(action, true, "Opened WiFi panel") }
                "internet" -> { systemController.openInternetPanel(); ActionResult(action, true, "Opened internet panel") }
                "bluetooth" -> { systemController.openBluetoothPanel(); ActionResult(action, true, "Opened bluetooth panel") }
                "battery" -> { systemController.openBatterySaverSettings(); ActionResult(action, true, "Opened battery panel") }
                "hotspot" -> { systemController.openHotspotSettings(); ActionResult(action, true, "Opened hotspot settings") }
                "nfc" -> { systemController.openNfcSettings(); ActionResult(action, true, "Opened NFC settings") }
                else -> failed(action, "Unknown panel")
            }
        }

        ActionType.RINGER_MODE -> {
            when (action.params["mode"]?.lowercase()) {
                "silent" -> { systemController.setRingerSilent(); ActionResult(action, true, "Ringer: silent") }
                "vibrate" -> { systemController.setRingerVibrate(); ActionResult(action, true, "Ringer: vibrate") }
                "normal" -> { systemController.setRingerNormal(); ActionResult(action, true, "Ringer: normal") }
                else -> failed(action, "Mode must be silent|vibrate|normal")
            }
        }

        ActionType.GET_SYSTEM_STATE -> {
            val sb = StringBuilder()
            sb.appendLine("=== SYSTEM STATE ===")
            // System controller (volume, brightness, wifi, bluetooth, ringer, power-save)
            val snap = systemController.refresh()
            sb.appendLine("Volume: media ${snap.mediaVolumePct}%, ring ${snap.ringVolumePct}%, alarm ${snap.alarmVolumePct}%")
            sb.appendLine("Brightness: ${snap.brightnessPct}%")
            sb.appendLine("WiFi: ${if (snap.isWifiEnabled) "on" else "off"} | Bluetooth: ${if (snap.isBluetoothEnabled) "on" else "off"}")
            sb.appendLine("Ringer: ${snap.ringerMode} | Power-save: ${if (snap.isPowerSaveMode) "on" else "off"}")
            // Resource monitor (CPU, RAM, storage, battery, thermal)
            resourceMonitor?.let { rm ->
                val res = rm.state.value
                sb.appendLine("CPU: ${"%.1f".format(res.cpuPercent)}%")
                sb.appendLine("RAM: ${res.ramUsedMb}/${res.ramTotalMb} MB (${res.ramUsedPct.toInt()}%)")
                sb.appendLine("Storage: ${"%.1f".format(res.storageFreeGb)} GB free of ${"%.1f".format(res.storageTotalGb)} GB")
                sb.appendLine("Battery: ${res.batteryPct}% (${if (res.isCharging) "charging" else "on battery"}, ${res.batteryTempC.toInt()}°C)")
                sb.appendLine("Thermal: ${res.thermalStatus}")
            }
            // Model states
            val ev = modelManager.everydayState.value
            val cd = modelManager.coderState.value
            sb.appendLine("Model PRIMARY: ${ev.loadedModel?.name ?: "none"} ${if (ev.isLoaded) "[loaded]" else if (ev.isLoading) "[loading]" else "[idle]"}")
            sb.appendLine("Model CODER:   ${cd.loadedModel?.name ?: "none"} ${if (cd.isLoaded) "[loaded]" else if (cd.isLoading) "[loading]" else "[idle]"}")
            // Network
            sb.appendLine("Internet policy: ${if (networkGuard.isInternetAllowed()) "ALLOWED" else "RECALLED"}")
            // GitHub
            sb.appendLine("GitHub: ${if (githubEngine.isConfigured()) "configured" else "NOT configured"}")
            // Self-correction
            selfCorrectionMachine?.let { sc ->
                sb.appendLine("Self-healing: phase=${sc.phase.value.name}")
                sc.attempt.value?.let { att ->
                    sb.appendLine("  active attempt: ${att.failure.type} — ${att.failure.message.take(80)}")
                }
            }
            ActionResult(action, true, sb.toString())
        }

        ActionType.SELF_DIAGNOSTIC -> {
            val sb = StringBuilder()
            sb.appendLine("=== MAX SELF-DIAGNOSTIC REPORT ===")
            sb.appendLine()
            // 1. Environment
            sb.appendLine(terminal.diagnoseSelf())
            sb.appendLine()
            // 2. System state (full)
            val snap = systemController.refresh()
            sb.appendLine("--- System ---")
            sb.appendLine("Volume: media ${snap.mediaVolumePct}%, ring ${snap.ringVolumePct}%")
            sb.appendLine("Brightness: ${snap.brightnessPct}% | Ringer: ${snap.ringerMode}")
            sb.appendLine("WiFi: ${if (snap.isWifiEnabled) "on" else "off"} | BT: ${if (snap.isBluetoothEnabled) "on" else "off"} | Power-save: ${if (snap.isPowerSaveMode) "on" else "off"}")
            sb.appendLine()
            // 3. Resources
            resourceMonitor?.let { rm ->
                val res = rm.state.value
                sb.appendLine("--- Resources ---")
                sb.appendLine("CPU: ${"%.1f".format(res.cpuPercent)}%")
                sb.appendLine("RAM: ${res.ramUsedMb}/${res.ramTotalMb} MB (${res.ramUsedPct.toInt()}% used, ${res.ramFreeMb} MB free)")
                sb.appendLine("Storage: ${"%.1f".format(res.storageFreeGb)} GB free of ${"%.1f".format(res.storageTotalGb)} GB (${res.storageUsedPct.toInt()}% used)")
                sb.appendLine("Battery: ${res.batteryPct}% (${if (res.isCharging) "charging" else "discharging"}, ${res.batteryTempC.toInt()}°C)")
                sb.appendLine("Thermal: ${res.thermalStatus}")
                sb.appendLine()
            }
            // 4. Models
            sb.appendLine("--- Models ---")
            val avail = modelManager.available.value
            sb.appendLine("Available models: ${avail.size}")
            avail.forEach { sb.appendLine("  ${it.name} (${it.displaySize})") }
            val ev = modelManager.everydayState.value
            val cd = modelManager.coderState.value
            sb.appendLine("PRIMARY slot: ${ev.loadedModel?.name ?: "none"} ${if (ev.isLoaded) "[LOADED]" else if (ev.isLoading) "[LOADING...]" else "[idle]"}")
            ev.error?.let { sb.appendLine("  PRIMARY error: $it") }
            sb.appendLine("CODER slot:   ${cd.loadedModel?.name ?: "none"} ${if (cd.isLoaded) "[LOADED]" else if (cd.isLoading) "[LOADING...]" else "[idle]"}")
            cd.error?.let { sb.appendLine("  CODER error: $it") }
            sb.appendLine()
            // 5. Self-healing status
            selfCorrectionMachine?.let { sc ->
                sb.appendLine("--- Self-Healing ---")
                sb.appendLine("Phase: ${sc.phase.value.name}")
                sc.attempt.value?.let { att ->
                    sb.appendLine("Active attempt: ${att.failure.type} — ${att.failure.message}")
                    att.log.takeLast(5).forEach { sb.appendLine("  $it") }
                }
                val hist = sc.history.value
                sb.appendLine("History: ${hist.size} attempts (${hist.count { it.resolved }} resolved)")
                sb.appendLine()
            }
            // 6. Self-modification
            sb.appendLine("--- Self-Modification ---")
            sb.appendLine("GitHub: ${if (githubEngine.isConfigured()) "configured" else "NOT configured"}")
            githubEngine.config()?.let { cfg ->
                sb.appendLine("  repo: ${cfg.owner}/${cfg.repo}@${cfg.branch}")
            }
            sb.appendLine("HotSwap patches: ${hotSwapper.listPatches().size} staged")
            sb.appendLine("HotSwap dex: ${hotSwapper.listDexFiles().size} loaded")
            sb.appendLine()
            // 7. Network
            sb.appendLine("--- Network ---")
            sb.appendLine("Internet policy: ${if (networkGuard.isInternetAllowed()) "ALLOWED" else "RECALLED"}")
            sb.appendLine("Enforcement: ${networkGuard.enforcement.value.name}")
            sb.appendLine()
            // 8. Safety
            sb.appendLine("--- Safety ---")
            sb.appendLine("Constitution: ${Constitution.RULES.size} rules (immutable, v${Constitution.VERSION})")
            sb.appendLine("ActionLog entries: ${actionLog.entries.value.size}")
            sb.appendLine("Log tampered: ${actionLog.tampered.value}")
            sb.appendLine("Permission gate: ${if (permissionGate.isLockedDown()) "LOCKED DOWN" else "nominal"}")
            sb.appendLine()
            sb.appendLine("=== END DIAGNOSTIC ===")
            ActionResult(action, true, sb.toString())
        }

        ActionType.MODIFY_SYSTEM_PROMPT -> {
            val txt = action.params["text"].orEmpty()
            if (txt.isBlank()) failed(action, "Missing 'text'")
            else { MaxIdentity.updatePrompt(txt); ActionResult(action, true, "System prompt updated") }
        }

        ActionType.ADD_RULE -> {
            val rule = action.params["rule"].orEmpty()
            if (rule.isBlank()) failed(action, "Missing 'rule'")
            else { MaxIdentity.addRule(rule); ActionResult(action, true, "Rule appended") }
        }

        ActionType.WRITE_FILE -> writeFile(action)
        ActionType.READ_FILE -> readFile(action)
        ActionType.LIST_DIR -> listDir(action)

        ActionType.EXECUTE_SCRIPT -> {
            val engine = scriptingEngine
            val script = action.params["script"].orEmpty()
            if (engine == null) failed(action, "Scripting engine not available")
            else if (script.isBlank()) failed(action, "Missing 'script'")
            else {
                val r = engine.execute(script)
                ActionResult(action, r.success, r.output?.toString() ?: "", r.error)
            }
        }

        ActionType.SET_VOICE -> {
            val gender = action.params["gender"] ?: "neutral"
            val pitch = action.params["pitch"]?.toFloatOrNull() ?: 1.0f
            val rate = action.params["rate"]?.toFloatOrNull() ?: 1.0f
            onSetVoice?.invoke(gender, pitch, rate)
            ActionResult(action, true, "Voice set: $gender pitch=$pitch rate=$rate")
        }

        ActionType.GITHUB_READ_FILE -> {
            val path = action.params["path"].orEmpty()
            if (path.isBlank()) failed(action, "Missing 'path'")
            else {
                val content = githubEngine.readSourceFile(path)
                if (content == null) failed(action, "Read failed for $path")
                else ActionResult(action, true, content)
            }
        }

        ActionType.GITHUB_WRITE_FILE -> {
            val path = action.params["path"].orEmpty()
            val content = action.params["content"].orEmpty()
            val message = action.params["message"] ?: "Max self-edit"
            if (path.isBlank()) failed(action, "Missing 'path'")
            else {
                val ok = githubEngine.writeSourceFile(path, content, message)
                if (ok) ActionResult(action, true, "Committed $path")
                else failed(action, "Write failed for $path")
            }
        }

        ActionType.GITHUB_TRIGGER_BUILD -> {
            val sha = githubEngine.latestBuild()
            if (sha == null) failed(action, "No build status available")
            else ActionResult(action, true, "runId=${sha.runId} status=${sha.status} conclusion=${sha.conclusion ?: "-"}")
        }

        ActionType.INSTALL_APK -> {
            val apk = githubEngine.downloadLatestApk()
            if (apk == null) failed(action, "No APK available")
            else { apkInstaller.install(apk); ActionResult(action, true, "Install requested: ${apk.name}") }
        }

        ActionType.HOTSWAP_DEX -> {
            val dexPath = action.params["dex"].orEmpty()
            val className = action.params["class"].orEmpty()
            if (dexPath.isBlank() || className.isBlank()) failed(action, "Need 'dex' and 'class' params")
            else {
                val r = hotSwapper.loadFromDex(dexPath, className)
                if (r.success) ActionResult(action, true, "Hot-swapped $className")
                else failed(action, r.error ?: "unknown")
            }
        }

        ActionType.TOGGLE_INTERNET -> {
            val enable = action.params["enable"]?.toBoolean() ?: false
            if (enable) networkGuard.ownerRequestInternet()
            else networkGuard.ownerDisableInternet()
            val flag = if (enable) "ALLOW" else "BLOCK"
            ActionResult(action, true, "NetworkGuard policy flag set to $flag")
        }
        ActionType.LAUNCH_APP -> {
            val pkg = action.params["pkg"].orEmpty()
            if (pkg.isBlank()) failed(action, "Missing 'pkg'")
            else {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent == null) failed(action, "No launch intent for package '$pkg'")
                else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ActionResult(action, true, "Launched $pkg")
                }
            }
        }

        ActionType.NONE -> ActionResult(action, true, "no-op")
    }

    private fun writeFile(action: Action): ActionResult {
        val rel = action.params["path"].orEmpty()
        val content = action.params["content"].orEmpty()
        if (rel.isBlank()) return failed(action, "Missing 'path'")
        val target = File(maxVault, rel).canonicalFile
        if (!target.path.startsWith(maxVault.canonicalPath)) {
            return failed(action, "Path escapes vault")
        }
        target.parentFile?.mkdirs()
        target.writeText(content)
        return ActionResult(action, true, "Wrote ${target.absolutePath} (${content.length} chars)")
    }

    private fun readFile(action: Action): ActionResult {
        val rel = action.params["path"].orEmpty()
        if (rel.isBlank()) return failed(action, "Missing 'path'")
        val target = File(maxVault, rel).canonicalFile
        if (!target.path.startsWith(maxVault.canonicalPath)) {
            return failed(action, "Path escapes vault")
        }
        if (!target.exists()) return failed(action, "Not found: $rel")
        return ActionResult(action, true, target.readText())
    }

    private fun listDir(action: Action): ActionResult {
        val rel = action.params["path"] ?: ""
        val target = File(maxVault, rel).canonicalFile
        if (!target.path.startsWith(maxVault.canonicalPath)) {
            return failed(action, "Path escapes vault")
        }
        if (!target.isDirectory) return failed(action, "Not a directory: $rel")
        val listing = target.list()?.joinToString("\n") ?: ""
        return ActionResult(action, true, listing)
    }

    private fun record(result: ActionResult, requester: String) {
        actionLog.record(
            ActionLog.LogEntry(
                action = result.action.type.name + "(" + result.action.description + ")",
                requestedBy = requester,
                riskLevel = result.action.riskLevel.label,
                approved = result.success,
                outcome = if (result.success) result.output.take(200) else "FAIL: ${result.error}"
            )
        )
    }

    private fun failed(action: Action, msg: String): ActionResult =
        ActionResult(action, false, "", msg)
}

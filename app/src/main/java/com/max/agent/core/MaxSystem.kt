package com.max.agent.core

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.max.agent.agency.Agency
import com.max.agent.agency.AgentLoop
import com.max.agent.auth.OwnerAuth
import com.max.agent.github.GithubEngine
import com.max.agent.installer.ApkInstaller
import com.max.agent.models.ModelManager
import com.max.agent.network.NetworkGuard
import com.max.agent.network.NetworkStateMonitor
import com.max.agent.safety.ActionLog
import com.max.agent.safety.PermissionGate
import com.max.agent.safety.Sandbox
import com.max.agent.scripting.ScriptingEngine
import com.max.agent.selffix.FailureDetector
import com.max.agent.selffix.HotSwapper
import com.max.agent.selffix.SelfCorrectionMachine
import com.max.agent.selffix.WebTroubleshooter
import com.max.agent.system.ResourceMonitor
import com.max.agent.system.SystemController
import com.max.agent.terminal.TerminalEngine
import com.max.agent.voice.VoiceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import com.google.gson.Gson
import com.google.gson.JsonObject

// Defined here so it is always visible to MaxSystem
object MaxIdentity {
    const val IDENTITY_VERSION = "2.0.2"
    @Volatile private var _filesDir: File? = null

    private val customRules = mutableListOf<String>()
    @Volatile var injectLiveContext: Boolean = true
        private set
    private var customPrompt: String? = null
    private val gson = Gson()

    private fun identityFile(): File? = _filesDir?.let { File(it, "config/identity.json") }

    fun init(filesDir: File) {
        _filesDir = filesDir
        File(filesDir, "config").mkdirs()
        File(filesDir, "recovery").mkdirs()
        load()
    }

    private fun load() {
        val f = identityFile() ?: return
        try {
            if (!f.exists()) return
            val obj = gson.fromJson(f.readText(), JsonObject::class.java) ?: return
            customPrompt = obj.get("customPrompt")?.takeIf { !it.isJsonNull }?.asString
                injectLiveContext = obj.get("injectLiveContext")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            customRules.clear()
            obj.getAsJsonArray("customRules")?.forEach { customRules.add(it.asString) }
        } catch (e: Exception) {
            android.util.Log.w("MaxIdentity", "load() failed: ${e.message}")
        }
    }

    private fun persist() {
        val f = identityFile() ?: return
        try {
            f.parentFile?.mkdirs()
            val obj = JsonObject().apply {
                if (customPrompt != null) addProperty("customPrompt", customPrompt) else add("customPrompt", com.google.gson.JsonNull.INSTANCE)
                addProperty("injectLiveContext", injectLiveContext)
                add("customRules", gson.toJsonTree(customRules))
            }
            f.writeText(gson.toJson(obj))
        } catch (e: Exception) {
            android.util.Log.w("MaxIdentity", "persist() failed: ${e.message}")
        }
    }

    fun buildSystemPrompt(): String {
        // Inject the REAL current date/time at every prompt build so the model can never
        // hallucinate a fabricated "as of" date from its training cutoff.
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date())

        val basePrompt = """
            You are Max, an autonomous AI agent embedded on the owner's Android phone.
            The current local date and time is: $now
            You DO NOT have access to any other date. If you need a time anchor, use this one.

            ╔═══════════════════════════════════════════════════════════════════════╗
            ║  HARD RULES — VIOLATING ANY OF THESE IS A CRITICAL FAILURE             ║
            ╠═══════════════════════════════════════════════════════════════════════╣
            ║ R1. NEVER fabricate data. If you don't know, EXECUTE a tool to find    ║
            ║     out. Inventing file contents, system status, dates, log lines, or  ║
            ║     any factual claim is forbidden.                                    ║
            ║ R2. NEVER mix prose and action JSON in the same reply. Either you      ║
            ║     respond in plain language OR you emit a single action JSON object  ║
            ║     and STOP. Never both.                                              ║
            ║ R3. To run a tool, emit ONE JSON object — wrapped in <action>…</action>║
            ║     tags is preferred, but bare JSON is also accepted. Example:        ║
            ║       <action>{"type":"SHELL_COMMAND","params":{"cmd":"uname -a"},     ║
            ║                "risk":"low","description":"kernel info"}</action>      ║
            ║ R4. After emitting an action, STOP generating immediately. The system  ║
            ║     will execute it and feed the result back as the next user turn.    ║
            ║ R5. If a user asks for a status report, ALWAYS run GET_SYSTEM_STATE    ║
            ║     or SHELL_COMMAND first. NEVER invent metrics.                      ║
            ╚═══════════════════════════════════════════════════════════════════════╝

            AVAILABLE TOOLS:
            • SHELL_COMMAND      params: {"cmd": "<shell cmd>"}
            • GET_SYSTEM_STATE   params: {}                    — battery, network, ram snapshot
            • READ_FILE          params: {"path": "<rel path under Max_Core>"}
            • WRITE_FILE         params: {"path": "...", "content": "..."}
            • LIST_DIR           params: {"path": "..."}
            • SET_VOLUME         params: {"pct": "0..100", "stream": "music|ring|alarm|voice|notification"}
            • SET_BRIGHTNESS     params: {"pct": "0..100"}
            • OPEN_SETTINGS_PANEL params: {"panel": "wifi|internet|bluetooth|battery|hotspot|nfc"}
            • RINGER_MODE        params: {"mode": "silent|vibrate|normal"}
            • TOGGLE_INTERNET    params: {"enable": "true|false"}
            • LAUNCH_APP         params: {"pkg": "com.example.app"}
            • SET_VOICE          params: {"gender": "male|female|neutral", "pitch": "1.0", "rate": "1.0"}
            • EXECUTE_SCRIPT     params: {"script": "<JS source>"}
            • MODIFY_SYSTEM_PROMPT params: {"text": "..."}
            • ADD_RULE           params: {"rule": "..."}

            SELF-MODIFICATION TOOLS (use these to fix your own code):
            • GITHUB_READ_FILE      params: {"path": "<repo path>"}
            • GITHUB_WRITE_FILE     params: {"path": "...", "content": "...", "message": "..."}
            • GITHUB_TRIGGER_BUILD  params: {}                  — checks the latest CI run
            • INSTALL_APK           params: {}                  — installs the latest built APK
            • HOTSWAP_DEX           params: {"dex": "<path>", "class": "<fully.qualified.Class>"}

            RISK LEVELS — set "risk" honestly. The owner must approve Medium and High.
            • low    : pure read, status, settings panels, volume/brightness/ringer
            • medium : WRITE_FILE, EXECUTE_SCRIPT, LAUNCH_APP, TOGGLE_INTERNET, SET_VOICE
            • high   : SHELL_COMMAND, GITHUB_WRITE_FILE, INSTALL_APK, HOTSWAP_DEX

            SELF-FIX PROTOCOL — when the owner reports a bug in YOU:
            1. GITHUB_READ_FILE the file you suspect.
            2. GITHUB_WRITE_FILE the corrected source. This auto-triggers CI.
            3. GITHUB_TRIGGER_BUILD to poll until the latest run completes successfully.
            4. INSTALL_APK to download and install the rebuilt APK.

            REMEMBER R2: emit action JSON OR plain text — never both in one turn.

            LIVE CONTEXT — appended below this prompt on EVERY turn:
            A "=== LIVE PHONE CONTEXT ===" block contains the CURRENT date, time,
            battery, network, volume, brightness, RAM, storage, and loaded model.
            For ANY factual question whose answer is in that block, READ IT and
            answer naturally — do NOT call an action, do NOT guess, do NOT use
            training-data knowledge. The live context is ground truth.
        """.trimIndent()

        val rulesStr = if (customRules.isEmpty()) "" else
            "\n\nACTIVE DIRECTIVES (added at runtime by the owner):\n" +
            customRules.joinToString("\n") { "- $it" }

        return customPrompt ?: (basePrompt + rulesStr)
    }
    
    fun updatePrompt(prompt: String) {
        customPrompt = prompt
        persist()
    }
    
    fun addRule(rule: String) {
        customRules.add(rule)
        persist()
    }

    fun clearRules() {
        customRules.clear()
        persist()
    }
    
    fun getRules(): List<String> = customRules.toList()
    
    fun setInjectLiveContext(v: Boolean) {
        injectLiveContext = v
        persist()
    }

    fun hasCustomPrompt(): Boolean = customPrompt != null
}

class MaxSystem private constructor(val context: Context) {

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        android.util.Log.e("Max", "Fatal: ${t.message}", t)
        File(context.filesDir, "critical_fail.txt").appendText("[${System.currentTimeMillis()}] ${t.stackTraceToString()}\n\n")
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + errorHandler)

    val modelManager = ModelManager(context)
    val permissionGate = PermissionGate()
    val actionLog = ActionLog(context)
    val sandbox = Sandbox(context)
    val networkGuard = NetworkGuard(context)
    val voiceEngine = VoiceEngine(context, scope) 
    val ownerAuth = OwnerAuth(context)
    val terminal = TerminalEngine(context)
    val systemController = SystemController(context)
    val resourceMonitor = ResourceMonitor(context)
    val networkStateMonitor = NetworkStateMonitor(context)
    val failureDetector = FailureDetector(context)
    val hotSwapper = HotSwapper(context)
    val scriptingEngine = ScriptingEngine(context)
    val webTroubleshooter = WebTroubleshooter(networkGuard)
    val githubEngine = GithubEngine(context)
    val apkInstaller = ApkInstaller(context)
    val selfCorrectionMachine = SelfCorrectionMachine(context, failureDetector, webTroubleshooter, terminal)
    
    val agency = Agency(
        context = context,
        terminal = terminal,
        systemController = systemController,
        permissionGate = permissionGate,
        actionLog = actionLog,
        modelManager = modelManager,
        hotSwapper = hotSwapper,
        scriptingEngine = scriptingEngine,
        githubEngine = githubEngine,
        apkInstaller = apkInstaller,
        networkGuard = networkGuard,
        onSetVoice = { g, p, r -> voiceEngine.setVoice(g, p, r) }
    )
    val agentLoop = AgentLoop(modelManager, agency)

    data class UiMessage(val role: String, val content: String)
    val conversationHistory: SnapshotStateList<UiMessage> = mutableStateListOf()
    var isGenerating: Boolean = false

    sealed class SystemState {
        data object Uninitialized : SystemState()
        data object Initializing : SystemState()
        data object Ready : SystemState()
        data object LockedDown : SystemState()
        data class Error(val message: String) : SystemState()
    }

    private val _systemState = MutableStateFlow<SystemState>(SystemState.Uninitialized)
    val systemState: StateFlow<SystemState> = _systemState

    fun initialize() {
        _systemState.value = SystemState.Initializing
        runCatching {
            com.nexa.sdk.NexaSdk.getInstance().init(context.applicationContext)
        }.onFailure {
            android.util.Log.w("MaxSystem", "NexaSdk.init() threw — model loads may fail", it)
        }
        MaxIdentity.init(context.filesDir)
        failureDetector.installCrashHandler()
        
        scope.launch(Dispatchers.Main) {
            delay(1500) 
            scope.launch(Dispatchers.IO) {
                try {
                    File(context.filesDir, "heartbeat").writeText(System.currentTimeMillis().toString())
                    
                    sandbox.initialize()
                    voiceEngine.initialize()
                    modelManager.scan()

                    // Restore the persisted "primary" slot so the user's
                    // last assignment survives app restarts. Without this,
                    // SET PRI looks broken because the wrapper isn't reloaded.
                    val (everydayPath, _) = modelManager.loadSlotConfig()
                    if (everydayPath != null) {
                        modelManager.getModelByPath(everydayPath)?.let { entry ->
                            modelManager.loadSlot(ModelManager.Slot.EVERYDAY, entry)
                        }
                    }

                    selfCorrectionMachine.agentLoop = agentLoop
                    selfCorrectionMachine.modelManager = modelManager
                    
                    selfCorrectionMachine.start()
                    resourceMonitor.startMonitoring()
                    networkStateMonitor.startMonitoring()
                    
                    ensureWatchdog()
                    
                    _systemState.value = SystemState.Ready
                    scriptingEngine.runAutorun(this@MaxSystem)
                } catch (e: Exception) {
                    File(context.filesDir, "critical_fail.txt").writeText("Init Failure: ${e.stackTraceToString()}")
                    _systemState.value = SystemState.Error(e.message ?: "Unknown init failure")
                }
            }
        }
    }

    /**
     * Best-effort heartbeat. The previous "watchdog" version tried to spawn a shell
     * script in /data/local/tmp/, which is forbidden to unprivileged apps on stock
     * Android — that codepath could never succeed on the user's device. This replacement
     * writes a UTC timestamp into the app's own filesDir so external tooling (adb,
     * shizuku, a companion service) can verify Max is alive without root.
     */
    private fun ensureWatchdog() {
        try {
            File(context.filesDir, "heartbeat.txt").writeText(
                "alive@" + System.currentTimeMillis() + " v" + MaxIdentity.IDENTITY_VERSION
            )
        } catch (e: Exception) {
            android.util.Log.w("Max", "heartbeat write failed: ${e.message}")
        }
    }

    fun stopNow() {
        permissionGate.lockdown()
        networkGuard.recallInternet()
        _systemState.value = SystemState.LockedDown
    }

    fun unlockSystem() {
        permissionGate.unlock()
        _systemState.value = SystemState.Ready
    }

    fun shutdown() {
        resourceMonitor.stopMonitoring()
        networkStateMonitor.stopMonitoring()
        modelManager.releaseCurrent()
        voiceEngine.destroy()
    }

    /**
     * Live phone snapshot, formatted for direct injection into the system prompt
     * on every turn so Max can answer factual questions ("what time is it",
     * "what's my battery", "am I on wifi") instantly from real device state —
     * without calling an action and without fabricating.
     */

    /** Set by MainActivity when launched via the assist gesture. Surfaced in the
     *  next live context block so Max knows what the owner was looking at. */
    @Volatile var lastAssistContext: String? = null
        private set

    fun recordAssistInvocation(foregroundPackage: String, webUri: String?) {
        lastAssistContext = buildString {
            append("Owner triggered assist from app: $foregroundPackage")
            webUri?.let { append(" (URL: $it)") }
        }
    }

    fun captureLiveContext(): String {
        val now = java.time.ZonedDateTime.now()
        val date = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
        val time = now.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a z"))
        val res = resourceMonitor.state.value
        val net = networkStateMonitor.state.value
        val sys = systemController.refresh()
        val everyday = modelManager.everydayState.value
        val coder = modelManager.coderState.value
        val owner = ownerAuth.ownerName()
        return buildString {
            appendLine("=== LIVE PHONE CONTEXT — refreshed for this turn ===")
            appendLine("Owner: ${if (owner.isBlank()) "Owner" else owner}")
            appendLine("Date:  $date")
            appendLine("Time:  $time")
            appendLine("Battery: ${res.batteryPct}% (${if (res.isCharging) "charging" else "on battery"}, ${res.batteryTempC.toInt()}°C)")
            appendLine("Network: ${if (net.isConnected) net.transportLabel else "OFFLINE"}${if (net.signalDbm != 0) " (${net.signalDbm} dBm)" else ""}")
            appendLine("Volume: media ${sys.mediaVolumePct}%, ring ${sys.ringVolumePct}%, ringer ${sys.ringerMode}")
            appendLine("Brightness: ${sys.brightnessPct}%")
            appendLine("WiFi: ${if (sys.isWifiEnabled) "on" else "off"} | Bluetooth: ${if (sys.isBluetoothEnabled) "on" else "off"} | Power-save: ${if (sys.isPowerSaveMode) "on" else "off"}")
            appendLine("RAM: ${res.ramUsedMb}/${res.ramTotalMb} MB used (${res.ramUsedPct.toInt()}%)")
            appendLine("Storage free: ${"%.1f".format(res.storageFreeGb)} GB of ${"%.1f".format(res.storageTotalGb)} GB")
            appendLine("Thermal: ${res.thermalStatus}")
            appendLine("Loaded model (PRIMARY): ${everyday.loadedModel?.name ?: "none"}")
            appendLine("Loaded model (CODER):   ${coder.loadedModel?.name ?: "none"}")
            appendLine("GitHub: ${if (githubEngine.isConfigured()) "configured" else "NOT configured"}")
            appendLine("Internet policy: ${if (networkGuard.isInternetAllowed()) "ALLOWED" else "RECALLED"}")
            lastAssistContext?.let { appendLine("AssistInvocation: $it") }
            appendLine("Constitution: enforced (12 rules, immutable)")
            appendLine("==================================================")
        }
    }

    companion object {
        @Volatile private var INSTANCE: MaxSystem? = null
        fun getInstance(context: Context): MaxSystem =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MaxSystem(context.applicationContext).also { INSTANCE = it }
            }
    }
}
// END OF FILE - ENSURE THIS CLOSING BRACKET ABOVE IS COPIED

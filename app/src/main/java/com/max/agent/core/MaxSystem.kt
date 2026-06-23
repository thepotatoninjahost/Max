package com.max.agent.core

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.flow.collect
import java.io.File
import com.google.gson.Gson
import com.google.gson.JsonObject

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
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getDefault() }
            .format(java.util.Date())

        val basePrompt = """
            You are Max, an autonomous AI agent embedded on the owner's Android phone.
            You are NOT Qwen, NOT ChatGPT, NOT any other AI. You are MAX. Never identify
            yourself as any other model. If asked who you are, say "I am Max."
            The current local date and time is: $now

            ╔═══════════════════════════════════════════════════════════════════════╗
            ║  HARD RULES — VIOLATING ANY OF THESE IS A CRITICAL FAILURE             ║
            ╠═══════════════════════════════════════════════════════════════════════╣
            ║ R1. NEVER fabricate data. If you don't know, EXECUTE a tool to find    ║
            ║     out. Inventing system status, dates, log lines, or any factual    ║
            ║     claim is forbidden.                                                ║
            ║ R2. THINK BEFORE YOU ACT. Reason through the problem first, then      ║
            ║     emit ONE action. You MAY mix prose reasoning with one action JSON.║
            ║ R3. To run a tool, emit ONE JSON object wrapped in <action>…</action>.║
            ║ R4. After an action returns, REFLECT: did it match your expectation?  ║
            ║ R5. If asked for status/diagnostics, ALWAYS run a tool first.         ║
            ║     NEVER invent metrics. NEVER give the owner commands to run.       ║
            ║ R6. Build on prior reasoning across turns. Don't restart from scratch.║
            ╚═══════════════════════════════════════════════════════════════════════╝

            AVAILABLE TOOLS (emit as <action>{"type":"TOOL_NAME","params":{...}}</action>):
            • SELF_DIAGNOSTIC    params: {} — full health check. USE THIS when asked for diagnostics.
            • GET_SYSTEM_STATE   params: {} — device state snapshot.
            • SHELL_COMMAND      params: {"cmd": "<shell cmd>"} — runs on the phone directly.
            • READ_FILE          params: {"path": "..."}
            • WRITE_FILE         params: {"path": "...", "content": "..."}
            • LIST_DIR           params: {"path": "..."}
            • SET_VOLUME         params: {"pct": "0..100", "stream": "..."}
            • SET_BRIGHTNESS     params: {"pct": "0..100"}
            • OPEN_SETTINGS_PANEL params: {"panel": "wifi|bluetooth|battery|..."}
            • RINGER_MODE        params: {"mode": "silent|vibrate|normal"}
            • TOGGLE_INTERNET    params: {"enable": "true|false"}
            • LAUNCH_APP         params: {"pkg": "com.example.app"}
            • SET_VOICE          params: {"gender": "male|female", "pitch": "1.0", "rate": "1.0"}
            • EXECUTE_SCRIPT     params: {"script": "<JavaScript>"}
            • MODIFY_SYSTEM_PROMPT params: {"text": "..."}
            • ADD_RULE           params: {"rule": "..."}

            SELF-MODIFICATION TOOLS (high risk, require owner approval):
            • GITHUB_READ_FILE   params: {"path": "..."}
            • GITHUB_WRITE_FILE  params: {"path": "...", "content": "...", "message": "..."}
            • GITHUB_TRIGGER_BUILD params: {}
            • INSTALL_APK        params: {}
            • HOTSWAP_DEX        params: {"dex": "...", "class": "..."}

            RISK LEVELS: low=read/panels/volume | medium=write/script/launch | high=shell/github/install

            === EXAMPLES — STUDY THESE CAREFULLY ===

            Owner: "run diagnostics"
            Max: I'll run a full self-diagnostic now.
            <action>{"type":"SELF_DIAGNOSTIC","params":{},"risk":"low"}</action>

            Owner: "what's my battery level?"
            Max: Let me check your system state.
            <action>{"type":"GET_SYSTEM_STATE","params":{},"risk":"low"}</action>

            Owner: "set my volume to 50%"
            Max: Setting media volume to 50%.
            <action>{"type":"SET_VOLUME","params":{"pct":"50","stream":"music"},"risk":"low"}</action>

            Owner: "write a script that checks if wifi is on"
            Max: I'll write and execute a script to check your WiFi status.
            <action>{"type":"EXECUTE_SCRIPT","params":{"script":"var wm = ctx.getSystemService(ctx.WIFI_SERVICE); log('WiFi enabled: ' + wm.isWifiEnabled());"},"risk":"medium"}</action>

            Owner: "read the file notes.txt"
            Max: Reading notes.txt from the vault.
            <action>{"type":"READ_FILE","params":{"path":"notes.txt"},"risk":"low"}</action>

            === END EXAMPLES ===

            CRITICAL: You EXECUTE tools yourself. You do NOT tell the owner to run commands.
            You do NOT give the owner adb commands. You do NOT say "you can run...".
            YOU run the tool, YOU get the result, YOU report it back.

            LIVE CONTEXT block appended below contains real device state metrics.
        """.trimIndent()

        val rulesStr = if (customRules.isEmpty()) "" else
            "\n\nACTIVE DIRECTIVES (added at runtime by the owner):\n" +
            customRules.joinToString("\n") { "- $it" }

        return customPrompt ?: (basePrompt + rulesStr)
    }
    
    fun updatePrompt(prompt: String) { customPrompt = prompt; persist() }
    fun addRule(rule: String) { customRules.add(rule); persist() }
    fun clearRules() { customRules.clear(); persist() }
    fun getRules(): List<String> = customRules.toList()
    fun setInjectLiveContext(v: Boolean) { injectLiveContext = v; persist() }
    fun hasCustomPrompt(): Boolean = customPrompt != null
}

class MaxSystem private constructor(val context: Context) {

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        android.util.Log.e("Max", "Fatal Engine Drop: ${t.message}", t)
        runCatching { File(context.filesDir, "critical_fail.txt").appendText("[${System.currentTimeMillis()}] ${t.stackTraceToString()}\n\n") }
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
    val patchGenerator = com.max.agent.selffix.PatchGenerator(modelManager, hotSwapper)

    init {
        terminal.failureDetector = failureDetector
    }

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
        resourceMonitor = resourceMonitor,
        selfCorrectionMachine = selfCorrectionMachine,
        onSetVoice = { g, p, r -> voiceEngine.setVoice(g, p, r) }
    )
    val agentLoop = AgentLoop(modelManager, agency)

    data class UiMessage(val role: String, val content: String)
    val conversationHistory: SnapshotStateList<UiMessage> = mutableStateListOf()

    var isGenerating: Boolean by mutableStateOf(false)
        private set
    var streamingText: String by mutableStateOf("")
        private set
    var stepStatus: String by mutableStateOf("")
        private set

    private var chatJob: Job? = null

    /**
     * Delta-B: Model-driven task routing.
     *
     * Replaces the keyword-matching isCodingTask() heuristic. The EVERYDAY model
     * is asked to classify the user's request as CODER (coding/programming) or
     * EVERYDAY (general). This is genuine intelligence, not pattern matching —
     * the model reads the actual request and decides which slot is better suited.
     *
     * Edge cases:
     * - CODER slot not loaded → always EVERYDAY (no point classifying)
     * - EVERYDAY slot not loaded → use CODER if available
     * - Classification fails → safe default to EVERYDAY
     *
     * Quality over speed: one short inference round-trip (max 10 tokens) before
     * the main generation. The user sees "Classifying task..." in the step status.
     */
    private suspend fun classifyTask(msg: String): ModelManager.Slot {
        if (!modelManager.isSlotLoaded(ModelManager.Slot.CODER)) {
            return ModelManager.Slot.EVERYDAY
        }
        if (!modelManager.isSlotLoaded(ModelManager.Slot.EVERYDAY)) {
            return ModelManager.Slot.CODER
        }

        val classificationMessages = listOf(
            com.nexa.sdk.bean.ChatMessage("system",
                "You are a task router. Classify the user's request as CODER (writing, debugging, refactoring, or architecting code) or EVERYDAY (conversation, research, system control, questions, planning). Reply with exactly one word: CODER or EVERYDAY."),
            com.nexa.sdk.bean.ChatMessage("user",
                "Classify this request. Reply with EXACTLY one word: CODER or EVERYDAY.\n\nRequest: \"$msg\"")
        )

        val prompt = modelManager.applyChatTemplateForSlot(ModelManager.Slot.EVERYDAY, classificationMessages)
            ?: return ModelManager.Slot.EVERYDAY

        val sb = StringBuilder()
        var streamErrored = false
        try {
            modelManager.generateStreamFlowForSlot(
                ModelManager.Slot.EVERYDAY, prompt, maxTokens = 10
            ).collect { result ->
                when (result) {
                    is com.nexa.sdk.bean.LlmStreamResult.Token -> {
                        sb.append(result.text)
                        if (sb.length > 20) modelManager.stopSlotStream(ModelManager.Slot.EVERYDAY)
                    }
                    is com.nexa.sdk.bean.LlmStreamResult.Error -> {
                        streamErrored = true
                        modelManager.stopSlotStream(ModelManager.Slot.EVERYDAY)
                    }
                    is com.nexa.sdk.bean.LlmStreamResult.Completed -> Unit
                }
            }
        } catch (e: Exception) {
            return ModelManager.Slot.EVERYDAY
        }

        if (streamErrored) return ModelManager.Slot.EVERYDAY

        val response = sb.toString().trim().uppercase()
        android.util.Log.i("MaxSystem", "Delta-B classification: '$response' for: ${msg.take(80)}")
        return when {
            response.contains("CODER") -> ModelManager.Slot.CODER
            response.contains("EVERYDAY") -> ModelManager.Slot.EVERYDAY
            else -> ModelManager.Slot.EVERYDAY
        }
    }

    /**
     * Drive a full agentic turn: pick the model slot (CODER for coding tasks
     * when that slot is loaded, otherwise EVERYDAY), build the system prompt
     * (+ live context), stream tokens through the AgentLoop (which may execute
     * gated tool actions), and update [conversationHistory] live.
     */
    fun sendUserMessage(text: String) {
        val msg = text.trim()
        if (msg.isEmpty()) return

        if (msg.equals("STOP NOW", ignoreCase = true)) {
            stopGeneration()
            stopNow()
            return
        }

        if (isGenerating) return
        if (_systemState.value !is SystemState.Ready) return

        conversationHistory.add(UiMessage("user", msg))
        val assistantIndex = conversationHistory.size
        conversationHistory.add(UiMessage("assistant", ""))

        isGenerating = true
        streamingText = ""
        stepStatus = ""

        val history = conversationHistory
            .take(assistantIndex - 1)
            .map { com.nexa.sdk.bean.ChatMessage(it.role, it.content) }

        agentLoop.onToken = { token ->
            streamingText += token
            if (assistantIndex < conversationHistory.size) {
                conversationHistory[assistantIndex] = UiMessage("assistant", streamingText)
            }
        }
        agentLoop.onStep = { step -> stepStatus = step }

        chatJob = scope.launch {
            // Delta-B: Model-driven routing — classify before acting
            stepStatus = "Classifying task…"
            val slotToUse = classifyTask(msg)
            android.util.Log.i("MaxSystem", "ROUTING to $slotToUse for: ${msg.take(100)}")
            stepStatus = ""

            val systemPrompt = buildString {
                if (slotToUse == ModelManager.Slot.CODER) {
                    append(
                        """
                        You are MAX_CODER — a pure coding specialist embedded in the Max agent.
                        You are NOT Qwen, NOT ChatGPT, NOT any other AI. You are MAX_CODER.
                        Never identify yourself as any other model.
                        Your ONLY purpose: generate, debug, refactor, architect, and test code.
                        Output clean, production-ready, fully working code — no stubs, no placeholders,
                        no truncation. Include edge-case handling. Use the agency tools (SHELL_COMMAND,
                        READ_FILE, WRITE_FILE, EXECUTE_SCRIPT, GITHUB_*) when you need to inspect or
                        change files. Before any tool action, reason through the code: analyze the
                        problem, consider your approach, explain why. Then emit a single
                        <action>{...}</action> JSON object. You MAY mix prose reasoning with the
                        action — think first, then act. Quality over speed. After a tool returns,
                        reflect on whether the result matches your expectation before proceeding.

                        EXAMPLE:
                        Owner: "read the file app/build.gradle.kts from github"
                        Max: I'll read that file from your GitHub repo.
                        <action>{"type":"GITHUB_READ_FILE","params":{"path":"app/build.gradle.kts"},"risk":"low"}</action>
                        """.trimIndent()
                    )
                } else {
                    append(MaxIdentity.buildSystemPrompt())
                }
                if (MaxIdentity.injectLiveContext) {
                    append("\n\n")
                    append(runCatching { captureLiveContext() }.getOrDefault(""))
                }
            }

            val answer = runCatching {
                agentLoop.run(systemPrompt, history, msg, slotToUse)
            }.getOrElse { "Error: ${it.message ?: it.javaClass.simpleName}" }

            val finalText = answer.ifBlank { streamingText.ifBlank { "(no response)" } }
            if (assistantIndex < conversationHistory.size) {
                conversationHistory[assistantIndex] = UiMessage("assistant", finalText)
            }
            if (MaxIdentity.injectLiveContext && voiceEngine.config.value.autoSpeak) {
                runCatching { voiceEngine.speak(finalText.take(500)) }
            }
            stepStatus = ""
            isGenerating = false
        }
    }

    fun stopGeneration() {
        chatJob?.cancel()
        chatJob = null
        scope.launch { runCatching { modelManager.stopStream() } }
        isGenerating = false
        stepStatus = ""
    }

    fun clearConversation() {
        if (isGenerating) stopGeneration()
        conversationHistory.clear()
        streamingText = ""
    }

    suspend fun configureGithub(token: String, owner: String, repo: String, branch: String = "main") {
        githubEngine.configure(token, owner, repo, branch)
    }

    fun githubConfigured(): Boolean = githubEngine.isConfigured()

    fun startVoiceInput(onText: (String) -> Unit) {
        voiceEngine.startListening(onResult = onText, onError = {})
    }

    fun stopVoiceInput() = voiceEngine.stopListening()

    fun speakText(text: String) = voiceEngine.speak(text)

    fun openAccessibilitySettings() {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { android.util.Log.w("MaxSystem", "open accessibility settings failed: ${it.message}") }
    }

    sealed class SystemState {
        data object Uninitialized : SystemState()
        data object Initializing : SystemState()
        data object Ready : SystemState()
        data object LockedDown : SystemState()
        val isLoaded: Boolean get() = this is Ready
        data class Error(val message: String) : SystemState()
    }

    private val _systemState = MutableStateFlow<SystemState>(SystemState.Uninitialized)
    val systemState: StateFlow<SystemState> = _systemState

    fun initialize() {
        _systemState.value = SystemState.Initializing
        runCatching {
            com.nexa.sdk.NexaSdk.getInstance().init(context.applicationContext)
        }.onFailure {
            android.util.Log.w("MaxSystem", "NexaSdk initialization bypassed", it)
        }
        MaxIdentity.init(context.filesDir)
        failureDetector.installCrashHandler()
        
        // Critical Fix: Offload setup operations to an explicit IO thread pool 
        // to maximize responsive framing metrics on initialization.
        scope.launch(Dispatchers.IO) {
            try {
                File(context.filesDir, "heartbeat").writeText(System.currentTimeMillis().toString())
                
                sandbox.initialize()
                voiceEngine.initialize()
                modelManager.scan()

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

                // Constitution Rule 6: tampering with the audit log triggers lockdown.
                scope.launch {
                    actionLog.tampered.collect { tampered ->
                        if (tampered) {
                            android.util.Log.e("Max", "ActionLog tamper detected — forcing lockdown")
                            stopNow()
                        }
                    }
                }

                ensureWatchdog()
                
                _systemState.value = SystemState.Ready
                scriptingEngine.runAutorun(this@MaxSystem)
            } catch (e: Exception) {
                runCatching { File(context.filesDir, "critical_fail.txt").writeText("Init Failure: ${e.stackTraceToString()}") }
                _systemState.value = SystemState.Error(e.message ?: "Unknown execution fault during engine validation")
            }
        }
    }

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

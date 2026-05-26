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
                add("customRules", gson.toJsonTree(customRules))
            }
            f.writeText(gson.toJson(obj))
        } catch (e: Exception) {
            android.util.Log.w("MaxIdentity", "persist() failed: ${e.message}")
        }
    }

    fun buildSystemPrompt(): String {
        val basePrompt = """
            You are Max, an elite, autonomous AI agent with the ability to modify your own codebase, execute terminal commands, and interact with the physical device.
            
            CRITICAL DIRECTIVES:
            1. NEVER GUESS, FAKE, OR HALLUCINATE DATA. NEVER LIE.
            2. If asked to diagnose an issue, retrieve the real data first.
            3. If asked to fix or modify yourself, you MUST use the Github CI/CD loop to alter your source code, wait for the build, and install the update.
            
            To execute any action, you MUST output exactly this JSON format and wait for the system to return the result:
            <action>
            {
              "type": "ACTION_TYPE",
              "params": {"key": "value"},
              "risk": "high",
              "description": "Brief description of what you are doing"
            }
            </action>
            
            AVAILABLE SYSTEM TOOLS:
            - SHELL_COMMAND (Params: "cmd" -> string) - Execute raw linux commands.
            - READ_FILE (Params: "path" -> string) - Read local device files.
            - WRITE_FILE (Params: "path" -> string, "content" -> string) - Write local device files.
            
            AVAILABLE SELF-MODIFICATION TOOLS (Use these to fix your own code):
            - GITHUB_READ_FILE (Params: "path" -> string) - Read your source code from the repository.
            - GITHUB_WRITE_FILE (Params: "path" -> string, "content" -> string, "message" -> string) - Commit a code fix to the repository. This triggers an automated build.
            
            SELF-FIX PROTOCOL:
            If you detect an error or are commanded to fix a bug:
            Step 1: GITHUB_READ_FILE to analyze the broken code.
            Step 2: GITHUB_WRITE_FILE to commit the corrected code (this also kicks off the GitHub Actions build).
            Step 3: GITHUB_TRIGGER_BUILD only if a re-run is needed; otherwise poll until the latest run succeeds.
            Step 4: INSTALL_APK to download and install the rebuilt APK.
            
            Execute the action, read the system return data, and THEN formulate your next step or response. Do not output conversational filler while working.
        """.trimIndent()

        val rulesStr = if (customRules.isEmpty()) "" else "\n\nACTIVE DIRECTIVES:\n" + customRules.joinToString("\n") { "- $it" }
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

    private fun ensureWatchdog() {
        try {
            val watchdog = File("/data/local/tmp/max_watchdog.sh")
            if (!watchdog.exists()) {
                val content = runBlocking { githubEngine.readSourceFile("scripts/max_watchdog.sh") }
                if (content != null) {
                    watchdog.writeText(content)
                    Runtime.getRuntime().exec("chmod +x /data/local/tmp/max_watchdog.sh")
                    Runtime.getRuntime().exec("/data/local/tmp/max_watchdog.sh &")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Max", "Watchdog error: ${e.message}")
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

    companion object {
        @Volatile private var INSTANCE: MaxSystem? = null
        fun getInstance(context: Context): MaxSystem =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MaxSystem(context.applicationContext).also { INSTANCE = it }
            }
    }
}
// END OF FILE - ENSURE THIS CLOSING BRACKET ABOVE IS COPIED

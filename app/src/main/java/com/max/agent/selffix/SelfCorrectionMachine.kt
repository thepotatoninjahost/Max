package com.max.agent.selffix

import android.content.Context
import com.max.agent.agency.AgentLoop
import com.max.agent.core.MaxIdentity
import com.max.agent.models.ModelManager
import com.max.agent.terminal.TerminalEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class SelfCorrectionMachine(
    private val context: Context,
    private val failureDetector: FailureDetector,
    private val webTroubleshooter: WebTroubleshooter,
    private val terminal: TerminalEngine
) {

    enum class Phase {
        NOMINAL, DIAGNOSING, RULE_FIX, AGENT_REASONING,
        RECOVERED, QUEUED, UNRECOVERABLE
    }

    data class Attempt(
        val failure: FailureDetector.Failure,
        val phase: Phase = Phase.DIAGNOSING,
        val log: List<String> = emptyList(),
        val resolved: Boolean = false
    )

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        android.util.Log.e("Max", "SelfCorrection exception: ${t.message}")
    }

    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
    private val mutex     = Mutex()
    private val queueFile = File(context.filesDir, "recovery/queue.json")

    private val _phase   = MutableStateFlow(Phase.NOMINAL)
    private val _attempt = MutableStateFlow<Attempt?>(null)
    private val _history = MutableStateFlow<List<Attempt>>(emptyList())

    val phase:   StateFlow<Phase>         = _phase
    val attempt: StateFlow<Attempt?>      = _attempt
    val history: StateFlow<List<Attempt>> = _history

    private val pendingFailures = ConcurrentLinkedQueue<FailureDetector.Failure>()

    var agentLoop:    AgentLoop?    = null
    var modelManager: ModelManager? = null
    var patchGenerator: PatchGenerator? = null
    var hotSwapper: HotSwapper? = null

    fun start() {
        loadPersistedQueue()

        scope.launch {
            failureDetector.events.collect { failure ->
                if (_phase.value !in BUSY_PHASES) {
                    handle(failure)
                }
            }
        }

        scope.launch {
            modelManager?.state?.collect { modelState ->
                if (modelState.isLoaded && pendingFailures.isNotEmpty() && _phase.value !in BUSY_PHASES) {
                    drainQueue()
                }
            }
        }
    }

    fun resetToNominal() {
        _phase.value = Phase.NOMINAL
    }

    private suspend fun handle(failure: FailureDetector.Failure) = mutex.withLock {
        var a = Attempt(failure).log("⚡ ${failure.type}: ${failure.message}")
        set(a, Phase.DIAGNOSING)

        val ruleFix = tryRuleBasedFix(failure)
        if (ruleFix != null) {
            val r = runCatching { terminal.exec(ruleFix) }.getOrNull()
            if (r != null && r.success) {
                a = a.log("✓ Rule fix applied and verified: $ruleFix")
                finalize(a, Phase.RECOVERED)
                return@withLock
            }
            a = a.log("Rule fix did not resolve; escalating. cmd=$ruleFix exit=${r?.exitCode ?: "n/a"}")
        }

        val loop = agentLoop
        val mm   = modelManager
        if (loop != null && mm != null && mm.state.value.isLoaded) {
            agentFix(a, failure, loop)
            return@withLock
        }

        queue(a, failure)
    }

    /**
     * Generate a concrete code patch for code-level failures and return it as
     * extra context the agent loop can act on (commit via GITHUB_WRITE_FILE,
     * stage in the sandbox, etc). Returns empty string when no patch is produced.
     */
    private suspend fun buildPatchContext(failure: FailureDetector.Failure): String {
        val gen = patchGenerator ?: return ""
        val mm = modelManager ?: return ""
        if (!mm.state.value.isLoaded) return ""
        if (failure.type !in CODE_FAILURES) return ""

        val targetClass = failure.context["class"]
            ?: failure.stackTrace?.lineSequence()
                ?.firstOrNull { it.contains("com.max.agent") }
                ?.substringAfter("at ")?.substringBeforeLast('.')?.trim()
            ?: "com.max.agent.UnknownClass"

        val web = runCatching {
            webTroubleshooter.searchForFix(failure.message)
                .joinToString("\n") { "${it.title}: ${it.snippet}" }
        }.getOrDefault("")

        val patch = runCatching {
            gen.generate(
                errorMessage = failure.message,
                failedCode = failure.context["code"] ?: "",
                webContext = web,
                targetClass = targetClass
            )
        }.getOrNull() ?: return ""

        return buildString {
            appendLine("CANDIDATE PATCH (generated locally, saved to ${patch.savedPath ?: "memory"}):")
            appendLine("Target class: ${patch.targetClass}")
            appendLine("```kotlin")
            appendLine(patch.sourceCode)
            appendLine("```")
            appendLine("Review this candidate, correct it if needed, then apply it with GITHUB_WRITE_FILE and GITHUB_TRIGGER_BUILD, or stage it for owner approval. Verify before declaring done.")
        }
    }

    private suspend fun agentFix(
        a: Attempt,
        failure: FailureDetector.Failure,
        loop: AgentLoop,
        fromQueue: Boolean = false
    ): Attempt {
        var attempt = a.log(
            if (fromQueue) "⏳ Queued failure — model loaded, fixing now…"
            else "Routing to AgentLoop for reasoning…"
        )
        set(attempt, Phase.AGENT_REASONING)

        val patchContext = runCatching { buildPatchContext(failure) }.getOrDefault("")
        if (patchContext.isNotBlank()) {
            attempt = attempt.log("Candidate patch generated; supplying to agent for verification & commit.")
            set(attempt, Phase.AGENT_REASONING)
        }

        repeat(MAX_RETRIES) { tryIndex ->
            attempt = attempt.log("Attempt ${tryIndex + 1}/3")
            try {
                val answer = loop.run(
                    systemPrompt = MaxIdentity.buildSystemPrompt(),
                    history      = emptyList(),
                    userMessage  = buildTask(failure, tryIndex + 1) +
                        (if (patchContext.isNotBlank()) "\n\n$patchContext" else "")
                )
                attempt = attempt.log("✓ $answer")
                finalize(attempt, Phase.RECOVERED)
                return attempt
            } catch (e: Exception) {
                attempt = attempt.log("✗ Attempt ${tryIndex + 1} failed: ${e.message}")
                if (tryIndex < MAX_RETRIES - 1) delay(RETRY_BASE_DELAY_MS * (tryIndex + 1))
            }
        }

        attempt = attempt.log("All 3 attempts failed. Error remains unresolved.")
        finalize(attempt, Phase.UNRECOVERABLE)
        return attempt
    }

    private fun queue(a: Attempt, failure: FailureDetector.Failure) {
        pendingFailures.add(failure)
        persistQueue()
        finalize(
            a.log("⏱ No model loaded — queued to disk (${pendingFailures.size} pending). Will fix when model loads."),
            Phase.QUEUED
        )
    }

    private suspend fun drainQueue() {
        if (!mutex.tryLock()) return
        try {
            val loop = agentLoop ?: return
            while (pendingFailures.isNotEmpty()) {
                if (modelManager?.state?.value?.isLoaded != true) break
                val failure = pendingFailures.poll() ?: break
                persistQueue()
                agentFix(Attempt(failure), failure, loop, fromQueue = true)
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun tryRuleBasedFix(f: FailureDetector.Failure): String? {
        if (f.type != FailureDetector.FailureType.COMMAND_FAILURE) return null
        val cmd = f.context["command"] ?: return null
        return when {
            f.message.contains("Permission denied") -> {
                val firstToken = cmd.trim().substringBefore(' ').trim()
                if (firstToken.isBlank()) return null
                val safePath = firstToken.replace("'", "'\\''")
                "chmod +x '$safePath' && $cmd"
            }
            else -> null
        }
    }

    private fun buildTask(f: FailureDetector.Failure, attempt: Int) = buildString {
        if (attempt > 1) appendLine("Previous attempt failed. Try a completely different approach.")
        appendLine("SELF-HEALING TASK: Fix this error. The actual fix — not a workaround.")
        appendLine("Error type: ${f.type}")
        appendLine("Message: ${f.message}")
        if (!f.stackTrace.isNullOrBlank()) appendLine("Stack: ${f.stackTrace.take(400)}")
        f.context.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("Use EXECUTE_SCRIPT for Android API fixes (runs immediately via Rhino JS).")
        appendLine("Use SAVE_AUTORUN_SCRIPT to make the fix survive restarts.")
        appendLine("Use SHELL_COMMAND for shell-level issues.")
        appendLine("Verify the fix actually worked before declaring done.")
    }

    private fun persistQueue() {
        runCatching {
            queueFile.parentFile?.mkdirs()
            val arr = JSONArray()
            pendingFailures.forEach { f ->
                arr.put(JSONObject().apply {
                    put("type",    f.type.name)
                    put("message", f.message)
                    put("stack",   f.stackTrace ?: "")
                    put("ts",      f.timestamp)
                    val ctx = JSONObject()
                    f.context.forEach { (k, v) -> ctx.put(k, v) }
                    put("context", ctx)
                })
            }
            queueFile.writeText(arr.toString())
        }
    }

    private fun loadPersistedQueue() {
        runCatching {
            if (!queueFile.exists()) return
            val arr = JSONArray(queueFile.readText())
            for (i in 0 until arr.length()) {
                val obj  = arr.getJSONObject(i)
                val type = runCatching {
                    FailureDetector.FailureType.valueOf(obj.getString("type"))
                }.getOrDefault(FailureDetector.FailureType.UNKNOWN)
                val ctxObj = obj.optJSONObject("context")
                val ctx    = mutableMapOf<String, String>()
                ctxObj?.keys()?.forEach { k -> ctx[k] = ctxObj.getString(k) }
                pendingFailures.add(
                    FailureDetector.Failure(
                        type       = type,
                        message    = obj.getString("message"),
                        stackTrace = obj.optString("stack").ifBlank { null },
                        context    = ctx,
                        timestamp  = obj.optLong("ts", System.currentTimeMillis())
                    )
                )
            }
        }
    }

    private fun set(a: Attempt, phase: Phase) {
        _phase.value   = phase
        _attempt.value = a.copy(phase = phase)
    }

    private fun finalize(a: Attempt, phase: Phase) {
        val done = a.copy(phase = phase, resolved = phase == Phase.RECOVERED)
        _phase.value   = phase
        _attempt.value = done
        _history.value = (_history.value + done).takeLast(50)
    }

    private fun Attempt.log(msg: String) = copy(log = log + msg)

    companion object {
        private const val MAX_RETRIES         = 3
        private const val RETRY_BASE_DELAY_MS = 1_000L
        private val BUSY_PHASES = setOf(Phase.DIAGNOSING, Phase.RULE_FIX, Phase.AGENT_REASONING)
        private val CODE_FAILURES = setOf(
            FailureDetector.FailureType.CRASH,
            FailureDetector.FailureType.RUNTIME_EXCEPTION,
            FailureDetector.FailureType.COMPILATION_ERROR
        )
    }
}

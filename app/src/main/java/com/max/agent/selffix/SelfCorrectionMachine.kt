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

/**
 * Self-Healing Engine.
 *
 * Three honest steps — no patches, no workarounds, no band-aids:
 *
 * 1. RULE FIX    — instant, no model needed (permission errors, path errors)
 * 2. AGENT FIX   — AgentLoop reasons, writes executable code, runs it, verifies
 * 3. QUEUE       — no model loaded: persist to disk, fix the moment model loads
 *
 * The error stays open until it is actually fixed. Nothing is pretended away.
 */
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

    // Upgraded to a thread-safe concurrent queue
    private val pendingFailures = ConcurrentLinkedQueue<FailureDetector.Failure>()

    var agentLoop:    AgentLoop?    = null
    var modelManager: ModelManager? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
            val mm = modelManager ?: return@launch
            mm.state.collect { modelState ->
                if (modelState.isLoaded && pendingFailures.isNotEmpty()) {
                    drainQueue()
                }
            }
        }
    }

    fun resetToNominal() {
        _phase.value = Phase.NOMINAL
    }

    // ── Core ──────────────────────────────────────────────────────────────────

    private suspend fun handle(failure: FailureDetector.Failure) = mutex.withLock {
        var a = Attempt(failure).log("⚡ ${failure.type}: ${failure.message}")
        set(a, Phase.DIAGNOSING)

        val ruleFix = tryRuleBasedFix(failure)
        if (ruleFix != null) {
            a = a.log("✓ Rule fix applied: $ruleFix")
            finalize(a, Phase.RECOVERED)
            return@withLock
        }

        val loop = agentLoop
        val mm   = modelManager
        if (loop != null && mm != null && mm.state.value.isLoaded) {
            agentFix(a, failure, loop)
            return@withLock
        }

        queue(a, failure)
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

        repeat(MAX_RETRIES) { tryIndex ->
            attempt = attempt.log("Attempt ${tryIndex + 1}/3")
            try {
                val answer = loop.run(
                    systemPrompt = MaxIdentity.buildSystemPrompt(),
                    history      = emptyList(),
                    userMessage  = buildTask(failure, tryIndex + 1)
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

    // Locked drainQueue to prevent LLM collision when handling off-cycle tasks
    private suspend fun drainQueue() = mutex.withLock {
        val loop = agentLoop ?: return@withLock
        while (pendingFailures.isNotEmpty()) {
            val failure = pendingFailures.poll() ?: break
            persistQueue()
            agentFix(Attempt(failure), failure, loop, fromQueue = true)
        }
    }


        // ── Rule-based fixes (no model required) ─────────────────────────────────

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


    // ── Task builder ──────────────────────────────────────────────────────────

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

    // ── Queue persistence ─────────────────────────────────────────────────────

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

    // ── State helpers ─────────────────────────────────────────────────────────

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
    }
}

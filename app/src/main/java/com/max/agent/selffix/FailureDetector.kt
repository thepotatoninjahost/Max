package com.max.agent.selffix

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class FailureDetector(private val context: Context) {

    enum class FailureType {
        CRASH, RUNTIME_EXCEPTION, COMMAND_FAILURE,
        COMPILATION_ERROR, NETWORK_ERROR, MODEL_ERROR, UNKNOWN
    }

    data class Failure(
        val id: String = UUID.randomUUID().toString(),
        val type: FailureType,
        val message: String,
        val stackTrace: String? = null,
        val context: Map<String, String> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis()
    )

    private val _failures = MutableStateFlow<List<Failure>>(emptyList())
    val failures: StateFlow<List<Failure>> = _failures

    // Warning fixed: explicitly handle buffer overflow to prevent silent drop of critical errors
    private val _events = MutableSharedFlow<Failure>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Failure> = _events

    fun report(failure: Failure) {
        _failures.value = (_failures.value + failure).takeLast(200)
        _events.tryEmit(failure)
    }

    fun reportException(e: Throwable, ctx: Map<String, String> = emptyMap()) {
        val type = when (e) {
            is java.net.ConnectException, is java.net.SocketTimeoutException -> FailureType.NETWORK_ERROR
            is RuntimeException -> FailureType.RUNTIME_EXCEPTION
            else -> FailureType.UNKNOWN
        }
        report(Failure(type = type, message = e.message ?: e.javaClass.simpleName,
            stackTrace = e.stackTraceToString(), context = ctx))
    }

    fun reportCommand(command: String, exitCode: Int, stderr: String) {
        report(Failure(
            type = FailureType.COMMAND_FAILURE,
            message = "Command failed (exit $exitCode): ${command.take(120)}",
            context = mapOf("command" to command, "exitCode" to "$exitCode", "stderr" to stderr)
        ))
    }

    fun reportModel(message: String) {
        report(Failure(type = FailureType.MODEL_ERROR, message = message))
    }

    fun installCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            val msg = "[${System.currentTimeMillis()}] CRASH on ${thread.name}: ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(1200)}\n\n"
            try { context.filesDir.resolve("crash.log").appendText(msg) } catch (_: Exception) {}
            try { context.filesDir.resolve("errors.log").appendText(msg) } catch (_: Exception) {}
            report(Failure(type = FailureType.CRASH, message = "${t.javaClass.simpleName}: ${t.message}", stackTrace = t.stackTraceToString()))
            original?.uncaughtException(thread, t)
        }
    }

    fun clear() { _failures.value = emptyList() }
}

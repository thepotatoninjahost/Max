package com.max.agent.terminal

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream

class TerminalEngine(private val context: Context) {

    var failureDetector: com.max.agent.selffix.FailureDetector? = null

    companion object {
        private const val MAX_OUTPUT_CHARS = 100_000
    }

    data class CommandResult(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long
    ) {
        val success: Boolean get() = exitCode == 0
        val output: String get() = buildString {
            if (stdout.isNotBlank()) append(stdout)
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(stderr)
            }
        }
    }

    data class HistoryEntry(
        val id: Long = System.currentTimeMillis(),
        val command: String,
        val output: String,
        val exitCode: Int,
        val isError: Boolean
    )

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        try { 
            context.filesDir.resolve("errors.log")
                .appendText("[${System.currentTimeMillis()}] ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(800)}\n\n") 
        } catch (_: Exception) {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
    private val mutex = Mutex()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    suspend fun exec(command: String, timeoutMs: Long = 30_000L): CommandResult {
        return mutex.withLock {
            _isRunning.value = true
            val start = System.currentTimeMillis()
            var process: Process? = null
            
            try {
                withTimeout(timeoutMs) {
                    withContext(Dispatchers.IO) {
                        // UPGRADE: Launch an interactive naked shell and feed complex multi-line AI scripts via STDIN. 
                        // This bypasses Android mksh parsing failures and truncation limits of the "-c" argument flag.
                        // Resolve absolute shell path. The Android app environment has no useful PATH
                        // by default, so `ProcessBuilder("sh")` may fail to locate the shell.
                        val shellPath = listOf("/system/bin/sh", "/system/xbin/sh")
                            .firstOrNull { java.io.File(it).canExecute() } ?: "sh"

                        val builder = ProcessBuilder(shellPath)
                            .directory(context.filesDir)
                            .redirectErrorStream(false)

                        // Inject a usable POSIX environment. Without these, most commands
                        // (mktemp, awk, sed pipelines, etc.) silently fail with cryptic errors.
                        val env = builder.environment()
                        val existingPath = env["PATH"].orEmpty()
                        val basePath = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
                        val nativeLib = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
                        env["PATH"] = listOfNotNull(
                            existingPath.takeIf { it.isNotBlank() },
                            basePath,
                            nativeLib
                        ).joinToString(":")
                        env["HOME"] = context.filesDir.absolutePath
                        env["TMPDIR"] = context.cacheDir.absolutePath
                        env["LANG"] = "C.UTF-8"
                        env["TERM"] = "dumb"
                        env["PWD"] = context.filesDir.absolutePath

                        process = builder.start()

                        val p = process!!
                        
                        // Feed command natively to pipe, then close it. Closing stdin forces the shell 
                        // to exit gracefully when the script is done, entirely preventing infinite hangs.
                        p.outputStream.writer().use { writer ->
                            writer.write("$command\nexit\n")
                            writer.flush()
                        }

                        val stdoutDef = async { p.inputStream.readSafely(MAX_OUTPUT_CHARS) }
                        val stderrDef = async { p.errorStream.readSafely(MAX_OUTPUT_CHARS) }
                        
                        p.waitFor()
                        
                        val result = CommandResult(
                            command    = command,
                            stdout     = stdoutDef.await().trim(),
                            stderr     = stderrDef.await().trim(),
                            exitCode   = p.exitValue(),
                            durationMs = System.currentTimeMillis() - start
                        )
                        appendHistory(result)
                        if (!result.success) failureDetector?.reportCommand(command, result.exitCode, result.stderr)
                        result
                    }
                }
            } catch (e: Exception) {
                val result = CommandResult(
                    command = command,
                    stdout = "",
                    stderr = e.message ?: "Execution aborted or timed out",
                    exitCode = -1,
                    durationMs = System.currentTimeMillis() - start
                )
                appendHistory(result)
                result
            } finally {
                // CRITICAL FIX: Explicitly closing streams intercepts Java's blocking I/O blindspot. 
                // This forces reader.read() to instantly throw an IOException, releasing the leaked thread back to the IO pool.
                process?.let { p ->
                    runCatching { p.inputStream.close() }
                    runCatching { p.errorStream.close() }
                    p.destroy()
                }
                _isRunning.value = false
            }
        }
    }

    private fun InputStream.readSafely(limit: Int): String {
        return try {
            val reader = bufferedReader()
            val sb = StringBuilder()
            val buffer = CharArray(4096)
            var totalRead = 0
            
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                
                val toAppend = minOf(read, limit - totalRead)
                sb.append(buffer, 0, toAppend)
                totalRead += toAppend
                
                if (totalRead >= limit) {
                    sb.append("\n...[TRUNCATED: Exceeded memory safety limit of $limit characters]...")
                    break
                }
            }
            sb.toString()
        } catch (e: Exception) {
            "[Stream Read Terminated]"
        }
    }

    suspend fun execScript(commands: List<String>): List<CommandResult> =
        commands.map { exec(it) }

    fun execAsync(command: String, onResult: (CommandResult) -> Unit = {}) {
        scope.launch { onResult(exec(command)) }
    }

    fun diagnoseSelf(): String = buildString {
        appendLine("=== Max Self-Diagnostic ===")
        appendLine("Files dir: ${context.filesDir}")
        appendLine("Cache dir: ${context.cacheDir}")
        appendLine("Models dir: ${context.filesDir}/models")
        val modelsDir = java.io.File(context.filesDir, "models")
        if (modelsDir.exists()) {
            modelsDir.listFiles()?.forEach { appendLine("  model: ${it.name} (${it.length() / 1_048_576}MB)") }
        } else {
            appendLine("  (no models directory)")
        }
    }

    private fun appendHistory(result: CommandResult) {
        val entry = HistoryEntry(
            command = result.command,
            output = result.output,
            exitCode = result.exitCode,
            isError = !result.success
        )
        _history.value = (_history.value + entry).takeLast(500)
    }

    fun clearHistory() { _history.value = emptyList() }
}

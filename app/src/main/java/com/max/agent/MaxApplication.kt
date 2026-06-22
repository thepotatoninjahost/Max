package com.max.agent

import android.app.Application
import android.util.Log
import com.nexa.sdk.NexaSdk
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application entry point — initializes the Nexa SDK and detects native crashes.
 *
 * SDK init: NexaSdk.getInstance().init() registers all plugins (cpu_gpu, npu,
 * etc.) and extracts HTP runtime assets. This MUST complete before any
 * LlmWrapper is created.
 *
 * Native crash detection: ModelManager writes a marker file before each native
 * llm.create() call. If the app dies (SIGSEGV), the marker survives. On the
 * next boot, we detect it, grab logcat, and write the trace to crash.log so
 * the user can see what happened — Java's UncaughtExceptionHandler cannot
 * catch native SIGSEGV, so this marker-based approach is the only way.
 */
class MaxApplication : Application() {

    companion object {
        private const val TAG = "MaxApplication"
        @Volatile var sdkInitialized = false
            private set
        @Volatile var sdkInitError: String? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()

        detectNativeCrash()

        Log.d(TAG, "Initializing Nexa SDK...")
        NexaSdk.getInstance().init(this, object : NexaSdk.InitCallback {
            override fun onSuccess() {
                sdkInitialized = true
                sdkInitError = null
                Log.d(TAG, "Nexa SDK initialized — plugins registered, HTP assets extracted")
            }

            override fun onFailure(reason: String) {
                sdkInitialized = false
                sdkInitError = reason
                Log.e(TAG, "Nexa SDK init FAILED: $reason")
                writeCrashLog("NEXA SDK INIT FAILURE:\n$reason\n")
            }
        })
    }

    /**
     * If a native_crash_marker.json exists from the previous session, a native
     * crash occurred during model loading. Capture logcat (the native backtrace
     * is written there by Android's tombstoned) and persist it to crash.log.
     */
    private fun detectNativeCrash() {
        val marker = File(filesDir, "native_crash_marker.json")
        if (!marker.exists()) return

        val markerContent = try { marker.readText() } catch (_: Exception) { return }
        val json = try { JSONObject(markerContent) } catch (_: Exception) { null }

        val model = json?.optString("model") ?: "unknown"
        val device = json?.optString("device") ?: "unknown"
        val ts = json?.optLong("timestamp") ?: 0L
        val timeStr = if (ts > 0) SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts)) else "?"

        val logcatSnippet = captureLogcat()

        val report = buildString {
            append("NATIVE CRASH DETECTED (SIGSEGV during model load)\n")
            append("Model: $model\n")
            append("Device attempt: $device\n")
            append("Time of crash: $timeStr\n")
            append("\n--- logcat (last 150 lines) ---\n")
            append(logcatSnippet)
            append("\n--- end ---\n")
        }

        writeCrashLog(report)
        marker.delete()
        Log.e(TAG, "Native crash from previous session detected — see crash.log")
    }

    /**
     * Reads the last N lines of the system logcat buffer. On Android, apps can
     * read their own process's log entries without special permissions. The
     * native crash backtrace from tombstoned may appear here.
     */
    private fun captureLogcat(maxLines: Int = 150): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", maxLines.toString()))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.appendLine(line)
            }
            reader.close()
            process.waitFor()
            sb.toString()
        } catch (e: Exception) {
            "(logcat capture failed: ${e.message})\n"
        }
    }

    private fun writeCrashLog(content: String) {
        try {
            val logFile = File(filesDir, "crash.log")
            logFile.parentFile?.mkdirs()
            logFile.writeText(content)
        } catch (_: Exception) {}
    }
}

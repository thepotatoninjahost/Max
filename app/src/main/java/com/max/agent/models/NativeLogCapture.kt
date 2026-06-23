package com.max.agent.models

import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Captures native logcat output LIVE during model load attempts.
 *
 * The Nexa SDK's native layer (QNN, ggml, HTP runtime) logs the REAL error
 * details to logcat with tags like "NexaSDK", "QNN", "ggml", "htp".
 * Java's RuntimeException("Llm create failed, error code: -XXXXX") is a
 * generic wrapper — the actual failure reason is in logcat.
 *
 * Usage:
 *   val capture = NativeLogCapture.start()
 *   // ... attempt model load ...
 *   val nativeLog = capture.stop()
 */
class NativeLogCapture {

    private var process: Process? = null
    private val buffer = StringBuilder()
    private var captureThread: Thread? = null
    private val startTime = System.currentTimeMillis()

    companion object {
        private const val TAG = "NativeLogCapture"

        /**
         * Detects whether this device supports Qualcomm NPU (Hexagon HTP).
         * Per Nexa docs: NPU support requires Snapdragon 8 Elite or 8 Elite Gen 5.
         * Attempting NPU on unsupported hardware fails AND can corrupt the QNN
         * runtime state, poisoning subsequent CPU/GPU fallback attempts.
         */
        fun isNpuSupported(): Boolean {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".lowercase()
            } else {
                "unknown"
            }
            val hardware = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val fingerprint = Build.FINGERPRINT.lowercase()

            // Snapdragon 8 Elite = SM8750. 8 Elite Gen 5 = SM8850.
            // Also check common marketing strings.
            val npuIndicators = listOf(
                "sm8750", "sm8850", "snapdragon 8 elite", "qualcomm snapdragon 8 elite"
            )
            return npuIndicators.any { indicator ->
                soc.contains(indicator) ||
                hardware.contains(indicator) ||
                board.contains(indicator) ||
                fingerprint.contains(indicator)
            }
        }

        /**
         * Returns a human-readable chipset identifier for logging.
         */
        fun chipsetInfo(): String {
            return buildString {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    append("SOC_MFR=${Build.SOC_MANUFACTURER ?: "unknown"}")
                    append(" SOC_MODEL=${Build.SOC_MODEL ?: "unknown"}")
                } else {
                    append("SOC_MFR=unknown (API < 31)")
                    append(" SOC_MODEL=unknown (API < 31)")
                }
                append(" BOARD=${Build.BOARD}")
                append(" HW=${Build.HARDWARE}")
                append(" DEVICE=${Build.DEVICE}")
                append(" MODEL=${Build.MODEL}")
                append(" NPU_SUPPORTED=${isNpuSupported()}")
            }
        }

        fun start(): NativeLogCapture {
            val capture = NativeLogCapture()
            capture.beginCapture()
            return capture
        }
    }

    private fun beginCapture() {
        try {
            // Capture all logcat from this point forward, filtering for relevant tags.
            // -d won't work here because we need LIVE output. Use -T to start from now.
            val cmd = arrayOf("logcat", "-T", "1", "-v", "time")
            process = Runtime.getRuntime().exec(cmd)

            captureThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // Filter for relevant log lines — native SDK, errors, and our tags
                        val lower = line!!.lowercase()
                        if (lower.contains("nexa") ||
                            lower.contains("qnn") ||
                            lower.contains("ggml") ||
                            lower.contains("htp") ||
                            lower.contains("llama") ||
                            lower.contains("model") ||
                            lower.contains("error") ||
                            lower.contains("fatal") ||
                            lower.contains("fail") ||
                            lower.contains("memory") ||
                            lower.contains("alloc") ||
                            lower.contains("oom") ||
                            lower.contains("maxagent") ||
                            lower.contains("maxapplication") ||
                            lower.contains("modelmanager")
                        ) {
                            buffer.appendLine(line)
                            // Cap at 50KB to avoid unbounded growth
                            if (buffer.length > 50000) {
                                buffer.setLength(0)
                                buffer.append("[...truncated earlier log...]\n")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }
            captureThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logcat capture", e)
        }
    }

    /**
     * Stops capture and returns the filtered native log output.
     */
    fun stop(): String {
        try {
            process?.destroy()
            captureThread?.join(2000)
        } catch (_: Exception) {}

        val duration = System.currentTimeMillis() - startTime
        val header = "=== Native Log Capture (${duration}ms) ===\n${chipsetInfo()}\n"
        return header + buffer.toString()
    }
}

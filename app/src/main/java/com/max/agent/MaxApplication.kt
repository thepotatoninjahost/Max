package com.max.agent

import android.app.Application
import android.util.Log
import com.nexa.sdk.NexaSdk

/**
 * Application entry point — initializes the Nexa SDK before any model operation.
 *
 * CRITICAL: NexaSdk.getInstance().init(this) MUST be called before any LlmWrapper
 * is created. Without it:
 *   - The NPU plugin (.so) is never registered via registerPlugin()
 *   - HTP runtime assets (Qualcomm NPU firmware) are never extracted
 *   - Environment variables (NEXA_QNN_HTP_PATH, key_npu_lib_folder_path) are never set
 *   - Llm.create() in native code hits an unregistered plugin → SIGSEGV (native crash)
 *
 * This was the root cause of the "crashes the second a model loads" defect.
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
        Log.d(TAG, "Initializing Nexa SDK...")
        NexaSdk.getInstance().init(this, object : NexaSdk.InitCallback {
            override fun onSuccess() {
                sdkInitialized = true
                sdkInitError = null
                Log.d(TAG, "Nexa SDK initialized successfully — NPU plugin registered, HTP assets extracted")
            }

            override fun onFailure(reason: String) {
                sdkInitialized = false
                sdkInitError = reason
                Log.e(TAG, "Nexa SDK init FAILED: $reason")
                // Write to crash log so the user can see it in the Log tab
                try {
                    val logFile = java.io.File(filesDir, "crash.log")
                    logFile.parentFile?.mkdirs()
                    logFile.writeText("NEXA SDK INIT FAILURE:\n$reason\n\nThe NPU plugin could not be loaded. Check that the AAR ships all native libraries.\n")
                } catch (_: Exception) {}
            }
        })
    }
}

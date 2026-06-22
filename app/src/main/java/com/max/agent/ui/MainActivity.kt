package com.max.agent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.max.agent.core.MaxSystem
import com.nexa.sdk.NexaSdk

class MainActivity : FragmentActivity() {

    private lateinit var max: MaxSystem
    private lateinit var vpnConsentLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install global crash handler FIRST — before any SDK init.
        // Captures any uncaught exception (including OutOfMemoryError) to a
        // file the user can read back to us for diagnosis.
        val crashFile = java.io.File(filesDir, "crash.log")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile.writeText(
                    "CRASH: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n" +
                    "Thread: ${thread.name}\n" +
                    "Exception: ${throwable.javaClass.name}\n" +
                    "Message: ${throwable.message}\n\n" +
                    throwable.stackTraceToString().take(3000)
                )
            } catch (_: Throwable) {}
            previousHandler?.uncaughtException(thread, throwable)
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        vpnConsentLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && ::max.isInitialized) {
                max.networkGuard.onEnforcementConsentGranted()
            }
        }

        var startupError: String? = null

        try {
            NexaSdk.getInstance().init(this)
            max = MaxSystem.getInstance(this)
            max.networkGuard.consentRequester = { intent -> vpnConsentLauncher.launch(intent) }
            max.initialize()
            
            if (intent?.getBooleanExtra("assist_invoked", false) == true) {
                val pkg = intent.getStringExtra("assist_foreground_pkg") ?: "(unknown)"
                val uri = intent.getStringExtra("assist_web_uri")
                max.recordAssistInvocation(pkg, uri)
            }
        } catch (e: Throwable) {
            startupError = "${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString().take(800)}"
        }

        setContent {
            MaxTheme {
                if (startupError != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A0000))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⛔ Max failed to start:\n\n$startupError",
                            color = Color(0xFFFF6B6B),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    MaxApp(max = max)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::max.isInitialized) {
            runCatching { max.shutdown() }
        }
    }
}

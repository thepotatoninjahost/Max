package com.max.agent.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var startupError: String? = null

        try {
            NexaSdk.getInstance().init(this)
            max = MaxSystem.getInstance(this)
            max.initialize()
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
        if (::max.isInitialized) max.shutdown()
    }
}

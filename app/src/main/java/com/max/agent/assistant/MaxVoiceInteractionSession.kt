package com.max.agent.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.max.agent.ui.MainActivity

/**
 * The live assist session — created when the owner triggers the assist gesture
 * (long-press home, power-button long-press, or "OK Google" / "Hey Max" hotword).
 *
 * Captures the current foreground app structure + screenshot, then hands off
 * to MainActivity so Max can act on what the owner is looking at.
 */
class MaxVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        Log.i("MaxAssist", "Assist session created")
    }

    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)

        // Stash what the owner was looking at, then route into Max.
        val foregroundPkg = structure?.activityComponent?.packageName ?: "(unknown)"
        val webUri = content?.webUri?.toString()
        val structuredData = content?.structuredData

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("assist_foreground_pkg", foregroundPkg)
            putExtra("assist_web_uri", webUri)
            putExtra("assist_structured_data", structuredData)
            putExtra("assist_invoked", true)
        }
        startVoiceActivity(launch)
        hide()
    }

    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
        super.onHandleScreenshot(screenshot)
        // The screenshot is delivered here. We could persist it to filesDir
        // for Max to inspect, but for now we just log size; future work can
        // route this through the multimodal slot once a VLM is loaded.
        screenshot?.let { Log.i("MaxAssist", "Got screen ${it.width}x${it.height}") }
    }
}

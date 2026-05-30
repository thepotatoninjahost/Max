package com.max.agent.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Entry point for the "Default Digital Assistant" role on Android.
 */
class MaxVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i("MaxAssist", "VoiceInteractionService ready")
    }

    override fun onShutdown() {
        Log.i("MaxAssist", "VoiceInteractionService shutdown")
        super.onShutdown()
    }
}

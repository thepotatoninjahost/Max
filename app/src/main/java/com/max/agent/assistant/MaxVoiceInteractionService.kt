package com.max.agent.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Entry point for the "Default Digital Assistant" role on Android.
 *
 * Once the owner selects Max in Settings -> Default apps -> Digital assistant,
 * this service is bound by the system. Long-press home (or the configured
 * assist gesture) routes through here, which then spawns a
 * MaxVoiceInteractionSession via MaxVoiceInteractionSessionService.
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

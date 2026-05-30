package com.max.agent.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Factory for MaxVoiceInteractionSession instances.
 * Bound by the platform each time the assist gesture fires.
 */
class MaxVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return MaxVoiceInteractionSession(this)
    }
}

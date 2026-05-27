package com.max.agent.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Minimal RecognitionService required for any app to register as a full
 * default assistant on Android: per AOSP voice-interaction guidelines,
 * a VIA *must* declare both a VoiceInteractionService and a
 * RecognitionService.
 *
 * Max receives its transcripts from the VoiceInteractionSession flow,
 * not through the SpeechRecognizer API, so this service does not need to
 * perform real recognition - it exists to satisfy the Samsung /OEM
 * assistant-chooser contract. All callbacks respond with ERROR_CLIENT
 * so any app that does try to use it receives a clean failure instead
 * of hanging indefinitely.
 */
class MaxRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}

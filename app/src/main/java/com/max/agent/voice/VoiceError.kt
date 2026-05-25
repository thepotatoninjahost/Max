package com.max.agent.voice

sealed class VoiceError {
    data object MicBlocked : VoiceError()
    data object NetworkRequired : VoiceError()
    data object RecognizerBusy : VoiceError()
    data class Unknown(val code: Int) : VoiceError()
}

package com.max.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceEngine(
    private val context: Context,
    private val scope: CoroutineScope
) : RecognitionListener, TextToSpeech.OnInitListener {

    enum class Mode { IDLE, LISTENING, SPEAKING }

    data class Config(
        val autoSpeak: Boolean = true,
        val handsFree: Boolean = false,
        val preferOffline: Boolean = false
    )

    private val _mode = MutableStateFlow(Mode.IDLE)
    val mode: StateFlow<Mode> = _mode

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript

    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((VoiceError) -> Unit)? = null
    private var onSpeakDoneCallback: (() -> Unit)? = null

    fun initialize() {
        scope.launch(Dispatchers.Main) {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer?.setRecognitionListener(this@VoiceEngine)
            }
            tts = TextToSpeech(context, this@VoiceEngine)
        }
    }

    fun updateConfig(block: Config.() -> Config) {
        _config.update { it.block() }
    }

    fun startListening(onResult: (String) -> Unit, onError: (VoiceError) -> Unit = {}) {
        scope.launch(Dispatchers.Main) {
            if (recognizer == null) {
                onError(VoiceError.Unknown(-1))
                return@launch
            }
            
            onResultCallback = onResult
            this@VoiceEngine.onErrorCallback = onError
            
            _mode.value = Mode.LISTENING
            _transcript.value = ""
            _error.value = null

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (_config.value.preferOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
            
            try {
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                _mode.value = Mode.IDLE
                _error.value = "Failed to start listening: ${e.message}"
            }
        }
    }

    fun stopListening() {
        scope.launch(Dispatchers.Main) {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) {
                // Ignore errors on stop
            } finally {
                _mode.value = Mode.IDLE
                _rms.value = 0f
            }
        }
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank() || tts == null) {
            onDone()
            return
        }
        
        onSpeakDoneCallback = onDone
        _mode.value = Mode.SPEAKING
        
        val utteranceId = java.util.UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
        _mode.value = Mode.IDLE
        onSpeakDoneCallback?.invoke()
        onSpeakDoneCallback = null
    }

    fun setVoice(gender: String, pitch: Float, rate: Float) {
        val engine = tts ?: return
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
        runCatching {
            val target = gender.lowercase().trim()
            val currentLocale = engine.voice?.locale
            val candidates = engine.voices ?: return@runCatching
            val match = candidates
                .filter { v ->
                    val name = v.name?.lowercase() ?: ""
                    when (target) {
                        "male" -> "male" in name && "female" !in name
                        "female" -> "female" in name
                        "neutral", "" -> true
                        else -> target in name
                    }
                }
                .sortedByDescending { v -> if (currentLocale != null && v.locale == currentLocale) 1 else 0 }
                .firstOrNull()
            if (match != null) {
                engine.voice = match
            } else {
                android.util.Log.i("VoiceEngine", "No TTS voice matched gender='$gender'; keeping current voice")
            }
        }.onFailure {
            android.util.Log.w("VoiceEngine", "setVoice() failed: ${it.message}")
        }
    }

    fun destroy() {
        scope.launch(Dispatchers.Main) {
            try {
                recognizer?.destroy()
            } catch (_: Exception) {}
            recognizer = null
            
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Exception) {}
            tts = null
        }
    }

    // ─── TextToSpeech.OnInitListener ─────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                
                override fun onDone(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) {
                        _mode.value = Mode.IDLE
                        onSpeakDoneCallback?.invoke()
                        onSpeakDoneCallback = null
                    }
                }
                
                @Suppress("OVERRIDE_DEPRECATION") override fun onError(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) {
                        _mode.value = Mode.IDLE
                        _error.value = "TTS Error"
                        onSpeakDoneCallback?.invoke()
                        onSpeakDoneCallback = null
                    }
                }
            })
        } else {
            _error.value = "TTS Initialization failed"
        }
    }

    // ─── RecognitionListener ──────────────────────────────────────────────────

    override fun onReadyForSpeech(params: Bundle?) {}
    
    override fun onBeginningOfSpeech() {}
    
    override fun onRmsChanged(rmsdB: Float) { 
        _rms.value = rmsdB 
    }
    
    @Suppress("OVERRIDE_DEPRECATION") override fun onBufferReceived(buffer: ByteArray?) {}
    
    override fun onEndOfSpeech() { 
        _rms.value = 0f 
    }
    
    override fun onError(error: Int) {
        scope.launch(Dispatchers.Main) {
            _mode.value = Mode.IDLE
            _rms.value = 0f
            
            val voiceError = when (error) {
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError.NetworkRequired
                SpeechRecognizer.ERROR_AUDIO -> VoiceError.MicBlocked
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.RecognizerBusy
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    // Silent fail, just reset
                    return@launch 
                }
                else -> VoiceError.Unknown(error)
            }
            
            _error.value = "Speech Error code: $error"
            onErrorCallback?.invoke(voiceError)
        }
    }

    override fun onResults(results: Bundle?) {
        scope.launch(Dispatchers.Main) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val finalResult = matches?.firstOrNull() ?: ""
            
            _transcript.value = finalResult
            _mode.value = Mode.IDLE
            
            if (finalResult.isNotBlank()) {
                onResultCallback?.invoke(finalResult)
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        matches?.firstOrNull()?.let { 
            _transcript.value = it 
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}

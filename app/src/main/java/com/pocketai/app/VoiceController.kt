package com.pocketai.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceController(
    private val context: Context,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setPitch(0.68f)
                tts?.setSpeechRate(0.90f)
                val deepVoice = tts?.voices
                    ?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
                    ?.firstOrNull {
                        val n = it.name.lowercase()
                        n.contains("male") || n.contains("iol") || n.contains("iom") || n.contains("tpd") || n.contains("sfg")
                    }
                if (deepVoice != null) tts?.voice = deepVoice
                ttsReady = true
            }
        }
    }

    fun getAvailableVoices(): List<String> {
        if (!ttsReady) return emptyList()
        return tts?.voices
            ?.filter { !it.isNetworkConnectionRequired }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun applyVoiceSettings(voiceName: String, pitch: Float, rate: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 1.5f))
        tts?.setSpeechRate(rate.coerceIn(0.6f, 1.5f))
        if (voiceName.isNotBlank() && ttsReady) {
            tts?.voices?.firstOrNull { it.name == voiceName }?.let { tts?.voice = it }
        }
    }

    fun setWakeMode(enabled: Boolean) {
        wakeMode = enabled
        val svcIntent = Intent(context, WakeWordService::class.java)
        if (enabled) {
            context.startForegroundService(svcIntent)
            startListening()
        } else {
            context.stopService(svcIntent)
            stopListening()
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition unavailable")
            return
        }
        stopSpeaking()
        mainHandler.post {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { onListeningChanged(true) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { onListeningChanged(false) }
                    override fun onError(error: Int) {
                        onListeningChanged(false)
                        if (wakeMode) {
                            mainHandler.postDelayed({ if (wakeMode) startListening() }, 900L)
                        } else {
                            onError("Voice input error ($error)")
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        onListeningChanged(false)
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        if (!text.isNullOrBlank()) {
                            if (wakeMode) {
                                val lower = text.lowercase()
                                if (lower.contains("ultron")) {
                                    val cmd = text.replaceFirst(Regex("""(?i)^.*?\b(?:hey\s+)?ultron[,:\s]*"""), "").trim()
                                    if (cmd.isNotEmpty()) onResult(cmd) else speak("I am listening.")
                                }
                            } else {
                                onResult(text)
                            }
                        }
                        if (wakeMode) {
                            mainHandler.postDelayed({ if (wakeMode) startListening() }, 700L)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer?.startListening(intent)
        }
    }

    fun stopListening() {
        mainHandler.post {
            recognizer?.stopListening()
            onListeningChanged(false)
        }
    }

    fun speak(rawText: String) {
        if (!ttsReady) return
        val clean = rawText.replace(Regex("""\[TOOL:[^\]]+\]"""), "").trim()
        if (clean.isNotEmpty()) tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ultron_utt")
    }

    fun stopSpeaking() { tts?.stop() }

    fun release() {
        wakeMode = false
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }
}

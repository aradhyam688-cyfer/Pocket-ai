package com.pocketai.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread

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
    private var sttLangTag = "en-IN"
    private var metallicMode = true
    private var activeTrack: AudioTrack? = null
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
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "ultron_wav") playMetallicWav()
                    }
                    @Deprecated("Deprecated")
                    override fun onError(utteranceId: String?) {}
                })
                ttsReady = true
            }
        }
    }

    fun getAvailableVoices(): List<String> {
        if (!ttsReady) return emptyList()
        return tts?.voices?.filter { !it.isNetworkConnectionRequired }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun applyVoiceSettings(voiceName: String, pitch: Float, rate: Float, langTag: String, metallic: Boolean) {
        sttLangTag = langTag.ifBlank { "en-IN" }
        metallicMode = metallic
        tts?.setPitch(pitch.coerceIn(0.5f, 1.5f))
        tts?.setSpeechRate(rate.coerceIn(0.6f, 1.5f))
        if (voiceName.isNotBlank() && ttsReady) {
            tts?.voices?.firstOrNull { it.name == voiceName }?.let { tts?.voice = it }
        }
    }

    fun setWakeMode(enabled: Boolean) {
        wakeMode = enabled
        val svcIntent = Intent(context, WakeWordService::class.java)
        runCatching {
            if (enabled) { context.startForegroundService(svcIntent); startListening() }
            else { context.stopService(svcIntent); stopListening() }
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onError("Speech recognition unavailable"); return }
        stopSpeaking()
        mainHandler.post {
            runCatching {
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
                            if (wakeMode) mainHandler.postDelayed({ if (wakeMode) startListening() }, 900L)
                            else onError("Voice input error ($error)")
                        }
                        override fun onResults(results: Bundle?) {
                            onListeningChanged(false)
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                            if (!text.isNullOrBlank()) {
                                if (wakeMode) {
                                    if (text.lowercase().contains("ultron")) {
                                        val cmd = text.replaceFirst(Regex("""(?i)^.*?\b(?:hey\s+)?ultron[,:\s]*"""), "").trim()
                                        if (cmd.isNotEmpty()) onResult(cmd) else speak("I am listening.")
                                    }
                                } else onResult(text)
                            }
                            if (wakeMode) mainHandler.postDelayed({ if (wakeMode) startListening() }, 700L)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, sttLangTag)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                recognizer?.startListening(intent)
            }.onFailure { onError("Mic failed: ${it.message}") }
        }
    }

    fun stopListening() {
        mainHandler.post { runCatching { recognizer?.stopListening() }; onListeningChanged(false) }
    }

    fun speak(rawText: String) {
        if (!ttsReady) return
        val clean = rawText.replace(Regex("""\[TOOL:[^\]]+\]"""), "").trim()
        if (clean.isEmpty()) return
        stopSpeaking()
        if (metallicMode) {
            val wavFile = File(context.cacheDir, "ultron_tts.wav")
            tts?.synthesizeToFile(clean, null, wavFile, "ultron_wav")
        } else {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ultron_utt")
        }
    }

    private fun playMetallicWav() {
        thread(isDaemon = true) {
            runCatching {
                val file = File(context.cacheDir, "ultron_tts.wav")
                if (!file.exists()) return@runCatching
                val bytes = file.readBytes()
                if (bytes.size <= 44) return@runCatching
                val sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int.coerceIn(8000, 48000)
                val pcmCount = (bytes.size - 44) / 2
                val src = ShortArray(pcmCount)
                ByteBuffer.wrap(bytes, 44, pcmCount * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(src)

                val d1 = (sampleRate * 0.011f).toInt()
                val d2 = (sampleRate * 0.023f).toInt()
                val out = ShortArray(pcmCount)
                for (i in 0 until pcmCount) {
                    var v = src[i].toInt()
                    if (i >= d1) v += (src[i - d1] * 0.35f).toInt()
                    if (i >= d2) v -= (src[i - d2] * 0.22f).toInt()
                    out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(out.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                activeTrack = track
                track.write(out, 0, out.size)
                track.play()
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        runCatching { activeTrack?.stop(); activeTrack?.release(); activeTrack = null }
    }

    fun release() {
        wakeMode = false
        runCatching { recognizer?.destroy() }
        stopSpeaking()
        tts?.shutdown()
    }
}

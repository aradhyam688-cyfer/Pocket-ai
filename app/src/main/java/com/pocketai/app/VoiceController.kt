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
import kotlin.math.sin
import kotlin.math.tanh

class VoiceController(
    private val context: Context,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val prefs = context.getSharedPreferences("pocketai_voice", Context.MODE_PRIVATE)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeMode = false
    private var sttLangTag = prefs.getString("lang", "mix") ?: "mix"
    private var metallicMode = prefs.getBoolean("metallic", true)
    private var savedVoiceName = prefs.getString("voiceName", "") ?: ""
    private var savedPitch = prefs.getFloat("pitch", 0.76f)
    private var savedRate = prefs.getFloat("rate", 0.94f)
    private var activeTrack: AudioTrack? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.US
                applySavedOrDefaultVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "ultron_wav") playMetallicWav()
                    }
                    @Deprecated("Deprecated")
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    private fun applySavedOrDefaultVoice() {
        tts?.setPitch(savedPitch.coerceIn(0.5f, 1.5f))
        tts?.setSpeechRate(savedRate.coerceIn(0.6f, 1.5f))
        val voices = tts?.voices?.filter { !it.isNetworkConnectionRequired } ?: emptyList()
        val chosen = if (savedVoiceName.isNotBlank()) voices.firstOrNull { it.name == savedVoiceName } else null
        val maleFallback = voices.filter { it.locale.language == "en" }.firstOrNull {
            val n = it.name.lowercase()
            !n.contains("sfg") && !n.contains("tpf") && !n.contains("female") &&
                (n.contains("iom") || n.contains("iol") || n.contains("tpd") || n.contains("rjs") || n.contains("gba") || n.contains("male"))
        }
        (chosen ?: maleFallback)?.let { tts?.voice = it }
    }

    fun getAvailableVoices(): List<String> =
        if (!ttsReady) emptyList() else tts?.voices?.filter { !it.isNetworkConnectionRequired }?.map { it.name }?.sorted() ?: emptyList()

    fun applyVoiceSettings(voiceName: String, pitch: Float, rate: Float, langTag: String, metallic: Boolean) {
        savedVoiceName = voiceName; savedPitch = pitch.coerceIn(0.5f, 1.5f); savedRate = rate.coerceIn(0.6f, 1.5f)
        sttLangTag = langTag.ifBlank { "mix" }; metallicMode = metallic
        prefs.edit().putString("voiceName", savedVoiceName).putFloat("pitch", savedPitch)
            .putFloat("rate", savedRate).putString("lang", sttLangTag).putBoolean("metallic", metallicMode).apply()
        if (ttsReady) applySavedOrDefaultVoice()
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
                val lang = if (sttLangTag == "mix") "en-IN" else sttLangTag
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                    if (sttLangTag == "mix") putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "pa-IN", "en-US"))
                    else putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                recognizer?.startListening(intent)
            }.onFailure { onError("Mic failed: ${it.message}") }
        }
    }

    fun stopListening() { mainHandler.post { runCatching { recognizer?.stopListening() }; onListeningChanged(false) } }

    fun speak(rawText: String) {
        if (!ttsReady) return
        val clean = rawText.replace(Regex("""\[TOOL:[^\]]+\]"""), "").trim()
        if (clean.isEmpty()) return
        stopSpeaking()
        if (metallicMode) {
            val wavFile = File(context.cacheDir, "ultron_tts.wav")
            tts?.synthesizeToFile(clean, null, wavFile, "ultron_wav")
        } else tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ultron_utt")
    }

    private fun playMetallicWav() {
        thread(isDaemon = true) {
            runCatching {
                val file = File(context.cacheDir, "ultron_tts.wav")
                if (!file.exists()) return@runCatching
                val bytes = file.readBytes(); if (bytes.size <= 44) return@runCatching
                val sr = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int.coerceIn(8000, 48000)
                val n = (bytes.size - 44) / 2; val src = ShortArray(n)
                ByteBuffer.wrap(bytes, 44, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(src)
                val d1 = (sr * 0.0045f).toInt().coerceAtLeast(1); val d2 = (sr * 0.0073f).toInt().coerceAtLeast(2)
                val buf = FloatArray(n); val out = ShortArray(n); var prev = 0f
                for (i in 0 until n) {
                    var y = src[i] / 32768f
                    if (i >= d1) y += buf[i - d1] * 0.34f
                    if (i >= d2) y -= buf[i - d2] * 0.24f
                    buf[i] = y.coerceIn(-1.2f, 1.2f)
                    val ring = sin(2.0 * Math.PI * 85.0 * i / sr).toFloat()
                    val mod = y * (0.88f + 0.12f * ring)
                    val crisp = mod - 0.18f * prev; prev = mod
                    val sat = tanh((crisp * 1.25f).toDouble()).toFloat()
                    out[i] = (sat * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(out.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                activeTrack = track; track.write(out, 0, out.size); track.play()
            }
        }
    }

    fun stopSpeaking() { tts?.stop(); runCatching { activeTrack?.stop(); activeTrack?.release(); activeTrack = null } }
    fun release() { wakeMode = false; runCatching { recognizer?.destroy() }; stopSpeaking(); tts?.shutdown() }
}

package com.example.showgraphs.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.showgraphs.BuildConfig
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Streaming STT engine backed by Deepgram, exposed through the same [Listener]
 * surface the rest of Daisy was written against (it previously sat on Android's
 * on-device SpeechRecognizer). Drop-in replacement:
 * mic -> [AudioStreamer] -> [DeepgramSttClient] -> partial/final transcripts.
 *
 * Two behaviours the on-device recognizer gave us for free are reconstructed here:
 *  - voice level ([Listener.onRmsChanged]): computed from the PCM frames, since
 *    Deepgram sends no audio-level signal. Drives the orb's pulse.
 *  - "didn't catch that" ([Listener.onNoSpeech]): Deepgram streams continuously and
 *    never times out, so a silence timer fires it after a quiet stretch.
 *
 * Deepgram callbacks arrive on OkHttp background threads; every [Listener] call is
 * marshalled onto the main thread before it touches conversation/UI state.
 */
class DeepgramVoiceAssistant(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartialSpeech(text: String)
        fun onFinalSpeech(text: String)
        fun onRmsChanged(rms: Float)
        fun onNoSpeech()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var audioStreamer: AudioStreamer? = null
    private var sttClient: DeepgramSttClient? = null
    private var active = false

    private val silenceRunnable = Runnable {
        if (active) {
            listener.onNoSpeech()
            scheduleSilenceTimeout()
        }
    }

    fun start() {
        if (active) return
        val apiKey = BuildConfig.DEEPGRAM_API_KEY
        if (apiKey.isNullOrEmpty()) {
            Log.e(TAG, "DEEPGRAM_API_KEY is empty — set it in local.properties and rebuild")
            return
        }
        active = true

        sttClient = DeepgramSttClient(apiKey, object : SttCallback {
            override fun onOpen() = Unit

            override fun onTranscript(transcript: String, isFinal: Boolean) {
                main.post {
                    if (!active) return@post
                    resetSilenceTimeout()
                    if (isFinal) listener.onFinalSpeech(transcript) else listener.onPartialSpeech(transcript)
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "Deepgram error: $message")
            }

            override fun onClose() = Unit
        }).also { it.connect() }

        audioStreamer = AudioStreamer { data, length ->
            sttClient?.sendAudio(data, length)
            val rms = pcmToRms(data, length)
            main.post { if (active) listener.onRmsChanged(rms) }
        }.also { it.start() }

        scheduleSilenceTimeout()
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacks(silenceRunnable)
        audioStreamer?.stop()
        audioStreamer = null
        sttClient?.finish()
        sttClient?.close()
        sttClient = null
    }

    fun destroy() = stop()

    private fun resetSilenceTimeout() {
        main.removeCallbacks(silenceRunnable)
        scheduleSilenceTimeout()
    }

    private fun scheduleSilenceTimeout() {
        main.postDelayed(silenceRunnable, SILENCE_TIMEOUT_MS)
    }

    /**
     * Root-mean-square amplitude of a 16-bit little-endian PCM frame, mapped into
     * the ~[-2, 10] range the orb expects (DaisyOrbView does (rms + 2) / 12). The
     * gain is tuned so normal speech drives the orb near full; tweak [RMS_GAIN] if
     * it feels under- or over-reactive.
     */
    private fun pcmToRms(data: ByteArray, length: Int): Float {
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            sumSquares += (sample * sample).toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return -2f
        val rmsLinear = sqrt(sumSquares / samples) / 32768.0
        val normalized = min(1.0, rmsLinear * RMS_GAIN).toFloat()
        return normalized * 12f - 2f
    }

    companion object {
        private const val TAG = "DAISY_VOICE"
        private const val SILENCE_TIMEOUT_MS = 5_000L
        private const val RMS_GAIN = 6.0
    }
}

package com.example.showgraphs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.showgraphs.voice.stt.AudioStreamer
import com.example.showgraphs.voice.stt.DeepgramSttClient
import com.example.showgraphs.voice.stt.SttCallback
import kotlin.math.min

/**
 * Streams microphone audio to Deepgram's real-time STT and surfaces results
 * through [Listener].
 *
 * <p>This replaces the earlier Android-native [android.speech.SpeechRecognizer]
 * implementation. The [Listener] contract is unchanged so callers
 * ([DaisyService] / [ConversationEngine]) need no changes:
 * - interim Deepgram results -> [Listener.onPartialSpeech]
 * - final Deepgram results   -> [Listener.onFinalSpeech]
 * - mic energy (computed from PCM frames) -> [Listener.onRmsChanged]
 * - a stretch of silence with no transcript -> [Listener.onNoSpeech]
 *
 * <p>Deepgram callbacks arrive on OkHttp background threads; every [Listener]
 * call here is marshalled to the main thread to match the old recognizer's
 * delivery semantics (callers touch overlay views / TTS).
 */
class VoiceAssistant(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartialSpeech(text: String)
        fun onFinalSpeech(text: String)
        fun onRmsChanged(rms: Float)
        fun onNoSpeech()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Reassigned from the main thread on reconnect, read from the audio-capture
    // thread — must be volatile so the streamer always sees the live client.
    @Volatile
    private var sttClient: DeepgramSttClient? = null
    private var audioStreamer: AudioStreamer? = null
    private var active = false

    /** Incremented each time we (re)open a client; used to ignore stale callbacks. */
    @Volatile
    private var clientGeneration = 0

    /** Fires [Listener.onNoSpeech] if no transcript arrives within the window. */
    private val silenceTimeout = Runnable {
        if (active) {
            listener.onNoSpeech()
            scheduleSilenceTimeout()
        }
    }

    /** Re-opens the Deepgram stream after a drop, while we are still listening. */
    private val reconnectRunnable = Runnable {
        if (active) {
            Log.i(TAG, "Reconnecting Deepgram stream")
            openClient()
        }
    }

    fun start() {
        if (active) return

        if (BuildConfig.DEEPGRAM_API_KEY.isBlank()) {
            Log.e(TAG, "DEEPGRAM_API_KEY is empty — set it in local.properties and rebuild")
            return
        }

        active = true

        openClient()
        audioStreamer = AudioStreamer { data, length ->
            sttClient?.sendAudio(data, length)
            emitRms(data, length)
        }.apply { start() }

        scheduleSilenceTimeout()
    }

    fun stop() {
        if (!active) return
        active = false
        mainHandler.removeCallbacks(silenceTimeout)
        mainHandler.removeCallbacks(reconnectRunnable)
        audioStreamer?.stop()
        audioStreamer = null
        sttClient?.finish()
        sttClient?.close()
        sttClient = null
    }

    fun destroy() {
        stop()
    }

    /** Opens (or replaces) the Deepgram client. Audio capture keeps running. */
    private fun openClient() {
        // Bump the generation first so the outgoing client's close/error
        // callbacks are recognized as stale and don't trigger another reconnect.
        val generation = ++clientGeneration
        sttClient?.close()
        sttClient = DeepgramSttClient(BuildConfig.DEEPGRAM_API_KEY, makeCallback(generation))
            .apply { connect() }
    }

    /**
     * Deepgram resets streams that go too long without real speech, and any
     * network blip drops the socket. The old SpeechRecognizer auto-restarted;
     * mirror that by reconnecting (debounced) whenever the socket fails or
     * closes while we are still meant to be listening.
     */
    private fun scheduleReconnect() {
        if (!active) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun scheduleSilenceTimeout() {
        mainHandler.removeCallbacks(silenceTimeout)
        mainHandler.postDelayed(silenceTimeout, SILENCE_TIMEOUT_MS)
    }

    /**
     * Computes a coarse loudness from a 16-bit little-endian PCM frame and
     * reports it on a dB-like scale compatible with the old recognizer's
     * `onRmsChanged` (consumed by `DaisyOrbView.setVoiceLevel`).
     */
    private fun emitRms(data: ByteArray, length: Int) {
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            sumSquares += (sample * sample).toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return
        val rms = kotlin.math.sqrt(sumSquares / samples) // 0..32767
        // Map perceived loudness into roughly -2..10, the range the orb expects.
        val level = min(1f, (rms / 8000f).toFloat())
        mainHandler.post { listener.onRmsChanged(-2f + level * 12f) }
    }

    /**
     * Builds a callback bound to a client [generation]. Events from a superseded
     * client (an old socket we intentionally closed during reconnect) are
     * ignored, which prevents a close -> reconnect -> close feedback loop.
     */
    private fun makeCallback(generation: Int) = object : SttCallback {
        override fun onOpen() = Unit

        override fun onTranscript(transcript: String, isFinal: Boolean) {
            if (generation != clientGeneration) return
            scheduleSilenceTimeout()
            val text = transcript.trim()
            if (text.isEmpty()) return
            mainHandler.post {
                if (isFinal) listener.onFinalSpeech(text) else listener.onPartialSpeech(text)
            }
        }

        override fun onError(message: String) {
            Log.e(TAG, "Deepgram error: $message")
            mainHandler.post { if (generation == clientGeneration) scheduleReconnect() }
        }

        override fun onClose() {
            mainHandler.post { if (generation == clientGeneration) scheduleReconnect() }
        }
    }

    companion object {
        private const val TAG = "DAISY_VOICE"

        /** Silence window before reporting [Listener.onNoSpeech], mirroring the
         * old recognizer's complete-silence timeout. */
        private const val SILENCE_TIMEOUT_MS = 3200L

        /** Backoff before re-opening a dropped Deepgram stream. */
        private const val RECONNECT_DELAY_MS = 1500L
    }
}

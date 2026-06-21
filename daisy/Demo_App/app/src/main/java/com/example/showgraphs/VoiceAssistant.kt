package com.example.showgraphs

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
 * Audio capture is gated on [SttCallback.onOpen]: the [AudioStreamer] is
 * created in [start] but its [AudioStreamer.start] is called only once the
 * WebSocket handshake completes. This prevents the first ~300–500 ms of
 * speech from being lost to a not-yet-open socket.
 *
 * During reconnects only the Deepgram client is replaced; the already-running
 * [AudioStreamer] keeps capturing so there is no gap in PCM delivery.
 */
class VoiceAssistant(
    private val listener: Listener,
    private val apiKey: String = BuildConfig.DEEPGRAM_API_KEY,
    /** Factory used to create (and connect) a [DeepgramSttClient]. Injected for testing. */
    private val clientFactory: (String, SttCallback) -> DeepgramSttClient = { key, cb ->
        DeepgramSttClient(key, cb).apply { connect() }
    },
    /** Factory used to create an [AudioStreamer]. Injected for testing. */
    private val captureFactory: (AudioStreamer.AudioFrameListener) -> AudioStreamer = { fl ->
        AudioStreamer(fl)
    },
) {
    interface Listener {
        fun onPartialSpeech(text: String)
        fun onFinalSpeech(text: String)
        fun onRmsChanged(rms: Float)
        fun onNoSpeech()
        /** Called when [VoiceAssistant] cannot start (e.g. missing API key). Default is no-op. */
        fun onError(message: String) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Reassigned from the main thread on reconnect, read from the audio-capture
    // thread — must be volatile so the streamer always sees the live client.
    @Volatile
    private var sttClient: DeepgramSttClient? = null
    private var audioStreamer: AudioStreamer? = null
    private var active = false

    /** True once [AudioStreamer.start] has been called for the current session. */
    private var captureStarted = false

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

        if (apiKey.isBlank()) {
            Log.e(TAG, "DEEPGRAM_API_KEY is empty — set it in local.properties and rebuild")
            listener.onError("Voice recognition is not configured. Please set DEEPGRAM_API_KEY.")
            return
        }

        active = true
        captureStarted = false

        // Create the streamer now so the frame listener closure captures sttClient,
        // but DO NOT call start() yet — audio capture begins in onOpen() below.
        audioStreamer = captureFactory { data, length ->
            sttClient?.sendAudio(data, length)
            emitRms(data, length)
        }

        openClient()
        scheduleSilenceTimeout()
    }

    fun stop() {
        if (!active) return
        active = false
        captureStarted = false
        mainHandler.removeCallbacks(silenceTimeout)
        mainHandler.removeCallbacks(reconnectRunnable)
        audioStreamer?.stop()
        audioStreamer = null
        sttClient?.finish()
        sttClient?.close()
        sttClient = null
    }

    fun destroy() = stop()

    /** True if we are listening and the recorder is actively capturing. */
    fun isHealthy(): Boolean = active && audioStreamer?.isCapturing() == true

    /** Tears down and restarts capture + the STT stream (e.g. after a stall). */
    fun restart() {
        stop()   // clears `active` so the following start() isn't a no-op
        start()
    }

    /** Opens (or replaces) the Deepgram client. Audio capture keeps running across reconnects. */
    private fun openClient() {
        val generation = ++clientGeneration
        sttClient?.close()
        sttClient = clientFactory(apiKey, makeCallback(generation))
    }

    private fun scheduleReconnect() {
        if (!active) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun scheduleSilenceTimeout() {
        mainHandler.removeCallbacks(silenceTimeout)
        mainHandler.postDelayed(silenceTimeout, SILENCE_TIMEOUT_MS)
    }

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
        val level = min(1f, (rms / 8000f).toFloat())
        mainHandler.post { listener.onRmsChanged(-2f + level * 12f) }
    }

    private fun makeCallback(generation: Int) = object : SttCallback {

        override fun onOpen() {
            // Gate: start capture exactly once per session, on the first confirmed open.
            // Reconnects replace only the socket; the streamer keeps running.
            if (!captureStarted) {
                captureStarted = true
                audioStreamer?.start()
            }
        }

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
        private const val SILENCE_TIMEOUT_MS = 3200L
        private const val RECONNECT_DELAY_MS = 1500L
    }
}

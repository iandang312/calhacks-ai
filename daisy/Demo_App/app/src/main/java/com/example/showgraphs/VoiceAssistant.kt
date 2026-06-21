package com.example.showgraphs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.showgraphs.voice.AudioStreamer
import com.example.showgraphs.voice.DeepgramSttClient

/**
 * Always-on listening backed by Deepgram streaming STT.
 *
 * Keeps the same [Listener] contract the rest of Daisy was built against
 * (partial/final speech, voice level, no-speech) so the conversation engine
 * is unchanged — only the recognition backend swapped from the on-device
 * [android.speech.SpeechRecognizer] to Deepgram, which handles atypical
 * speech far better.
 */
class VoiceAssistant(
    context: Context,
    private val listener: Listener,
) : DeepgramSttClient.Callback {

    interface Listener {
        fun onPartialSpeech(text: String)
        fun onFinalSpeech(text: String)
        fun onRmsChanged(rms: Float)
        fun onNoSpeech()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sttClient: DeepgramSttClient? = null
    private var audioStreamer: AudioStreamer? = null

    private var active = false

    /** When muted we keep capturing (for the orb) but stop feeding Deepgram, so
     * Daisy never transcribes her own ElevenLabs voice. */
    @Volatile private var muted = false
    private var heardSinceReset = false

    private val silenceTimeout = object : Runnable {
        override fun run() {
            if (active && !muted) {
                listener.onNoSpeech()
                heardSinceReset = false
                armSilenceTimer()
            }
        }
    }

    fun start() {
        val apiKey = BuildConfig.DEEPGRAM_API_KEY
        if (apiKey.isBlank()) {
            Log.e(TAG, "DEEPGRAM_API_KEY is empty — set it in local.properties and rebuild")
            return
        }
        if (active) return
        active = true

        sttClient = DeepgramSttClient(apiKey, this).also { it.connect() }
        audioStreamer = AudioStreamer { data, length ->
            listener.onRmsChanged(AudioStreamer.rmsLevel(data, length))
            if (!muted) sttClient?.sendAudio(data, length)
        }.also { it.start() }

        armSilenceTimer()
    }

    /** Stop feeding audio to Deepgram (e.g. while Daisy is speaking). */
    fun pauseInput() {
        muted = true
        cancelSilenceTimer()
        sttClient?.keepAlive()
    }

    /** Resume feeding audio to Deepgram after Daisy finishes speaking. */
    fun resumeInput() {
        muted = false
        heardSinceReset = false
        armSilenceTimer()
    }

    fun stop() {
        active = false
        cancelSilenceTimer()
        audioStreamer?.stop()
        audioStreamer = null
    }

    fun destroy() {
        stop()
        sttClient?.finish()
        sttClient?.close()
        sttClient = null
    }

    private fun armSilenceTimer() {
        cancelSilenceTimer()
        mainHandler.postDelayed(silenceTimeout, SILENCE_MS)
    }

    private fun cancelSilenceTimer() = mainHandler.removeCallbacks(silenceTimeout)

    // --- DeepgramSttClient.Callback (invoked on OkHttp background threads) ---

    override fun onOpen() = Unit

    override fun onTranscript(transcript: String, isFinal: Boolean) {
        mainHandler.post {
            heardSinceReset = true
            armSilenceTimer()
            if (isFinal) listener.onFinalSpeech(transcript) else listener.onPartialSpeech(transcript)
        }
    }

    override fun onUtteranceEnd() {
        // A pause was detected. If nothing was transcribed in this window, treat
        // it like the old "no speech" timeout so Daisy can re-prompt.
        mainHandler.post {
            if (active && !muted && !heardSinceReset) listener.onNoSpeech()
            heardSinceReset = false
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, "STT error: $message")
    }

    override fun onClose() = Unit

    companion object {
        private const val TAG = "DAISY_VOICE"
        private const val SILENCE_MS = 8000L
    }
}

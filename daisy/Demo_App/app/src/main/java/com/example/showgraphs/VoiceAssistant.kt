package com.example.showgraphs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceAssistant(
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
    private var speechRecognizer: SpeechRecognizer? = null
    private var active = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.w(TAG, "Speech recognition unavailable")
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        if (active) return
        active = true
        speechRecognizer?.startListening(recognitionIntent())
    }

    fun stop() {
        active = false
        speechRecognizer?.cancel()
    }

    fun destroy() {
        stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2600L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3200L)
    }

    private fun restart() {
        if (!active) return
        speechRecognizer?.startListening(recognitionIntent())
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = listener.onRmsChanged(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> listener.onNoSpeech()
            }
            if (active) restart()
        }

        override fun onResults(results: Bundle?) {
            val text = results?.bestText().orEmpty()
            if (text.isNotBlank()) listener.onFinalSpeech(text)
            if (active) restart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.bestText().orEmpty()
            if (text.isNotBlank()) listener.onPartialSpeech(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun Bundle.bestText(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        private const val TAG = "DAISY_VOICE"
    }
}

package com.example.showgraphs.voice

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Thin wrapper around Deepgram's real-time streaming STT endpoint.
 *
 * Lifecycle: [connect] once, feed raw PCM frames with [sendAudio] as they are
 * captured, then [finish] to flush the final transcript and [close] to tear down.
 *
 * Audio must be 16-bit signed PCM ("linear16"), 16 kHz, mono — exactly what
 * [AudioStreamer] produces.
 */
class DeepgramSttClient(
    private val apiKey: String,
    private val callback: Callback,
) {
    interface Callback {
        fun onOpen()
        fun onTranscript(transcript: String, isFinal: Boolean)

        /** Deepgram detected the end of an utterance (a pause in speech). */
        fun onUtteranceEnd()
        fun onError(message: String)
        fun onClose()
    }

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun connect() {
        Log.d(TAG, "Connecting to Deepgram (key length=${apiKey.length})")
        val request = Request.Builder()
            .url(DG_URL)
            .addHeader("Authorization", "Token $apiKey")
            .build()
        webSocket = client.newWebSocket(request, Listener())
    }

    /** Sends a chunk of raw PCM audio: [length] bytes from [data]. */
    fun sendAudio(data: ByteArray, length: Int) {
        webSocket?.send(data.toByteString(0, length))
    }

    /** Keeps the connection alive during pauses (e.g. while Daisy is speaking). */
    fun keepAlive() {
        webSocket?.send("{\"type\":\"KeepAlive\"}")
    }

    /** Tells Deepgram no more audio is coming so it flushes the final result. */
    fun finish() {
        webSocket?.send("{\"type\":\"CloseStream\"}")
    }

    fun close() {
        webSocket?.close(1000, "client closed")
        webSocket = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened (HTTP ${response.code})")
            callback.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "UtteranceEnd" -> {
                        callback.onUtteranceEnd()
                        return
                    }
                    "Metadata", "SpeechStarted" -> return
                }
                // Only "Results" messages carry a channel.
                if (!json.has("channel")) return
                val alternatives = json.getJSONObject("channel").getJSONArray("alternatives")
                if (alternatives.length() == 0) return
                val transcript = alternatives.getJSONObject(0).optString("transcript", "")
                val isFinal = json.optBoolean("is_final", false)
                if (transcript.isNotEmpty()) {
                    Log.d(TAG, (if (isFinal) "FINAL: " else "interim: ") + transcript)
                    callback.onTranscript(transcript, isFinal)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message: $text", e)
                callback.onError("Failed to parse Deepgram message: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure (HTTP ${response?.code ?: "none"})", t)
            callback.onError(t.message ?: "WebSocket failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing (code=$code, reason=$reason)")
            webSocket.close(1000, null)
            callback.onClose()
        }
    }

    companion object {
        private const val TAG = "DeepgramStt"

        // interim_results + utterance_end_ms let us mimic the old SpeechRecognizer
        // partial/final/no-speech callbacks on top of a continuous stream.
        private const val DG_URL =
            "wss://api.deepgram.com/v1/listen" +
                "?model=nova-2" +
                "&encoding=linear16" +
                "&sample_rate=16000" +
                "&channels=1" +
                "&interim_results=true" +
                "&smart_format=true" +
                "&punctuate=true" +
                "&utterance_end_ms=1500" +
                "&vad_events=true"
    }
}

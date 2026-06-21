package com.example.showgraphs.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Text-to-speech via Deepgram's Aura voices.
 *
 * Synthesizes [text] over HTTP (MP3), plays it through [MediaPlayer], and fires
 * [onDone] when playback completes. On any failure it calls [onError] so the
 * caller can fall back to on-device TTS — Daisy should never go silent.
 */
class DeepgramTts(
    context: Context,
    private val apiKey: String,
    private val model: String,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null
    private var pendingCall: Call? = null

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** Speak [text]. [onDone] runs on the main thread once audio finishes. */
    fun speak(text: String, onDone: (() -> Unit)?, onError: (() -> Unit)?) {
        stop()

        val url = "https://api.deepgram.com/v1/speak?model=$model"
        val body = JSONObject().put("text", text).toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        pendingCall = client.newCall(request).also {
            it.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "TTS request failed", e)
                    fail(onError, onDone)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            Log.e(TAG, "TTS HTTP ${response.code}: ${response.body?.string()}")
                            fail(onError, onDone)
                            return
                        }
                        val bytes = response.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            fail(onError, onDone)
                            return
                        }
                        try {
                            val file = File(appContext.cacheDir, "daisy_tts.mp3")
                            file.writeBytes(bytes)
                            mainHandler.post { play(file, onDone, onError) }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to cache TTS audio", e)
                            fail(onError, onDone)
                        }
                    }
                }
            })
        }
    }

    private fun play(file: File, onDone: (() -> Unit)?, onError: (() -> Unit)?) {
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    releasePlayer()
                    onDone?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    releasePlayer()
                    (onError ?: onDone)?.invoke()
                    true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            releasePlayer()
            (onError ?: onDone)?.invoke()
        }
    }

    private fun fail(onError: (() -> Unit)?, onDone: (() -> Unit)?) {
        mainHandler.post { (onError ?: onDone)?.invoke() }
    }

    fun stop() {
        pendingCall?.cancel()
        pendingCall = null
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
    }

    fun shutdown() = stop()

    companion object {
        private const val TAG = "DaisyDeepgramTts"
    }
}

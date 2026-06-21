package com.example.showgraphs.voice

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
 * Lifelike text-to-speech via ElevenLabs.
 *
 * Synthesizes [text] over HTTP (MP3), plays it through [MediaPlayer], and fires
 * [onDone] when playback completes. On any failure it calls [onError] so the
 * caller can fall back to on-device TTS — Daisy should never go silent.
 */
class ElevenLabsTts(
    context: Context,
    private val apiKey: String,
    private val voiceId: String,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null
    private var pendingCall: Call? = null

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * Speak [text]. [onDone] runs on the main thread once audio finishes (or
     * immediately on failure after [onError] has run).
     */
    fun speak(text: String, onDone: (() -> Unit)?, onError: (() -> Unit)?) {
        stop()

        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId" +
            "?output_format=mp3_44100_128"
        val body = JSONObject()
            .put("text", text)
            .put("model_id", "eleven_turbo_v2_5")
            .put(
                "voice_settings",
                JSONObject()
                    .put("stability", 0.5)
                    .put("similarity_boost", 0.8)
                    .put("style", 0.0)
                    .put("use_speaker_boost", true),
            )
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("accept", "audio/mpeg")
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
                    onError?.invoke() ?: onDone?.invoke()
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
        private const val TAG = "DaisyElevenLabs"
    }
}

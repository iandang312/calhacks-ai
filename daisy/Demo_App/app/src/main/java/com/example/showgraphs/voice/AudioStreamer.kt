package com.example.showgraphs.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures microphone audio as 16 kHz / 16-bit / mono PCM and hands each frame
 * to a listener. This format matches what [DeepgramSttClient] expects.
 *
 * The caller must hold the RECORD_AUDIO permission before calling [start].
 */
class AudioStreamer(
    private val listener: (data: ByteArray, length: Int) -> Unit,
) {
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // permission is enforced by the caller
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // ~100ms of audio per read keeps latency low without spamming tiny frames.
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5)

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        ).also { it.startRecording() }
        running = true

        captureThread = Thread({
            val buffer = ByteArray(bufferSize)
            while (running) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    listener(buffer, read)
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error code: $read")
                }
            }
        }, "audio-capture").also { it.start() }
    }

    fun stop() {
        running = false
        recorder?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // already stopped
            }
            it.release()
        }
        recorder = null
        captureThread = null
    }

    companion object {
        private const val TAG = "AudioStreamer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Perceptual-ish voice level in roughly the 0..10 range the orb expects. */
        fun rmsLevel(buffer: ByteArray, length: Int): Float {
            var sumSquares = 0.0
            var samples = 0
            var i = 0
            while (i + 1 < length) {
                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                sumSquares += (sample * sample).toDouble()
                samples++
                i += 2
            }
            if (samples == 0) return 0f
            val rms = Math.sqrt(sumSquares / samples) // 0..32767
            val normalized = (rms / 32767.0).coerceIn(0.0, 1.0)
            return (Math.sqrt(normalized) * 10.0).toFloat()
        }
    }
}

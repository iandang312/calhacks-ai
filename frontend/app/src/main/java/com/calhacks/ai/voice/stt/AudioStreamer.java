package com.calhacks.ai.voice.stt;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Captures microphone audio as 16 kHz / 16-bit / mono PCM and hands each frame
 * to a listener. This format matches what {@link DeepgramSttClient} expects.
 *
 * <p>The caller is responsible for holding the RECORD_AUDIO permission before
 * calling {@link #start()}.
 */
public class AudioStreamer {

    private static final String TAG = "AudioStreamer";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /** Receives raw PCM frames on a background thread. */
    public interface AudioFrameListener {
        void onFrame(byte[] data, int length);
    }

    private final AudioFrameListener listener;

    private AudioRecord recorder;
    private Thread captureThread;
    private volatile boolean running;

    public AudioStreamer(AudioFrameListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission") // permission is enforced by the caller
    public void start() {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        // ~100ms of audio per read keeps latency low without spamming tiny frames.
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 5);

        recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize);
        recorder.startRecording();
        running = true;
        Log.d(TAG, "Recording started (minBuffer=" + minBuffer
                + ", bufferSize=" + bufferSize
                + ", state=" + recorder.getRecordingState() + ")");

        captureThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            long frames = 0;
            while (running) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // Log roughly once a second (~5 frames) so we can confirm audio is
                    // flowing, and flag all-zero frames — the tell-tale sign the emulator
                    // mic is not wired to the host input.
                    if (frames % 5 == 0) {
                        Log.v(TAG, "frame #" + frames + " read=" + read + " bytes"
                                + (isSilent(buffer, read) ? " (SILENT — check host mic)" : ""));
                    }
                    frames++;
                    listener.onFrame(buffer, read);
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error code: " + read);
                }
            }
            Log.d(TAG, "Capture thread exiting after " + frames + " frames");
        }, "audio-capture");
        captureThread.start();
    }

    /** True if every sample in the frame is zero (i.e. pure silence). */
    private static boolean isSilent(byte[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            if (buffer[i] != 0) {
                return false;
            }
        }
        return true;
    }

    public void stop() {
        Log.d(TAG, "Stopping recording");
        running = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            }
            recorder.release();
            recorder = null;
        }
        captureThread = null;
    }
}

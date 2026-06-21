package com.example.showgraphs.voice.stt;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

/**
 * Captures microphone audio as 16 kHz / 16-bit / mono PCM and hands each frame
 * to a listener. This format matches what {@link DeepgramSttClient} expects.
 *
 * <p>The caller is responsible for holding the RECORD_AUDIO permission before
 * calling {@link #start()}.
 *
 * <p><b>Self-healing capture.</b> On Android 10+, when a higher-priority app
 * (e.g. the system Assistant) captures the mic, the platform does not error —
 * it silently feeds this (lower-priority/background) recorder zero-filled
 * buffers, and does not reliably un-silence it afterwards. Re-calling
 * {@code startRecording()} does not help; the {@link AudioRecord} must be torn
 * down and rebuilt. So the capture loop watches for a sustained run of all-zero
 * frames and re-initializes the recorder to recover. See
 * <a href="https://source.android.com/docs/core/audio/concurrent">concurrent capture</a>.
 */
public class AudioStreamer {

    private static final String TAG = "AudioStreamer";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // ~5 frames/sec. After this many consecutive all-zero frames we assume the
    // recorder has been silenced (by capture policy or a dropped mic) and rebuild it.
    private static final int SILENCE_FRAMES_BEFORE_REINIT = 15;
    // Wait before rebuilding so we don't thrash while the other app holds the mic.
    private static final long REINIT_BACKOFF_MS = 1500L;

    /** Receives raw PCM frames on a background thread. */
    public interface AudioFrameListener {
        void onFrame(byte[] data, int length);
    }

    private final AudioFrameListener listener;

    private AudioRecord recorder;
    private Thread captureThread;
    private volatile boolean running;
    private int bufferSize;

    public AudioStreamer(AudioFrameListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission") // permission is enforced by the caller
    private AudioRecord createRecorder() {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        // ~100ms of audio per read keeps latency low without spamming tiny frames.
        bufferSize = Math.max(minBuffer, SAMPLE_RATE / 5);
        return new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize);
    }

    public void start() {
        recorder = createRecorder();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=" + recorder.getState() + ")");
            recorder.release();
            recorder = null;
            return;
        }
        recorder.startRecording();
        running = true;
        Log.d(TAG, "Recording started (bufferSize=" + bufferSize
                + ", state=" + recorder.getRecordingState() + ")");

        captureThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            long frames = 0;
            int silentRun = 0;
            while (running) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    boolean silent = isSilent(buffer, read);
                    if (silent) {
                        silentRun++;
                        if (silentRun >= SILENCE_FRAMES_BEFORE_REINIT) {
                            Log.w(TAG, "Mic silent for " + silentRun + " frames"
                                    + (clientSilenced() ? " (silenced by another app)" : "")
                                    + " — reinitializing recorder");
                            if (!reinitRecorder()) break;
                            silentRun = 0;
                            continue;
                        }
                    } else {
                        silentRun = 0;
                    }
                    // Log roughly once a second (~5 frames) so we can confirm audio is flowing.
                    if (frames % 5 == 0) {
                        Log.v(TAG, "frame #" + frames + " read=" + read + " bytes"
                                + (silent ? " (SILENT)" : ""));
                    }
                    frames++;
                    listener.onFrame(buffer, read);
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error code: " + read + " — reinitializing");
                    if (!reinitRecorder()) break;
                    silentRun = 0;
                }
            }
            Log.d(TAG, "Capture thread exiting after " + frames + " frames");
        }, "audio-capture");
        captureThread.start();
    }

    /** True while the recorder exists and the platform reports it as recording. */
    public boolean isCapturing() {
        return running && recorder != null
                && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
    }

    /** Whether the framework is feeding us zeros because another app holds the mic. */
    private boolean clientSilenced() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || recorder == null) return false;
        AudioRecordingConfiguration cfg = recorder.getActiveRecordingConfiguration();
        return cfg != null && cfg.isClientSilenced();
    }

    /**
     * Releases the (silenced/errored) recorder and builds a fresh one on the
     * capture thread. Returns false if we should stop (no longer running or
     * re-init failed), true if capture resumed.
     */
    @SuppressLint("MissingPermission")
    private boolean reinitRecorder() {
        try {
            recorder.stop();
        } catch (IllegalStateException ignored) {
            // already stopped
        }
        recorder.release();
        recorder = null;
        try {
            Thread.sleep(REINIT_BACKOFF_MS); // let the higher-priority app release the mic
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!running) return false;
        recorder = createRecorder();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Recorder re-init failed (state=" + recorder.getState() + ")");
            recorder.release();
            recorder = null;
            return false;
        }
        recorder.startRecording();
        Log.d(TAG, "AudioRecord reinitialized, state=" + recorder.getRecordingState());
        return true;
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
        if (captureThread != null) {
            captureThread.interrupt(); // break out of the re-init backoff promptly
        }
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

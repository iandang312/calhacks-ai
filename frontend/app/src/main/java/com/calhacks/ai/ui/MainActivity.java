package com.calhacks.ai.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.calhacks.ai.BuildConfig;
import com.calhacks.ai.R;
import com.calhacks.ai.voice.stt.AudioStreamer;
import com.calhacks.ai.voice.stt.DeepgramSttClient;
import com.calhacks.ai.voice.stt.SttCallback;

/**
 * Minimal demo that wires the STT layer end-to-end:
 * mic -> {@link AudioStreamer} -> {@link DeepgramSttClient} -> live transcript.
 *
 * <p>Treat this as a reference for how to drive the voice.stt package, not as final UI.
 */
public class MainActivity extends AppCompatActivity implements SttCallback {

    private static final String TAG = "MainActivity";

    private TextView transcriptView;
    private Button toggleButton;

    private DeepgramSttClient sttClient;
    private AudioStreamer audioStreamer;
    private boolean recording = false;

    private final StringBuilder finalizedText = new StringBuilder();

    private final ActivityResultLauncher<String> micPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startStreaming();
                } else {
                    toast("Microphone permission is required for transcription");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        transcriptView = findViewById(R.id.transcript);
        toggleButton = findViewById(R.id.toggle);

        toggleButton.setOnClickListener(v -> {
            if (recording) {
                stopStreaming();
            } else {
                ensurePermissionThenStart();
            }
        });
    }

    private void ensurePermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startStreaming();
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startStreaming() {
        String apiKey = BuildConfig.DEEPGRAM_API_KEY;
        if (apiKey == null || apiKey.isEmpty()) {
            Log.e(TAG, "DEEPGRAM_API_KEY is empty — set it in local.properties and rebuild");
            toast("Set DEEPGRAM_API_KEY in local.properties, then rebuild");
            return;
        }

        Log.i(TAG, "Starting STT session");

        finalizedText.setLength(0);
        transcriptView.setText("");

        sttClient = new DeepgramSttClient(apiKey, this);
        sttClient.connect();

        audioStreamer = new AudioStreamer((data, length) -> sttClient.sendAudio(data, length));
        audioStreamer.start();

        recording = true;
        toggleButton.setText(R.string.stop);
    }

    private void stopStreaming() {
        Log.i(TAG, "Stopping STT session");
        recording = false;
        if (audioStreamer != null) {
            audioStreamer.stop();
            audioStreamer = null;
        }
        if (sttClient != null) {
            sttClient.finish();
            sttClient.close();
            sttClient = null;
        }
        toggleButton.setText(R.string.start);
    }

    // --- SttCallback (invoked on background threads) ---

    @Override
    public void onOpen() {
        // no-op; could show a "listening" indicator
    }

    @Override
    public void onTranscript(String transcript, boolean isFinal) {
        runOnUiThread(() -> {
            if (isFinal) {
                finalizedText.append(transcript).append(' ');
                transcriptView.setText(finalizedText.toString());
            } else {
                // Show interim words in brackets after the finalized text.
                transcriptView.setText(finalizedText + "[" + transcript + "]");
            }
        });
    }

    @Override
    public void onError(String message) {
        Log.e(TAG, "STT error: " + message);
        runOnUiThread(() -> toast(message));
    }

    @Override
    public void onClose() {
        // no-op
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
    }
}

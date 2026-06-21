package com.example.showgraphs.voice.stt;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Thin wrapper around Deepgram's real-time streaming STT endpoint.
 *
 * <p>Lifecycle: {@link #connect()} once, feed raw PCM frames with
 * {@link #sendAudio(byte[], int)} as they are captured, then {@link #finish()}
 * to flush the final transcript and {@link #close()} to tear down.
 *
 * <p>Audio must match the query params below: 16-bit signed PCM ("linear16"),
 * 16 kHz, mono — which is exactly what {@link AudioStreamer} produces.
 */
public class DeepgramSttClient {

    private static final String TAG = "DeepgramStt";

    private static final String DG_URL =
            "wss://api.deepgram.com/v1/listen"
                    + "?model=nova-2"
                    + "&encoding=linear16"
                    + "&sample_rate=16000"
                    + "&channels=1"
                    + "&interim_results=true"
                    + "&smart_format=true"
                    + "&punctuate=true";

    private final String apiKey;
    private final SttCallback callback;
    private final OkHttpClient client = new OkHttpClient();

    private WebSocket webSocket;

    public DeepgramSttClient(String apiKey, SttCallback callback) {
        this.apiKey = apiKey;
        this.callback = callback;
    }

    /** Opens the streaming connection. Safe to call once per session. */
    public void connect() {
        Log.d(TAG, "Connecting to Deepgram (key length=" + apiKey.length() + ")");
        Request request = new Request.Builder()
                .url(DG_URL)
                .addHeader("Authorization", "Token " + apiKey)
                .build();
        webSocket = client.newWebSocket(request, new Listener());
    }

    /** Sends a chunk of raw PCM audio. {@code length} bytes from {@code data}. */
    public void sendAudio(byte[] data, int length) {
        if (webSocket != null) {
            webSocket.send(ByteString.of(data, 0, length));
        }
    }

    /**
     * Tells Deepgram no more audio is coming so it flushes the final result.
     * Call this before {@link #close()}.
     */
    public void finish() {
        if (webSocket != null) {
            Log.d(TAG, "Sending CloseStream to flush final transcript");
            webSocket.send("{\"type\":\"CloseStream\"}");
        }
    }

    /** Closes the WebSocket. */
    public void close() {
        if (webSocket != null) {
            Log.d(TAG, "Closing WebSocket");
            webSocket.close(1000, "client closed");
            webSocket = null;
        }
    }

    private class Listener extends WebSocketListener {

        @Override
        public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
            Log.i(TAG, "WebSocket opened (HTTP " + response.code() + ")");
            callback.onOpen();
        }

        @Override
        public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
            Log.v(TAG, "Message: " + text);
            try {
                JSONObject json = new JSONObject(text);
                // Skip metadata / keep-alive messages — only "Results" carry a channel.
                if (!json.has("channel")) {
                    return;
                }
                JSONArray alternatives = json.getJSONObject("channel")
                        .getJSONArray("alternatives");
                if (alternatives.length() == 0) {
                    return;
                }
                String transcript = alternatives.getJSONObject(0)
                        .optString("transcript", "");
                boolean isFinal = json.optBoolean("is_final", false);
                if (!transcript.isEmpty()) {
                    Log.d(TAG, (isFinal ? "FINAL: " : "interim: ") + transcript);
                    callback.onTranscript(transcript, isFinal);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse message: " + text, e);
                callback.onError("Failed to parse Deepgram message: " + e.getMessage());
            }
        }

        @Override
        public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
            // A non-101 handshake (e.g. 401 bad key, 403 forbidden) surfaces here with a response.
            Log.e(TAG, "WebSocket failure (HTTP "
                    + (response != null ? response.code() : "none") + ")", t);
            callback.onError(t.getMessage() != null ? t.getMessage() : "WebSocket failure");
        }

        @Override
        public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
            Log.i(TAG, "WebSocket closing (code=" + code + ", reason=" + reason + ")");
            ws.close(1000, null);
            callback.onClose();
        }
    }
}

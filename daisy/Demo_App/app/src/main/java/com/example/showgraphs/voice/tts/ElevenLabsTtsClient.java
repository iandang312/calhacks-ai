package com.example.showgraphs.voice.tts;

import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches speech audio from ElevenLabs and plays it via an injectable
 * {@link AudioOutput}. Calls are asynchronous; results arrive on OkHttp's
 * background thread.
 *
 * <p>Use the two-arg public constructor in production. The four-arg constructor
 * (package-accessible) accepts a custom base URL so tests can point at a
 * {@code MockWebServer}.
 */
public class ElevenLabsTtsClient {

    private static final String TAG = "ElevenLabsTts";
    private static final String PRODUCTION_BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech/";

    /** Receives the raw audio bytes fetched from ElevenLabs and plays them. */
    public interface AudioOutput {
        /**
         * @param audioBytes MP3 bytes from ElevenLabs
         * @param onDone     call when playback finishes (or immediately on error)
         */
        void play(byte[] audioBytes, Runnable onDone);
    }

    private final String apiKey;
    private final String voiceId;
    private final AudioOutput audioOutput;
    private final String baseUrl;
    private final OkHttpClient client;

    public ElevenLabsTtsClient(String apiKey, String voiceId, AudioOutput audioOutput) {
        this(apiKey, voiceId, audioOutput, PRODUCTION_BASE_URL);
    }

    /** Package-visible for testing: lets tests inject a MockWebServer base URL. */
    ElevenLabsTtsClient(String apiKey, String voiceId, AudioOutput audioOutput, String baseUrl) {
        this.apiKey = apiKey;
        this.voiceId = voiceId;
        this.audioOutput = audioOutput;
        this.baseUrl = baseUrl;
        this.client = new OkHttpClient.Builder()
                .callTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public void speak(String text, TtsCallback callback) {
        String json = buildRequestJson(text);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        Request request = new Request.Builder()
                .url(baseUrl + voiceId + "/stream")
                .addHeader("xi-api-key", apiKey)
                .post(RequestBody.create(jsonBytes, MediaType.get("application/json")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "ElevenLabs request failed", e);
                callback.onError(e.getMessage() != null ? e.getMessage() : "network error");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "ElevenLabs error: HTTP " + response.code());
                        callback.onError("HTTP " + response.code());
                        return;
                    }
                    byte[] audioBytes = (body != null) ? body.bytes() : new byte[0];
                    audioOutput.play(audioBytes, callback::onDone);
                }
            }
        });
    }

    private static String buildRequestJson(String text) {
        String escaped = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "{\"text\":\"" + escaped + "\","
                + "\"model_id\":\"eleven_monolingual_v1\","
                + "\"voice_settings\":{\"stability\":0.5,\"similarity_boost\":0.75}}";
    }
}

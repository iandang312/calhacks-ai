package com.example.showgraphs.voice.tts;

/** Completion/error callbacks for {@link ElevenLabsTtsClient}. */
public interface TtsCallback {
    void onDone();
    void onError(String message);
}

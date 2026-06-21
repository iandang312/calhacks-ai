package com.example.showgraphs.voice;

/**
 * Callbacks from {@link DeepgramSttClient}. All methods are invoked on
 * OkHttp's background threads — marshal to the UI thread before touching views.
 */
public interface SttCallback {

    /** WebSocket connection to Deepgram is open and ready for audio. */
    void onOpen();

    /**
     * A transcript fragment arrived.
     *
     * @param transcript the recognized text
     * @param isFinal    true if Deepgram considers this segment finalized;
     *                   false for interim (still-changing) results
     */
    void onTranscript(String transcript, boolean isFinal);

    /** A connection or protocol error occurred. */
    void onError(String message);

    /** The connection closed. */
    void onClose();
}

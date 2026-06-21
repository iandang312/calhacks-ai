package com.example.showgraphs

import com.example.showgraphs.voice.stt.AudioStreamer
import com.example.showgraphs.voice.stt.DeepgramSttClient
import com.example.showgraphs.voice.stt.SttCallback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceAssistantTest {

    private var capturedCallback: SttCallback? = null
    private var fakeStreamer: FakeAudioStreamer? = null

    @Before
    fun setUp() {
        capturedCallback = null
        fakeStreamer = null
    }

    private fun makeAssistant(
        apiKey: String = "test_key",
        listener: VoiceAssistant.Listener = NoOpListener(),
    ): VoiceAssistant = VoiceAssistant(
        listener = listener,
        apiKey = apiKey,
        clientFactory = { _, cb ->
            capturedCallback = cb
            FakeDeepgramClient(cb)
        },
        captureFactory = { fl ->
            FakeAudioStreamer(fl).also { fakeStreamer = it }
        },
    )

    // ── Cycle 1: audio gate ────────────────────────────────────────────────

    @Test
    fun `audio capture does not start before onOpen fires`() {
        val va = makeAssistant()
        va.start()

        assertFalse("streamer must not start before onOpen", fakeStreamer!!.started)

        capturedCallback!!.onOpen()

        assertTrue("streamer must start once onOpen fires", fakeStreamer!!.started)
    }

    @Test
    fun `reconnect onOpen does not restart already-running capture`() {
        val va = makeAssistant()
        va.start()
        capturedCallback!!.onOpen()           // first open — starts capture
        fakeStreamer!!.started = false         // reset flag to detect a second start()
        capturedCallback!!.onOpen()           // second onOpen (reconnect scenario)

        assertFalse("capture must not be restarted on reconnect onOpen", fakeStreamer!!.started)
    }

    // ── Cycle 2: blank API key ─────────────────────────────────────────────

    @Test
    fun `blank api key calls onError and skips streamer creation`() {
        val errors = mutableListOf<String>()
        val listener = object : VoiceAssistant.Listener {
            override fun onPartialSpeech(text: String) = Unit
            override fun onFinalSpeech(text: String) = Unit
            override fun onRmsChanged(rms: Float) = Unit
            override fun onNoSpeech() = Unit
            override fun onError(message: String) { errors.add(message) }
        }

        makeAssistant(apiKey = "", listener = listener).start()

        assertFalse("onError must be called with a non-empty message", errors.isEmpty())
        assertNull("captureFactory must never be called when key is blank", fakeStreamer)
    }

    // ── Fakes ──────────────────────────────────────────────────────────────

    private class NoOpListener : VoiceAssistant.Listener {
        override fun onPartialSpeech(text: String) = Unit
        override fun onFinalSpeech(text: String) = Unit
        override fun onRmsChanged(rms: Float) = Unit
        override fun onNoSpeech() = Unit
    }

    /** Subclasses AudioStreamer but never touches AudioRecord hardware. */
    private class FakeAudioStreamer(
        listener: AudioStreamer.AudioFrameListener,
    ) : AudioStreamer(listener) {
        var started = false
        override fun start() { started = true }
        override fun stop() {}
        override fun isCapturing() = started
    }

    /** Subclasses DeepgramSttClient but never opens a network connection. */
    private class FakeDeepgramClient(
        callback: SttCallback,
    ) : DeepgramSttClient("test_key", callback) {
        override fun connect() {}
        override fun sendAudio(data: ByteArray, length: Int) {}
        override fun finish() {}
        override fun close() {}
    }
}

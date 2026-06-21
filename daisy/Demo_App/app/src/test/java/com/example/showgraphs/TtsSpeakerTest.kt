package com.example.showgraphs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSpeakerTest {

    // ── Cycle 3: queue before ready, drain on onReady ─────────────────────

    @Test
    fun `speak before onReady queues text and does not invoke engine`() {
        val spoken = mutableListOf<String>()
        val speaker = TtsSpeaker()
        speaker.setEngine { text, _ -> spoken.add(text) }

        speaker.speak("Hello")

        assertTrue("engine must not be called before onReady", spoken.isEmpty())
    }

    @Test
    fun `onReady drains queued speaks in order`() {
        val spoken = mutableListOf<String>()
        val speaker = TtsSpeaker()
        speaker.setEngine { text, _ -> spoken.add(text) }

        speaker.speak("First")
        speaker.speak("Second")
        speaker.onReady()

        assertEquals(listOf("First", "Second"), spoken)
    }

    @Test
    fun `speak after onReady invokes engine immediately`() {
        val spoken = mutableListOf<String>()
        val speaker = TtsSpeaker()
        speaker.setEngine { text, _ -> spoken.add(text) }
        speaker.onReady()

        speaker.speak("Immediate")

        assertEquals(listOf("Immediate"), spoken)
    }

    @Test
    fun `onDone callback is forwarded to engine`() {
        var doneCalled = false
        val speaker = TtsSpeaker()
        speaker.setEngine { _, onDone -> onDone() }
        speaker.onReady()

        speaker.speak("Test") { doneCalled = true }

        assertTrue("onDone must be forwarded to engine", doneCalled)
    }
}

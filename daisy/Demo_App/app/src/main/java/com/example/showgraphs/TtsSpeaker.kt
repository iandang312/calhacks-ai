package com.example.showgraphs

/**
 * Decouples TTS callers from TTS engine readiness.
 *
 * Calls to [speak] before [onReady] are queued and delivered in order once
 * [onReady] is called. After that, [speak] delegates immediately.
 *
 * The [Engine] SAM abstracts over Android TextToSpeech and ElevenLabs so
 * routing and fallback logic stays in [DaisyService], not here.
 */
class TtsSpeaker {

    fun interface Engine {
        fun speak(text: String, onDone: () -> Unit)
    }

    private var engine: Engine? = null
    private var ready = false
    private val queue = ArrayDeque<Pair<String, () -> Unit>>()

    /** Set the engine. If [onReady] has already been called, drains the queue immediately. */
    fun setEngine(engine: Engine) {
        this.engine = engine
        if (ready) drainQueue()
    }

    /** Signal that the engine is ready. Drains any queued speak calls. */
    fun onReady() {
        ready = true
        drainQueue()
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (!ready) {
            queue.addLast(text to onDone)
            return
        }
        engine!!.speak(text, onDone)
    }

    private fun drainQueue() {
        val e = engine ?: return
        while (queue.isNotEmpty()) {
            val (text, onDone) = queue.removeFirst()
            e.speak(text, onDone)
        }
    }
}

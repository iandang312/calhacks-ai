package com.example.showgraphs

import android.util.Log

class ConversationEngine(
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun speak(text: String, onDone: (() -> Unit)? = null)
        fun showOverlay(state: DaisyState)
        fun hideOverlay()
        fun leaveApp()
        fun execute(command: ParsedCommand)
    }

    private var phase = Phase.STANDBY
    private var pendingCommand: ParsedCommand? = null

    // Rolling buffer of recent speech while in STANDBY. Deepgram often finalizes
    // "Hi Daisy" as two segments ("Hi." then "Daisy."); buffering recent finals
    // lets the recombined text trigger the wake phrase.
    private val wakeBuffer = StringBuilder()
    private var lastWakeInputAt = 0L

    private enum class Phase {
        STANDBY,
        GREETING,
        LISTENING,
        CONFIRMING,
        EXECUTING,
    }

    fun onPartialSpeech(text: String) {
        // The wake phrase is idempotent: it (re)starts a session from any phase,
        // so every "Hi Daisy" behaves the same. It is only ignored while the
        // greeting is mid-playback, so the interim + final of one utterance don't
        // double-trigger.
        if (phase != Phase.GREETING && heardWake(text, isFinal = false)) {
            onWake()
            return
        }
        when (phase) {
            Phase.GREETING, Phase.LISTENING, Phase.CONFIRMING -> {
                if (CommandInterpreter.isGoodbye(text)) endSession()
            }
            Phase.STANDBY, Phase.EXECUTING -> Unit
        }
    }

    fun onFinalSpeech(text: String) {
        Log.i(TAG, "heard: $text phase=$phase")
        if (phase != Phase.GREETING && heardWake(text, isFinal = true)) {
            onWake()
            return
        }
        when (phase) {
            Phase.STANDBY -> Unit
            Phase.GREETING -> Unit
            Phase.LISTENING -> onCommand(text)
            Phase.CONFIRMING -> onConfirmation(text)
            Phase.EXECUTING -> Unit
        }
    }

    fun onNoSpeech() {
        // Be patient: a stretch of silence is fine. Daisy stays in her current
        // phase and keeps listening instead of nagging the user.
    }

    /**
     * Tests whether the wake phrase has been heard, tolerating Deepgram
     * splitting it across segments. The current fragment is checked against the
     * recent buffer without committing partials (so interim results don't
     * pollute it); only finalized fragments are retained for the next segment.
     * The buffer resets after [WAKE_BUFFER_WINDOW_MS] of silence.
     */
    private fun heardWake(text: String, isFinal: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastWakeInputAt > WAKE_BUFFER_WINDOW_MS) wakeBuffer.setLength(0)
        lastWakeInputAt = now

        val candidate = if (wakeBuffer.isEmpty()) text else "$wakeBuffer $text"
        if (CommandInterpreter.isWakePhrase(candidate)) {
            wakeBuffer.setLength(0)
            return true
        }
        if (isFinal) {
            if (wakeBuffer.isNotEmpty()) wakeBuffer.append(' ')
            wakeBuffer.append(text)
            if (wakeBuffer.length > WAKE_BUFFER_MAX) {
                wakeBuffer.delete(0, wakeBuffer.length - WAKE_BUFFER_MAX)
            }
        }
        return false
    }

    fun onWake() {
        // Idempotent: restart cleanly regardless of the current phase. Show the
        // orb over whatever app is on screen (don't navigate away) so Daisy can
        // act on the current app via the accessibility service, then greet + listen.
        pendingCommand = null
        phase = Phase.GREETING
        callbacks.showOverlay(DaisyState.AWAKE)
        Log.i("DAISY_WAKE", "WAKE")
        callbacks.speak("Hi, how can I help you?") {
            phase = Phase.LISTENING
            callbacks.showOverlay(DaisyState.LISTENING)
        }
    }

    private fun onCommand(text: String) {
        if (CommandInterpreter.isGoodbye(text)) {
            endSession()
            return
        }

        val parsed = CommandInterpreter.parse(text)
        // Be patient: if we can't map the speech to an action yet, just keep
        // listening silently instead of repeatedly saying we didn't understand.
        if (parsed.action == AgentAction.UNKNOWN) {
            Log.i(TAG, "no actionable command in: $text — staying patient")
            return
        }

        // Act on the command directly — no "should I do that?" confirmation step.
        executeNow(parsed)
    }

    private fun onConfirmation(text: String) {
        if (CommandInterpreter.isGoodbye(text)) {
            endSession()
            return
        }
        when {
            CommandInterpreter.isAffirmative(text) -> {
                val command = pendingCommand ?: return
                executeNow(command)
            }
            CommandInterpreter.isNegative(text) -> {
                callbacks.speak("Okay, cancelled.") { endSession() }
            }
            else -> callbacks.speak("Didn't catch that, can you say it again?")
        }
    }

    private fun executeNow(command: ParsedCommand) {
        pendingCommand = null
        phase = Phase.EXECUTING
        callbacks.showOverlay(DaisyState.PROCESSING)
        callbacks.speak("Okay, ${command.summary}.") {
            callbacks.execute(command)
            phase = Phase.LISTENING
            callbacks.showOverlay(DaisyState.LISTENING)
        }
    }

    private fun endSession() {
        pendingCommand = null
        phase = Phase.STANDBY
        wakeBuffer.setLength(0)
        lastWakeInputAt = 0L
        callbacks.hideOverlay()
    }

    fun stopSession() = endSession()

    fun currentState(): DaisyState = when (phase) {
        Phase.STANDBY -> DaisyState.STANDBY
        Phase.GREETING -> DaisyState.AWAKE
        Phase.LISTENING -> DaisyState.LISTENING
        Phase.CONFIRMING -> DaisyState.CONFIRMING
        Phase.EXECUTING -> DaisyState.PROCESSING
    }

    companion object {
        private const val TAG = "DAISY_CONV"

        /** Reset the wake buffer after this much silence between fragments. */
        private const val WAKE_BUFFER_WINDOW_MS = 4000L

        /** Cap the wake buffer so old speech can't accumulate unbounded. */
        private const val WAKE_BUFFER_MAX = 64
    }
}

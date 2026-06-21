package com.example.showgraphs

import android.os.Handler
import android.os.Looper
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

        /**
         * Send a speech transcript to the backend intent service. Exactly one of
         * [onPlan] (the LLM's first-person plan) or [onError] (a user-facing
         * message) is invoked on the main thread.
         */
        fun inferIntent(
            transcript: String,
            onPlan: (String) -> Unit,
            onError: (String) -> Unit,
        )

        /** Cancel any in-flight intent request (the session is ending). */
        fun cancelIntent()
    }

    private var phase = Phase.STANDBY
    private var pendingCommand: ParsedCommand? = null

    // Rolling buffer of recent speech while in STANDBY. Deepgram often finalizes
    // "Hi Daisy" as two segments ("Hi." then "Daisy."); buffering recent finals
    // lets the recombined text trigger the wake phrase.
    private val wakeBuffer = StringBuilder()
    private var lastWakeInputAt = 0L

    // Ends an active session after a stretch of silence so Daisy doesn't stay in
    // LISTENING forever. Re-armed on every utterance, cancelled while she speaks
    // or executes, so it only fires when the user has truly gone quiet.
    private val handler = Handler(Looper.getMainLooper())
    private val inactivityRunnable = Runnable { onInactivityTimeout() }

    // Caps the confirmation reprompt so an unclear yes/no can't loop forever.
    private var confirmRetries = 0

    // While listening, finalized speech segments accumulate here instead of being
    // sent one at a time. Deepgram finalizes a segment on every natural pause, so
    // sending on the first one cuts off a still-in-progress instruction. We wait
    // for COMMAND_DEBOUNCE_MS of quiet (no new segments) before sending the whole.
    private val pendingTranscript = StringBuilder()
    private val commandSendRunnable = Runnable { sendBufferedCommand() }

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
                if (CommandInterpreter.isGoodbye(text)) {
                    endSession()
                    return
                }
                // Interim words mean the user is still mid-instruction. Push the
                // pending send back so we don't cut them off between segments.
                if (phase == Phase.LISTENING && pendingTranscript.isNotEmpty()) {
                    scheduleCommandSend()
                }
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
        confirmRetries = 0
        cancelInactivityTimeout()
        cancelCommandSend()
        callbacks.cancelIntent()
        phase = Phase.GREETING
        callbacks.showOverlay(DaisyState.AWAKE)
        Log.i("DAISY_WAKE", "WAKE")
        callbacks.speak("Hi, how can I help you?") {
            phase = Phase.LISTENING
            callbacks.showOverlay(DaisyState.LISTENING)
            armInactivityTimeout()
        }
    }

    private fun onCommand(text: String) {
        if (CommandInterpreter.isGoodbye(text)) {
            endSession()
            return
        }

        // Accumulate this finalized segment and wait for a pause before sending,
        // so a mid-sentence breath doesn't ship a half-finished instruction. The
        // assembled transcript goes to the backend once the user truly stops.
        if (pendingTranscript.isNotEmpty()) pendingTranscript.append(' ')
        pendingTranscript.append(text)
        Log.i(TAG, "buffering command: $pendingTranscript")
        cancelInactivityTimeout()
        scheduleCommandSend()
    }

    private fun scheduleCommandSend() {
        handler.removeCallbacks(commandSendRunnable)
        handler.postDelayed(commandSendRunnable, COMMAND_DEBOUNCE_MS)
    }

    private fun cancelCommandSend() {
        handler.removeCallbacks(commandSendRunnable)
        pendingTranscript.setLength(0)
    }

    private fun sendBufferedCommand() {
        // Only fire if we're still gathering a command; a wake/goodbye/teardown
        // in the debounce window moves us out of LISTENING and cancels this.
        if (phase != Phase.LISTENING) return
        val text = pendingTranscript.toString().trim()
        pendingTranscript.setLength(0)
        if (text.isEmpty()) return
        // Hand the raw transcript to the backend LLM. It infers intent from noisy
        // speech and returns a first-person plan, which we speak back. No local
        // command parsing — the model owns interpretation now.
        sendToIntentService(text)
    }

    private fun sendToIntentService(text: String) {
        cancelInactivityTimeout()
        phase = Phase.EXECUTING
        callbacks.showOverlay(DaisyState.PROCESSING)
        callbacks.inferIntent(
            transcript = text,
            onPlan = { plan -> onPlanReceived(plan) },
            onError = { message -> onIntentError(message) },
        )
    }

    private fun onPlanReceived(plan: String) {
        // Drop a late response if the session moved on (ended, or re-woken) while
        // the request was in flight.
        if (phase != Phase.EXECUTING) return
        callbacks.speak(plan) { returnToListening() }
    }

    private fun onIntentError(message: String) {
        if (phase != Phase.EXECUTING) return
        callbacks.speak(message) { returnToListening() }
    }

    private fun returnToListening() {
        phase = Phase.LISTENING
        callbacks.showOverlay(DaisyState.LISTENING)
        armInactivityTimeout()
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
            else -> {
                confirmRetries++
                if (confirmRetries >= MAX_CONFIRM_RETRIES) {
                    callbacks.speak("I'll leave that for now. Say \"Hi Daisy\" when you need me.") {
                        endSession()
                    }
                } else {
                    callbacks.speak("Didn't catch that, can you say it again?") {
                        armInactivityTimeout()
                    }
                }
            }
        }
    }

    private fun executeNow(command: ParsedCommand) {
        pendingCommand = null
        confirmRetries = 0
        cancelInactivityTimeout()
        phase = Phase.EXECUTING
        callbacks.showOverlay(DaisyState.PROCESSING)
        callbacks.speak("Okay, ${command.summary}.") {
            callbacks.execute(command)
            phase = Phase.LISTENING
            callbacks.showOverlay(DaisyState.LISTENING)
            armInactivityTimeout()
        }
    }

    private fun endSession() {
        pendingCommand = null
        confirmRetries = 0
        cancelInactivityTimeout()
        cancelCommandSend()
        callbacks.cancelIntent()
        phase = Phase.STANDBY
        wakeBuffer.setLength(0)
        lastWakeInputAt = 0L
        callbacks.hideOverlay()
    }

    private fun armInactivityTimeout() {
        handler.removeCallbacks(inactivityRunnable)
        handler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS)
    }

    private fun cancelInactivityTimeout() {
        handler.removeCallbacks(inactivityRunnable)
    }

    private fun onInactivityTimeout() {
        // Only end an actively-listening session. If Daisy is mid-greeting or
        // executing, the timer was already cancelled — this is a belt-and-braces
        // guard against a stale callback firing after a phase change.
        if (phase != Phase.LISTENING && phase != Phase.CONFIRMING) return
        Log.i(TAG, "inactivity timeout after ${INACTIVITY_TIMEOUT_MS}ms — ending session")
        callbacks.speak("I'll be here if you need me. Say \"Hi Daisy\" to wake me.") {
            endSession()
        }
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

        /**
         * End an active session after this much silence while listening, so Daisy
         * doesn't stay open indefinitely. Tune up if it feels like it cuts users
         * off before they've had time to respond.
         */
        private const val INACTIVITY_TIMEOUT_MS = 10_000L

        /** Give up the confirmation reprompt after this many unclear replies. */
        private const val MAX_CONFIRM_RETRIES = 2

        /**
         * How long to wait for the user to keep talking before sending the
         * assembled instruction. Each new speech segment resets this, so the send
         * only fires once they've paused for this long. Raise it if instructions
         * still get cut off; lower it if Daisy feels sluggish to respond.
         */
        private const val COMMAND_DEBOUNCE_MS = 2000L
    }
}

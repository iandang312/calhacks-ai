package com.example.showgraphs

import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.example.showgraphs.voice.ElevenLabsTts
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

class DaisyService : android.app.Service(), ConversationEngine.Callbacks, VoiceAssistant.Listener {

    private lateinit var voiceAssistant: VoiceAssistant
    private lateinit var conversationEngine: ConversationEngine
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var elevenLabs: ElevenLabsTts? = null

    private var windowManager: WindowManager? = null
    private var overlayOrb: DaisyOrbView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var dismissTarget: TextView? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var overlayVisible = false

    private val handler = Handler(Looper.getMainLooper())
    private val utteranceId = AtomicInteger(0)
    private var pendingTtsDone: (() -> Unit)? = null

    private var dragStartX = 0
    private var dragStartY = 0
    private var overlayStartX = 0
    private var overlayStartY = 0

    override fun onBind(intent: android.content.Intent?) = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        pendingTtsDone?.invoke()
                        pendingTtsDone = null
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post {
                        pendingTtsDone?.invoke()
                        pendingTtsDone = null
                    }
                }
            })
        }

        elevenLabs = ElevenLabsTts(
            this,
            BuildConfig.ELEVENLABS_API_KEY,
            BuildConfig.ELEVENLABS_VOICE_ID,
        )

        conversationEngine = ConversationEngine(this)
        voiceAssistant = VoiceAssistant(this, this)
        voiceAssistant.start()
    }

    override fun onDestroy() {
        voiceAssistant.destroy()
        elevenLabs?.shutdown()
        tts?.shutdown()
        removeOverlay()
        super.onDestroy()
    }

    override fun speak(text: String, onDone: (() -> Unit)?) {
        // Stop feeding the mic to STT so Daisy doesn't transcribe her own voice,
        // then resume once playback completes.
        if (::voiceAssistant.isInitialized) voiceAssistant.pauseInput()
        val finish: () -> Unit = {
            if (::voiceAssistant.isInitialized) voiceAssistant.resumeInput()
            onDone?.invoke()
        }

        val eleven = elevenLabs
        if (eleven != null && eleven.isConfigured) {
            eleven.speak(text, onDone = finish, onError = { androidSpeak(text, finish) })
        } else {
            androidSpeak(text, finish)
        }
    }

    private fun androidSpeak(text: String, onDone: (() -> Unit)?) {
        if (!ttsReady) {
            onDone?.invoke()
            return
        }
        pendingTtsDone = onDone
        val id = utteranceId.incrementAndGet().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun showOverlay(state: DaisyState) {
        if (state == DaisyState.STANDBY) {
            removeOverlay()
            return
        }
        ensureOverlay()
        overlayOrb?.setState(state)
    }

    override fun hideOverlay() = removeOverlay()

    override fun leaveApp() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }

    override fun execute(command: ParsedCommand) {
        when (command.action) {
            AgentAction.OPEN_APP -> {
                val appName = command.appName.orEmpty()
                if (appName.isBlank() || !DaisyAccessibilityService.openApp(appName)) {
                    speak("I couldn't open $appName. I may not be able to see that app yet.")
                }
            }
            AgentAction.APP_SEARCH -> {
                val query = command.searchQuery.orEmpty()
                if (query.isBlank() || !DaisyAccessibilityService.searchInApp(command.appName, query)) {
                    speak("I couldn't search for $query on this screen.")
                }
            }
            AgentAction.TAP_TEXT -> {
                val target = command.targetText.orEmpty()
                if (target.isBlank() || !DaisyAccessibilityService.tapByText(target)) {
                    speak("I couldn't find $target on the screen.")
                }
            }
            AgentAction.TYPE_TEXT -> {
                val input = command.inputText.orEmpty()
                if (input.isBlank() || !DaisyAccessibilityService.typeIntoFocused(input)) {
                    speak("I couldn't type there. Try tapping the field first.")
                }
            }
            AgentAction.SCROLL -> {
                val direction = command.scrollDirection ?: ScrollDirection.DOWN
                if (!DaisyAccessibilityService.scroll(direction)) {
                    speak("I couldn't scroll this screen.")
                }
            }
            AgentAction.SCAN_SCREEN -> {
                val visibleText = DaisyAccessibilityService.scanScreen()
                    .mapNotNull { it.text ?: it.description }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(6)
                speak(
                    if (visibleText.isEmpty()) {
                        "I don't see readable controls on this screen."
                    } else {
                        "I see ${visibleText.joinToString(", ")}."
                    },
                )
            }
            AgentAction.GO_BACK -> {
                if (!DaisyAccessibilityService.back()) {
                    speak("I couldn't go back.")
                }
            }
            AgentAction.UNKNOWN -> speak("I don't know how to do that yet.")
        }
    }

    override fun onPartialSpeech(text: String) {
        conversationEngine.onPartialSpeech(text)
    }

    override fun onFinalSpeech(text: String) {
        conversationEngine.onFinalSpeech(text)
    }

    override fun onRmsChanged(rms: Float) {
        overlayOrb?.setVoiceLevel(rms)
        handler.post { overlayOrb?.invalidate() }
    }

    override fun onNoSpeech() {
        conversationEngine.onNoSpeech()
    }

    private fun ensureOverlay() {
        if (overlayVisible) return

        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            80f,
            resources.displayMetrics,
        ).toInt()

        overlayOrb = DaisyOrbView(this).apply {
            layoutParams = WindowManager.LayoutParams(sizePx, sizePx)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        overlayOrb?.setOnTouchListener { _, event -> handleDrag(event) }
        windowManager?.addView(overlayOrb, overlayParams)
        overlayVisible = true
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        val params = overlayParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.rawX.toInt()
                dragStartY = event.rawY.toInt()
                overlayStartX = params.x
                overlayStartY = params.y
                showDismissTarget()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = overlayStartX + (event.rawX.toInt() - dragStartX)
                params.y = overlayStartY + (event.rawY.toInt() - dragStartY)
                windowManager?.updateViewLayout(overlayOrb, params)
                dismissTarget?.alpha = if (isOverDismissTarget(event.rawX, event.rawY)) 1f else 0.72f
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val shouldDismiss = isOverDismissTarget(event.rawX, event.rawY)
                hideDismissTarget()
                if (shouldDismiss) {
                    conversationEngine.stopSession()
                }
                return true
            }
        }
        return false
    }

    private fun showDismissTarget() {
        if (dismissTarget != null) return

        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            72f,
            resources.displayMetrics,
        ).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        dismissTarget = TextView(this).apply {
            text = "X"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.72f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1F2933.toInt())
                setStroke(
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        2f,
                        resources.displayMetrics,
                    ).toInt(),
                    0x55FFFFFF,
                )
            }
        }

        dismissParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                40f,
                resources.displayMetrics,
            ).toInt()
        }

        windowManager?.addView(dismissTarget, dismissParams)
    }

    private fun hideDismissTarget() {
        dismissTarget?.let { windowManager?.removeView(it) }
        dismissTarget = null
        dismissParams = null
    }

    private fun isOverDismissTarget(rawX: Float, rawY: Float): Boolean {
        if (dismissTarget == null) return false
        val screen = resources.displayMetrics
        val targetCenterX = screen.widthPixels / 2f
        val targetCenterY = screen.heightPixels - TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            76f,
            resources.displayMetrics,
        )
        val activationRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            72f,
            resources.displayMetrics,
        )
        return hypot(rawX - targetCenterX, rawY - targetCenterY) <= activationRadius
    }

    private fun removeOverlay() {
        hideDismissTarget()
        if (!overlayVisible) return
        overlayOrb?.let { windowManager?.removeView(it) }
        overlayOrb = null
        overlayParams = null
        overlayVisible = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            android.app.NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(android.app.NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val openApp = android.app.PendingIntent.getActivity(
            this,
            0,
            android.content.Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "daisy_service"
        private const val NOTIFICATION_ID = 1001
    }
}

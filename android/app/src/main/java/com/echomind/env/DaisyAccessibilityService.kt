package com.echomind.env

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max
import kotlin.math.min

data class VisibleNode(
    val text: String?,
    val description: String?,
    val viewId: String?,
    val className: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
)

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

class DaisyAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        Log.d(TAG, "event=${event.eventType} package=${event.packageName} class=${event.className}")
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun openPackage(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return true
    }

    private fun openAppByName(appName: String): Boolean {
        val packageName = findLaunchablePackage(appName) ?: return false
        return openPackage(packageName)
    }

    private fun findLaunchablePackage(appName: String): String? {
        val requested = appName.clean()
        if (requested.isBlank()) return null

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { resolveInfo ->
                val label = resolveInfo.loadLabel(packageManager).toString()
                LaunchableApp(
                    label = label,
                    packageName = resolveInfo.activityInfo.packageName,
                    score = resolveInfo.matchScore(requested, label),
                )
            }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }

        return launchable.firstOrNull()?.packageName
    }

    private fun searchInApp(appName: String?, query: String): Boolean {
        val requestedApp = appName?.takeIf { it.isNotBlank() }
        if (requestedApp != null && !openAppByName(requestedApp)) return false
        mainHandler.postDelayed({
            clickSearchField()
            mainHandler.postDelayed({
                if (!setTextOnFocused(query)) {
                    setTextOnBestEditable(query)
                }
            }, INPUT_DELAY_MS)
        }, if (requestedApp == null) INPUT_DELAY_MS else APP_LAUNCH_DELAY_MS)
        return true
    }

    private fun clickSearchField(): Boolean {
        val searchLabels = listOf(
            "Where to",
            "Where to?",
            "Search",
            "Destination",
            "Enter destination",
            "Search destination",
            "Set destination",
            "Search",
            "Find",
        )
        return searchLabels.any { clickByVisibleText(it) } ||
            clickFirstEditable() ||
            clickBestClickable()
    }

    private fun scanVisibleNodes(): List<VisibleNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<VisibleNode>()
        root.walk { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && isInteresting(node)) {
                nodes.add(
                    VisibleNode(
                        text = node.text?.toString(),
                        description = node.contentDescription?.toString(),
                        viewId = node.viewIdResourceName,
                        className = node.className?.toString(),
                        bounds = Rect(bounds),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                    ),
                )
            }
        }
        return nodes
    }

    private fun clickByVisibleText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestNode(root) { it.visibleTextMatches(text) } ?: return false
        return performClick(node)
    }

    private fun clickByDescription(description: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestNode(root) { it.descriptionMatches(description) } ?: return false
        return performClick(node)
    }

    private fun clickByViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByViewId(viewId)
        return matches.firstOrNull()?.let { performClick(it) } == true
    }

    private fun clickFirstEditable(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestNode(root) { it.isEditable } ?: return false
        return performClick(node) || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    private fun clickBestClickable(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findBestNode(root) { it.isClickable } ?: return false
        return performClick(node)
    }

    private fun clickAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun typeText(text: String): Boolean {
        return setTextOnFocused(text) ||
            setTextOnBestEditable(text)
    }

    private fun setTextOnFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findBestNode(root) { it.isFocused && it.isEditable } ?: return false
        return setNodeText(focused, text)
    }

    private fun setTextOnBestEditable(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findBestNode(root) { it.isEditable } ?: return false
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return setNodeText(editable, text)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scroll(direction: ScrollDirection): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findBestNode(root) { it.isScrollable }
        if (scrollable != null) {
            val action = when (direction) {
                ScrollDirection.UP, ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ScrollDirection.DOWN, ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (scrollable.performAction(action)) return true
        }
        return dispatchSwipe(direction)
    }

    private fun dispatchSwipe(direction: ScrollDirection): Boolean {
        val metrics = resources.displayMetrics
        val left = metrics.widthPixels * 0.2f
        val right = metrics.widthPixels * 0.8f
        val top = metrics.heightPixels * 0.25f
        val bottom = metrics.heightPixels * 0.75f
        val centerX = metrics.widthPixels * 0.5f
        val centerY = metrics.heightPixels * 0.5f

        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.UP -> listOf(centerX, top, centerX, bottom)
            ScrollDirection.DOWN -> listOf(centerX, bottom, centerX, top)
            ScrollDirection.LEFT -> listOf(left, centerY, right, centerY)
            ScrollDirection.RIGHT -> listOf(right, centerY, left, centerY)
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return !bounds.isEmpty && clickAt(bounds.centerX(), bounds.centerY())
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        root.walk { node ->
            if (!predicate(node)) return@walk
            val score = node.score()
            if (score > bestScore) {
                best = node
                bestScore = score
            }
        }
        return best
    }

    private fun AccessibilityNodeInfo.walk(block: (AccessibilityNodeInfo) -> Unit) {
        block(this)
        for (i in 0 until childCount) {
            getChild(i)?.walk(block)
        }
    }

    private fun AccessibilityNodeInfo.visibleTextMatches(text: String): Boolean {
        val target = text.clean()
        return this.text?.toString()?.clean()?.contains(target) == true ||
            contentDescription?.toString()?.clean()?.contains(target) == true
    }

    private fun AccessibilityNodeInfo.descriptionMatches(text: String): Boolean {
        val target = text.clean()
        return contentDescription?.toString()?.clean()?.contains(target) == true
    }

    private fun AccessibilityNodeInfo.score(): Int {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        val screenArea = max(1, resources.displayMetrics.widthPixels * resources.displayMetrics.heightPixels)
        val nodeArea = max(1, bounds.width() * bounds.height())
        var score = 0
        if (isVisibleToUser) score += 100
        if (isClickable) score += 30
        if (isEditable) score += 40
        if (isFocusable) score += 10
        score -= min(40, nodeArea * 40 / screenArea)
        return score
    }

    private fun isInteresting(node: AccessibilityNodeInfo): Boolean {
        return node.isVisibleToUser &&
            (!node.text.isNullOrBlank() ||
                !node.contentDescription.isNullOrBlank() ||
                !node.viewIdResourceName.isNullOrBlank() ||
                node.isClickable ||
                node.isEditable ||
                node.isScrollable)
    }

    private fun CharSequence?.isNullOrBlank(): Boolean = this == null || this.toString().isBlank()

    private fun String.clean(): String = lowercase().trim()

    private fun ResolveInfo.matchScore(requested: String, label: String): Int {
        val cleanLabel = label.clean()
        val cleanPackage = activityInfo.packageName.clean()
        return when {
            cleanLabel == requested -> 100
            cleanLabel.startsWith(requested) -> 80
            cleanLabel.contains(requested) -> 60
            cleanPackage.contains(requested.replace(" ", "")) -> 40
            else -> 0
        }
    }

    private data class LaunchableApp(
        val label: String,
        val packageName: String,
        val score: Int,
    )

    companion object {
        private const val TAG = "DAISY_A11Y"
        private const val APP_LAUNCH_DELAY_MS = 1800L
        private const val INPUT_DELAY_MS = 450L
        private const val TAP_DURATION_MS = 80L
        private const val SWIPE_DURATION_MS = 450L

        @Volatile
        var instance: DaisyAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null

        fun openApp(appName: String): Boolean = instance?.openAppByName(appName) == true

        fun searchInApp(appName: String?, query: String): Boolean =
            instance?.searchInApp(appName, query) == true

        fun scanScreen(): List<VisibleNode> = instance?.scanVisibleNodes().orEmpty()

        fun tapByText(text: String): Boolean = instance?.clickByVisibleText(text) == true

        fun tapByDescription(description: String): Boolean =
            instance?.clickByDescription(description) == true

        fun tapByViewId(viewId: String): Boolean = instance?.clickByViewId(viewId) == true

        fun tapAt(x: Int, y: Int): Boolean = instance?.clickAt(x, y) == true

        fun typeIntoFocused(text: String): Boolean = instance?.typeText(text) == true

        fun scroll(direction: ScrollDirection): Boolean = instance?.scroll(direction) == true

        fun back(): Boolean =
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true

        fun home(): Boolean =
            instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true
    }
}

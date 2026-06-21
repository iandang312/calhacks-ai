package com.echomind.env

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

class DaisyOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.daisy_backdrop)
        style = Paint.Style.FILL
    }
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.daisy_petal)
        style = Paint.Style.FILL
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.daisy_center)
        style = Paint.Style.FILL
    }
    private val petalRect = RectF()

    private var voiceLevel = 0f
    private var bloom = 0f
    private var state = DaisyState.STANDBY

    fun setVoiceLevel(rms: Float) {
        voiceLevel = min(1f, (rms + 2f) / 12f)
        invalidate()
    }

    fun setState(newState: DaisyState) {
        state = newState
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(animationLoop)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animationLoop)
        super.onDetachedFromWindow()
    }

    private val animationLoop = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 16L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val targetBloom = when (state) {
            DaisyState.STANDBY -> 0.35f
            DaisyState.PROCESSING -> 0.75f
            else -> 0.55f + voiceLevel * 0.45f
        }
        bloom += (targetBloom - bloom) * 0.18f

        val cx = width / 2f
        val cy = height / 2f
        val orbRadius = min(width, height) * 0.46f
        val baseRadius = min(width, height) * 0.13f
        val petalLength = baseRadius * (1.55f + bloom * 0.95f)
        val petalWidth = baseRadius * 0.58f
        val petalAngles = floatArrayOf(-88f, -63f, -36f, -10f, 22f, 55f, 91f, 132f, 181f, 224f)

        canvas.drawCircle(cx, cy, orbRadius, backdropPaint)

        for (i in petalAngles.indices) {
            val stretch = if (i % 3 == 0) 1.12f else 0.95f
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(petalAngles[i])
            petalRect.set(
                -petalWidth / 2f,
                -petalLength * stretch,
                petalWidth / 2f,
                baseRadius * 0.45f,
            )
            canvas.drawOval(petalRect, petalPaint)
            canvas.restore()
        }

        val centerRadius = baseRadius * (1.05f + bloom * 0.24f)
        centerPaint.color = when (state) {
            DaisyState.STANDBY -> ContextCompat.getColor(context, R.color.daisy_center_idle)
            DaisyState.PROCESSING -> ContextCompat.getColor(context, R.color.daisy_center_processing)
            else -> ContextCompat.getColor(context, R.color.daisy_center)
        }
        canvas.drawCircle(cx, cy, centerRadius, centerPaint)
    }
}

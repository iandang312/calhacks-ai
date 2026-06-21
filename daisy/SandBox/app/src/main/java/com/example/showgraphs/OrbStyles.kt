package com.example.showgraphs

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat

object OrbStyles {
    private const val ORB_SIZE_DP = 280f

    fun apply(view: View, state: DaisyState, large: Boolean = true) {
        val sizeDp = if (large) ORB_SIZE_DP else 80f
        val (centerColor, edgeColor) = colorsFor(view.context, state)
        val radiusPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            sizeDp / 2f,
            view.resources.displayMetrics,
        )

        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = radiusPx
            colors = intArrayOf(centerColor, edgeColor)
        }
    }

    fun captionFor(state: DaisyState): String = when (state) {
        DaisyState.STANDBY -> "Say \"Hey Daisy\" anytime"
        DaisyState.AWAKE -> "Hi, how can I help you?"
        DaisyState.LISTENING -> "I'm listening..."
        DaisyState.CONFIRMING -> "Waiting for your confirmation..."
        DaisyState.PROCESSING -> "Working on your request..."
    }

    private fun colorsFor(context: Context, state: DaisyState): Pair<Int, Int> = when (state) {
        DaisyState.STANDBY -> ContextCompat.getColor(context, R.color.orb_idle_center) to
            ContextCompat.getColor(context, R.color.orb_idle_edge)
        DaisyState.AWAKE, DaisyState.LISTENING -> ContextCompat.getColor(context, R.color.orb_listening_center) to
            ContextCompat.getColor(context, R.color.orb_listening_edge)
        DaisyState.CONFIRMING -> ContextCompat.getColor(context, R.color.orb_primed_center) to
            ContextCompat.getColor(context, R.color.orb_primed_edge)
        DaisyState.PROCESSING -> ContextCompat.getColor(context, R.color.orb_primed_center) to
            ContextCompat.getColor(context, R.color.orb_primed_edge)
    }
}

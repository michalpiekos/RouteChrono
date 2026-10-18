package dev.mmp.go

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button

internal object AppAppearance {
    val background = Color.rgb(10, 17, 20)
    val surface = Color.rgb(24, 38, 42)
    val text = Color.rgb(235, 245, 241)
    val muted = Color.rgb(167, 188, 181)
    val mint = Color.rgb(117, 236, 183)
    val danger = Color.rgb(255, 167, 163)

    fun shape(color: Int, radius: Float, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        border?.let { setStroke(2, it) }
    }

    fun button(view: Button, primary: Boolean = false, destructive: Boolean = false) {
        val density = view.resources.displayMetrics.density
        val radius = 18 * density
        val colors = arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        )
        view.setTextColor(ColorStateList(colors, intArrayOf(
            Color.rgb(115, 134, 128), background,
            if (destructive) danger else if (primary) background else text,
        )))
        val states = StateListDrawable().apply {
            addState(colors[0], shape(Color.rgb(25, 33, 36), radius))
            addState(colors[1], shape(mint, radius))
            addState(colors[2], shape(if (primary) mint else surface, radius,
                if (destructive) Color.rgb(113, 63, 64) else Color.rgb(49, 76, 70)))
        }
        view.backgroundTintList = null
        view.background = RippleDrawable(ColorStateList.valueOf(0x3375ECB7), states, shape(Color.WHITE, radius))
        view.isAllCaps = false
        view.textSize = 17f
        view.minHeight = (56 * density).toInt()
        view.minimumHeight = (56 * density).toInt()
        view.setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
    }

    fun glow(view: View, activity: RecordingAppearanceState) {
        val top = when (activity) {
            RecordingAppearanceState.RECORDING -> Color.rgb(20, 100, 65)
            RecordingAppearanceState.MONITORING -> Color.rgb(20, 65, 51)
            else -> background
        }
        view.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, background))
    }
}

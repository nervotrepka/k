package io.github.nervotrepka.pitouch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/** Small helpers for building the UI in code. */
@SuppressLint("ClickableViewAccessibility")
class Widgets(private val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun dp(v: Int) = (v * density).toInt()
    fun dp(v: Float) = (v * density).toInt()

    fun background(color: Int): StateListDrawable {
        fun shape(c: Int) = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(c)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(KEY_PRESSED))
            addState(intArrayOf(), shape(color))
        }
    }

    fun button(label: String, textSize: Float = 14f, color: Int = KEY, onClick: (() -> Unit)? = null) =
        Button(context).apply {
            text = label
            isAllCaps = false
            this.textSize = textSize
            setTextColor(Color.WHITE)
            minWidth = dp(40)
            minimumWidth = dp(40)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), 0, dp(6), 0)
            stateListAnimator = null
            background = background(color)
            if (onClick != null) setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    /** Button that reports press and release separately (held keys, mouse buttons). */
    fun holdButton(label: String, textSize: Float = 14f, onDown: () -> Unit, onUp: () -> Unit) =
        button(label, textSize).apply {
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onDown()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        onUp()
                    }
                }
                true
            }
        }

    /** Equal-height row; each view gets its weight (0 = invisible spacer). */
    fun row(views: List<Pair<View, Float>>, height: Int, topMargin: Int = 4): LinearLayout =
        LinearLayout(context).apply {
            for ((v, weight) in views) {
                detach(v)
                addView(v, LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply {
                    leftMargin = dp(2); rightMargin = dp(2)
                })
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, if (height > 0) dp(height) else 0)
                .apply { this.topMargin = dp(topMargin) }
        }

    fun scrollRow(views: List<View>, height: Int): HorizontalScrollView {
        val inner = LinearLayout(context)
        for (v in views) {
            detach(v)
            inner.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(height)).apply { topMargin = dp(4) }
        }
    }

    fun spacer() = View(context)

    fun vertical() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    companion object {
        val BG = Color.rgb(0x15, 0x17, 0x1C)
        val KEY = Color.rgb(0x2C, 0x30, 0x38)
        val KEY_PRESSED = Color.rgb(0x4A, 0x50, 0x5C)
        val ONCE = Color.rgb(0xB5, 0x84, 0x1F)
        val ACTIVE = Color.rgb(0x2E, 0x8B, 0x57)
        val ACCENT = Color.rgb(0x2F, 0x5D, 0xA8)
        val DANGER = Color.rgb(0x8E, 0x2F, 0x2F)
        val MUTED_TEXT = Color.rgb(0xB0, 0xB4, 0xBC)
        val GOOD = Color.rgb(0x5C, 0xD6, 0x8A)
        val WARN = Color.rgb(0xF2, 0xB8, 0x4B)
        val BAD = Color.rgb(0xE5, 0x6B, 0x6B)

        fun detach(v: View) {
            (v.parent as? ViewGroup)?.removeView(v)
        }
    }
}

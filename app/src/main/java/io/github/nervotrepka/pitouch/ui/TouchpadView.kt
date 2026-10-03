package io.github.nervotrepka.pitouch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.nervotrepka.pitouch.hid.HidOutput
import io.github.nervotrepka.pitouch.hid.MouseButton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Touchpad gestures:
 * 1 finger move — cursor; tap — left click; tap then hold and move — drag;
 * 2 fingers move — scroll; 2-finger tap — right click; 3-finger tap — middle click.
 */
class TouchpadView(context: Context) : View(context) {

    var output: HidOutput? = null
    var sensitivity = 1f
    var acceleration = 1f
    var scrollSpeed = 1f
    var naturalScroll = true
    var tapToClick = true

    private enum class Mode { NONE, POINTER, SCROLL, IDLE }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val handler = Handler(Looper.getMainLooper())

    private var mode = Mode.NONE
    private var activeId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxPointers = 0
    private var moved = false
    private var fracX = 0f
    private var fracY = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    /** Left button pressed by a tap, released after [DRAG_WINDOW_MS] unless a drag starts. */
    private var tapHeld = false
    private var dragging = false
    private val releaseTap = Runnable {
        if (tapHeld && !dragging) {
            tapHeld = false
            output?.mouseButton(MouseButton.LEFT, false)
        }
    }

    private val touches = mutableListOf<Pair<Float, Float>>()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x26, 0x28, 0x2E) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x3A, 0x3D, 0x45)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x6B, 0x70, 0x7A)
        textAlign = Paint.Align.CENTER
        textSize = 13 * density
    }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0x4F, 0xC3, 0xF7) }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = 16 * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bgPaint)
        rect.inset(borderPaint.strokeWidth / 2, borderPaint.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, borderPaint)
        val cy = height / 2f
        canvas.drawText("1 палец — курсор, тап — клик", width / 2f, cy - textPaint.textSize, textPaint)
        canvas.drawText("2 пальца — прокрутка, тап — ПКМ", width / 2f, cy + textPaint.textSize * 0.4f, textPaint)
        canvas.drawText("тап + удержание — перетаскивание", width / 2f, cy + textPaint.textSize * 1.8f, textPaint)
        for ((x, y) in touches) canvas.drawCircle(x, y, 28 * density, touchPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = e.getPointerId(0)
                downX = e.x; downY = e.y; downTime = e.eventTime
                lastX = e.x; lastY = e.y; lastTime = e.eventTime
                maxPointers = 1
                moved = false
                fracX = 0f; fracY = 0f
                mode = Mode.POINTER
                if (tapHeld) {
                    handler.removeCallbacks(releaseTap)
                    dragging = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                if (e.pointerCount == 2 && mode == Mode.POINTER) {
                    mode = Mode.SCROLL
                    lastX = centroidX(e); lastY = centroidY(e)
                    scrollX = 0f; scrollY = 0f
                } else {
                    mode = Mode.IDLE
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.POINTER -> movePointer(e)
                Mode.SCROLL -> moveScroll(e)
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.SCROLL || mode == Mode.POINTER) mode = Mode.IDLE
            MotionEvent.ACTION_UP -> finishGesture(e)
            MotionEvent.ACTION_CANCEL -> {
                if (tapHeld || dragging) output?.mouseButton(MouseButton.LEFT, false)
                handler.removeCallbacks(releaseTap)
                tapHeld = false; dragging = false
                mode = Mode.NONE
            }
        }
        touches.clear()
        if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                touches += e.getX(i) to e.getY(i)
            }
        }
        invalidate()
        return true
    }

    private fun movePointer(e: MotionEvent) {
        val i = e.findPointerIndex(activeId)
        if (i < 0) return
        val x = e.getX(i)
        val y = e.getY(i)
        if (!moved && hypot(x - downX, y - downY) > slop) moved = true
        if (moved) {
            val dx = x - lastX
            val dy = y - lastY
            val dt = max(e.eventTime - lastTime, 1L)
            val speed = hypot(dx, dy) / density / dt // dp per ms
            val gain = BASE_GAIN * sensitivity * (1 + acceleration * (speed - 0.15f).coerceIn(0f, 2f))
            fracX += dx / density * gain
            fracY += dy / density * gain
            val ix = fracX.toInt()
            val iy = fracY.toInt()
            fracX -= ix; fracY -= iy
            output?.mouseMove(ix, iy)
        }
        lastX = x; lastY = y; lastTime = e.eventTime
    }

    private fun moveScroll(e: MotionEvent) {
        val x = centroidX(e)
        val y = centroidY(e)
        scrollX += (x - lastX) / density
        scrollY += (y - lastY) / density
        lastX = x; lastY = y
        if (!moved && (abs(scrollX) > slop / density || abs(scrollY) > slop / density)) moved = true
        if (!moved) return
        val step = SCROLL_STEP_DP / scrollSpeed
        val ny = (scrollY / step).toInt()
        val nx = (scrollX / step).toInt()
        scrollY -= ny * step
        scrollX -= nx * step
        val sign = if (naturalScroll) 1 else -1
        output?.mouseScroll(ny * sign, -nx * sign)
    }

    private fun finishGesture(e: MotionEvent) {
        val tap = !moved && e.eventTime - downTime < TAP_TIMEOUT_MS
        val out = output
        if (dragging) {
            dragging = false
            tapHeld = false
            out?.mouseButton(MouseButton.LEFT, false)
            if (tap && maxPointers == 1) click(MouseButton.LEFT) // second quick tap = double click
        } else if (tap && tapToClick) {
            when (maxPointers) {
                1 -> {
                    out?.mouseButton(MouseButton.LEFT, true)
                    tapHeld = true
                    handler.postDelayed(releaseTap, DRAG_WINDOW_MS)
                }
                2 -> click(MouseButton.RIGHT)
                else -> click(MouseButton.MIDDLE)
            }
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        mode = Mode.NONE
    }

    private fun click(button: Int) {
        output?.mouseButton(button, true)
        output?.mouseButton(button, false)
    }

    private fun centroidX(e: MotionEvent): Float = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun centroidY(e: MotionEvent): Float = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    private companion object {
        const val BASE_GAIN = 1.6f
        const val SCROLL_STEP_DP = 16f
        const val TAP_TIMEOUT_MS = 220L
        const val DRAG_WINDOW_MS = 220L
    }
}

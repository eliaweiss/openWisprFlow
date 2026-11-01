package app.openwisprflow

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating dictation control. It lives in a TYPE_ACCESSIBILITY_OVERLAY window owned by
 * the connected accessibility service, and is FLAG_NOT_FOCUSABLE so a tap never takes focus
 * from the destination field.
 */
class BubbleController(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    enum class State { IDLE, RECORDING, PROCESSING }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val sizePx = (BUBBLE_SIZE_DP * context.resources.displayMetrics.density).roundToInt()

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Default: against the right edge, just above a typical keyboard. A dragged position wins.
        val bounds = windowManager.currentWindowMetrics.bounds
        x = prefs.getInt(KEY_X, bounds.width() - sizePx)
        y = prefs.getInt(KEY_Y, (bounds.height() * DEFAULT_Y_FRACTION).roundToInt())
    }

    private val view = ImageView(context).apply {
        setImageResource(R.drawable.ic_mic)
        setBackgroundResource(R.drawable.bubble_background)
        val padding = sizePx / 4
        setPadding(padding, padding, padding, padding)
        contentDescription = context.getString(R.string.bubble_description)
        elevation = 6f * context.resources.displayMetrics.density
        // Partly transparent while idle, so the content underneath stays visible.
        alpha = BUBBLE_ALPHA
        setOnClickListener { onTap() }
        setOnTouchListener(DragListener())
    }

    private var shown = false

    /** The bubble's top edge in window coordinates. */
    val top: Int get() = params.y

    /**
     * Idle shows the microphone. Recording shows stop: a tap stops and submits, a long press
     * cancels. Processing shows ✕: a tap cancels. In translate mode (started by a long press) the
     * bubble is green instead of red.
     */
    fun setState(state: State, translate: Boolean = false) {
        val busy = if (translate) R.drawable.bubble_background_translate else R.drawable.bubble_background_busy
        val (icon, background, description) = when (state) {
            State.IDLE -> Triple(R.drawable.ic_mic, R.drawable.bubble_background, R.string.bubble_description)
            State.RECORDING -> Triple(R.drawable.ic_stop, busy, R.string.bubble_stop_description)
            State.PROCESSING -> Triple(R.drawable.ic_close, busy, R.string.bubble_cancel_description)
        }
        view.setImageResource(icon)
        view.setBackgroundResource(background)
        // See-through only while idle; a live recording should be unmistakable.
        view.alpha = if (state == State.IDLE) BUBBLE_ALPHA else 1f
        view.contentDescription = context.getString(description)
    }

    fun show() {
        if (shown) return
        windowManager.addView(view, params)
        shown = true
    }

    fun hide() {
        if (!shown) return
        windowManager.removeView(view)
        shown = false
    }

    /** Drag past touch slop moves the bubble and counts as neither a tap nor a long press. */
    private inner class DragListener : android.view.View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            view.isPressed = false
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    v.isPressed = true
                    v.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && !longPressed && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        v.isPressed = false
                        v.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        windowManager.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.removeCallbacks(longPress)
                    when {
                        dragging -> prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                        !longPressed -> v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(longPress)
                }
            }
            return true
        }
    }

    private companion object {
        const val BUBBLE_SIZE_DP = 52
        const val BUBBLE_ALPHA = 0.7f

        /** Top of the bubble as a fraction of the window height; 1372 px on a 2340 px screen. */
        const val DEFAULT_Y_FRACTION = 0.5863f
        const val PREFS = "bubble"
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}

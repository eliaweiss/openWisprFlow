package app.openwisprflow

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
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
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val sizePx = (BUBBLE_SIZE_DP * context.resources.displayMetrics.density).roundToInt()

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val bounds = windowManager.currentWindowMetrics.bounds
        x = prefs.getInt(KEY_X, bounds.width() - sizePx - sizePx / 3)
        y = prefs.getInt(KEY_Y, (bounds.height() * 0.4f).roundToInt())
    }

    private val view = ImageView(context).apply {
        setImageResource(R.drawable.ic_mic)
        setBackgroundResource(R.drawable.bubble_background)
        val padding = sizePx / 4
        setPadding(padding, padding, padding, padding)
        contentDescription = context.getString(R.string.bubble_description)
        elevation = 6f * context.resources.displayMetrics.density
        // Half transparent, so the content underneath stays visible.
        alpha = BUBBLE_ALPHA
        setOnClickListener { onTap() }
        setOnTouchListener(DragListener())
    }

    private var shown = false

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

    /** Drag past touch slop moves the bubble and does not count as a tap. */
    private inner class DragListener : android.view.View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        v.isPressed = false
                    }
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        windowManager.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                }
            }
            return true
        }
    }

    private companion object {
        const val BUBBLE_SIZE_DP = 52
        const val BUBBLE_ALPHA = 0.5f
        const val PREFS = "bubble"
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}

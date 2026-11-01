package app.openwisprflow

import android.content.Context
import android.graphics.PixelFormat
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shows live transcription text in a small strip just above the bubble while the user talks
 * (HLD section 5, Streaming). It never takes touches or focus; partials are display only and
 * are never inserted.
 */
class LiveTextController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var view: TextView? = null

    /** [bubbleTop] is the bubble's top edge in window coordinates; the strip sits above it. */
    fun show(text: String, bubbleTop: Int) {
        if (text.isBlank()) return
        val v = view ?: create(bubbleTop).also { view = it }
        // Keep the newest words in view.
        v.text = if (text.length > MAX_CHARS) "…" + text.takeLast(MAX_CHARS) else text
    }

    private fun create(bubbleTop: Int): TextView {
        val v = TextView(context).apply {
            setBackgroundResource(R.drawable.preview_background)
            setTextColor(context.getColor(R.color.preview_text))
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            val pad = (12 * density).roundToInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            alpha = 0.92f
        }
        val margin = (16 * density).roundToInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            horizontalMargin = 0f
            x = 0
            // Two lines of text plus padding, with a small gap above the bubble.
            y = (bubbleTop - 72 * density).roundToInt().coerceAtLeast(margin)
        }
        windowManager.addView(v, params)
        return v
    }

    fun hide() {
        view?.let { windowManager.removeView(it) }
        view = null
    }

    private companion object {
        const val MAX_CHARS = 160
    }
}

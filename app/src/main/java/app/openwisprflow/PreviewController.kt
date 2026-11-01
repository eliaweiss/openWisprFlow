package app.openwisprflow

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * The copy preview: shown when a result cannot be inserted safely. The text stays available to
 * copy instead of landing in the wrong field.
 */
class PreviewController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(text: String, reason: String) {
        dismiss()
        // An overlay window has no parent view; its size comes from the LayoutParams below.
        @SuppressLint("InflateParams")
        val card = LayoutInflater.from(context).inflate(R.layout.preview_card, null)
        card.findViewById<TextView>(R.id.preview_text).text = text
        card.findViewById<TextView>(R.id.preview_reason).text =
            context.getString(R.string.preview_reason, reason)
        card.findViewById<Button>(R.id.preview_copy).setOnClickListener {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
            Toast.makeText(context, R.string.preview_copied, Toast.LENGTH_SHORT).show()
            dismiss()
        }
        card.findViewById<Button>(R.id.preview_close).setOnClickListener { dismiss() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = (48 * context.resources.displayMetrics.density).toInt()
        }
        windowManager.addView(card, params)
        view = card
    }

    fun dismiss() {
        view?.let { windowManager.removeView(it) }
        view = null
    }
}

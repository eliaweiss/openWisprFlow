package app.openwisprflow

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reads the clipboard aloud when the accessibility button is pressed. Android lets only the app
 * with window focus read the clipboard, and an accessibility service has none, so this invisible
 * activity opens, reads the clipboard once it has focus, and closes.
 */
class ClipboardReadActivity : Activity() {

    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        Log.d("openWisprFlow", "clipboard read: ${text?.length ?: "nothing"}")
        ReadAloud.speak(this, text)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, ClipboardReadActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                ),
            )
        }
    }
}

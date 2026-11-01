package app.openwisprflow

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The "Read aloud" item in Android's text-selection menu (ACTION_PROCESS_TEXT). It receives the
 * selected text, hands it to [ReadAloud] and closes at once without showing anything; the text
 * in the other app is left unchanged.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReadAloud.speak(this, intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
        finish()
    }
}

package app.openwisprflow

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: stores the AI Gateway API key sent by scripts/set-api-key.sh, the same way
 * the Save button in [MainActivity] does. The result goes back to `am broadcast`; it never
 * contains the key.
 */
class DebugApiKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY)?.trim().orEmpty()
        if (key.isEmpty()) {
            setResult(Activity.RESULT_CANCELED, "no key given", null)
            return
        }
        try {
            ApiKeyStore.set(context, key)
        } catch (e: Exception) {
            setResult(Activity.RESULT_CANCELED, "not saved: ${e.javaClass.simpleName}", null)
            return
        }
        val saved = ApiKeyStore.get(context) == key
        setResult(
            if (saved) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            if (saved) "saved (${key.length} characters)" else "saved but did not read back",
            null,
        )
    }

    private companion object {
        const val EXTRA_KEY = "key"
    }
}

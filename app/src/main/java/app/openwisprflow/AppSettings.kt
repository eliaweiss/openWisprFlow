package app.openwisprflow

import android.content.Context

/** User settings that are not secrets. */
object AppSettings {

    private const val PREFS = "settings"
    private const val SCREEN_CONTEXT = "screen_context"
    private const val CLEANUP = "cleanup"
    private const val CLEANUP_PROMPT = "cleanup_prompt"

    /** Whether name-like terms near the field are sent as recognition hints. On by default. */
    fun useScreenContext(context: Context) = prefs(context).getBoolean(SCREEN_CONTEXT, true)

    fun setUseScreenContext(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SCREEN_CONTEXT, enabled).apply()
    }

    /** Whether the transcript goes through the cleanup model. On by default. */
    fun useCleanup(context: Context) = prefs(context).getBoolean(CLEANUP, true)

    fun setUseCleanup(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CLEANUP, enabled).apply()
    }

    /** The user's cleanup prompt, or the default when none was saved. */
    fun cleanupPrompt(context: Context): String =
        prefs(context).getString(CLEANUP_PROMPT, null) ?: CleanupPrompt.DEFAULT

    /** Saving the default (or a blank prompt) clears the override, so later default updates apply. */
    fun setCleanupPrompt(context: Context, prompt: String) {
        val trimmed = prompt.trim()
        prefs(context).edit().apply {
            if (trimmed.isEmpty() || trimmed == CleanupPrompt.DEFAULT) remove(CLEANUP_PROMPT) else putString(CLEANUP_PROMPT, trimmed)
        }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

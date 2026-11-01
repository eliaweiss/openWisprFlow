package app.openwisprflow

import android.content.Context

/** User settings that are not secrets. */
object AppSettings {

    private const val PREFS = "settings"
    private const val SCREEN_CONTEXT = "screen_context"

    /** Whether name-like terms near the field are sent as recognition hints. On by default. */
    fun useScreenContext(context: Context) = prefs(context).getBoolean(SCREEN_CONTEXT, true)

    fun setUseScreenContext(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(SCREEN_CONTEXT, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

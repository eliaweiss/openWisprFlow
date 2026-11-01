package app.openwisprflow

import android.content.Context
import android.os.Build

/**
 * Records how the last microphone start went, so build step 3 can show on each device whether a
 * bubble tap may start capture directly (HLD section 4, Audio). Stores no audio and no text.
 */
object MicDiagnostics {

    private const val PREFS = "mic_diagnostics"

    fun recordStart(context: Context, error: Throwable?) {
        prefs(context).edit()
            .putLong("start_time", System.currentTimeMillis())
            .putString("start_result", error?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "ok")
            .apply()
    }

    fun recordTake(context: Context, take: Take) {
        prefs(context).edit()
            .putLong("take_duration_ms", take.durationMs)
            .putInt("take_peak", take.peak)
            .putString("take_path", take.file.absolutePath)
            .apply()
    }

    fun lastTakeFile(context: Context): String? = prefs(context).getString("take_path", null)

    fun summary(context: Context): String {
        val p = prefs(context)
        val targetSdk = context.applicationInfo.targetSdkVersion
        val lines = mutableListOf("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), targetSdk $targetSdk")
        val result = p.getString("start_result", null)
        lines += if (result == null) "Microphone not started yet" else "Last microphone start: $result"
        if (p.contains("take_duration_ms")) {
            val seconds = p.getLong("take_duration_ms", 0) / 1000.0
            val peak = p.getInt("take_peak", 0)
            lines += "Last take: %.1f s, peak level %d of 32767%s".format(
                seconds,
                peak,
                if (peak == 0) " (silent: the microphone was blocked)" else "",
            )
        }
        return lines.joinToString("\n")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

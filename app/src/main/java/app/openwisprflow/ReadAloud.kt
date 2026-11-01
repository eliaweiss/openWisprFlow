package app.openwisprflow

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Reads text aloud with google/gemini-3.8-flash-lite-tts. Text arrives from the "Read aloud" item
 * in the text-selection menu ([ProcessTextActivity]) or from the clipboard when the accessibility
 * button is pressed ([ClipboardReadActivity]). One reading at a time: a new one replaces the
 * current one, and [toggle] stops it.
 *
 * The text is sent to AI Gateway only for that reading; logs record its length, never the text.
 * Process-wide, so playback continues after the short-lived activities finish. Main thread only.
 */
object ReadAloud {

    private const val TAG = "openWisprFlow"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // Holds only the application context, which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    private var player: SpeechPlayer? = null
    private var call: SpeechCall? = null

    val isBusy get() = call != null || player?.isPlaying == true

    fun stop() {
        call?.cancel()
        call = null
        player?.stop()
    }

    fun speak(context: Context, raw: String?) {
        val app = context.applicationContext
        stop()
        val selected = raw?.trim()?.takeIf { it.isNotEmpty() }
        if (selected == null) {
            toast(app, app.getString(R.string.speech_nothing_to_read))
            return
        }
        val key = ApiKeyStore.get(app)
        if (key == null) {
            toast(app, app.getString(R.string.api_key_needed))
            return
        }
        val text = selected.take(SpeechCall.MAX_CHARS)
        toast(
            app,
            if (text.length < selected.length) {
                app.getString(R.string.speech_truncated, SpeechCall.MAX_CHARS)
            } else {
                app.getString(R.string.speech_loading)
            },
        )
        val c = SpeechCall(key, text)
        call = c
        worker.execute {
            val started = System.currentTimeMillis()
            val result = runCatching { c.execute() }
            main.post {
                if (call !== c) return@post // Stopped or replaced while loading.
                call = null
                result.fold(
                    onSuccess = { wav ->
                        Log.d(TAG, "read aloud: ${text.length} chars, ${wav.size} bytes in ${System.currentTimeMillis() - started} ms")
                        val p = player ?: SpeechPlayer(app).also { player = it }
                        runCatching { p.play(wav) {} }.onFailure { failed(app, it) }
                    },
                    onFailure = { failed(app, it) },
                )
            }
        }
    }

    private fun failed(context: Context, error: Throwable) {
        Log.w(TAG, "read aloud failed: ${error.javaClass.simpleName} ${(error as? GatewayException)?.status ?: ""}")
        toast(context, context.getString(R.string.speech_failed, error.message ?: error.javaClass.simpleName))
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

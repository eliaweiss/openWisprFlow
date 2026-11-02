package app.openwisprflow

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Logs to logcat and to a file in app storage, because logcat on a busy phone only reaches back
 * about 20 minutes. The file holds at most two parts of [MAX_FILE_BYTES]. Like logcat, it records
 * events, lengths and timings, never transcripts, field or screen text, or the API key (HLD 7).
 */
object AppLog {

    private const val MAX_FILE_BYTES = 1_000_000L
    private const val FILE = "app.log"
    private const val MAX_STACK_FRAMES = 30

    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "openWisprFlow-log").apply { isDaemon = true }
    }
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
    }

    fun d(tag: String, message: String) {
        runCatching { Log.d(tag, message) }
        write("D", message)
    }

    fun w(tag: String, message: String) {
        runCatching { Log.w(tag, message) }
        write("W", message)
    }

    /** Records a crash and waits briefly for it to reach the file, since the process is ending. */
    fun crash(tag: String, thread: Thread, error: Throwable) {
        val frames = error.stackTrace.take(MAX_STACK_FRAMES).joinToString("\n  at ")
        val message = "crash on ${thread.name}: ${error.javaClass.name}\n  at $frames"
        runCatching { Log.e(tag, message) }
        runCatching { write("E", message)?.get(1, TimeUnit.SECONDS) }
    }

    private fun write(level: String, message: String) = dir?.let { target ->
        val stamp = synchronized(time) { time.format(Date()) }
        runCatching {
            writer.submit {
                runCatching {
                    val file = File(target, FILE)
                    if (file.length() > MAX_FILE_BYTES) file.renameTo(File(target, "$FILE.1"))
                    file.appendText("$stamp $level $message\n")
                }
            }
        }.getOrNull()
    }
}

/** The phone's current network, for the log: it shows whether a failure was the connection. */
object NetworkStatus {

    @Volatile private var manager: ConnectivityManager? = null

    fun init(context: Context) {
        manager = context.getSystemService(ConnectivityManager::class.java)
    }

    fun describe(): String = runCatching {
        val cm = manager ?: return "unknown"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val internet = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not-validated"
        "$transport $internet ${caps.linkDownstreamBandwidthKbps}kbps"
    }.getOrDefault("unknown")
}

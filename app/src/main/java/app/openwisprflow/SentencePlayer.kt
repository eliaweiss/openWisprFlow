package app.openwisprflow

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Reads a list of sentences aloud one at a time, as pocket-speak does: each sentence is a
 * separate google/gemini-3.8-flash-lite-tts request, the next [PREFETCH_AHEAD] are requested
 * while the current one plays, and the listener follows the current sentence so the reader
 * pop-up can highlight it. Play, pause, next and previous work at any point.
 *
 * One session at a time, process-wide: starting a new one ends the previous one and tells its
 * listener, so an older pop-up closes instead of fighting over the audio. Main thread only.
 * Sentences go to AI Gateway only while they are read; logs record lengths, never text.
 */
object SentencePlayer {

    enum class Status { LOADING, PLAYING, PAUSED, DONE }

    interface Listener {
        fun onSentence(index: Int, status: Status)
        fun onError(message: String)
        /** Another session started, or [stop] was called: this one is over. */
        fun onEnded()
    }

    private const val TAG = "openWisprFlow"
    /** Short sentences last about a second, less than one gateway round trip (2-4 s). */
    private const val PREFETCH_AHEAD = 3

    private val main = Handler(Looper.getMainLooper())
    private val fetcher = Executors.newFixedThreadPool(PREFETCH_AHEAD)
    /** Waits for the current sentence's audio; separate, so waiting never blocks a fetch. */
    private val waiter = Executors.newSingleThreadExecutor()

    private var key: String? = null
    private var sentences: List<String> = emptyList()
    private var listener: Listener? = null
    private var session = 0L
    private var index = 0
    private var status = Status.DONE
    private var player: MediaPlayer? = null
    private val fetches = mutableMapOf<Int, Pair<SpeechCall, Future<ByteArray>>>()

    val isActive get() = listener != null && status != Status.DONE

    /** Starts reading [list] from the first sentence. Returns the new session's id. */
    fun start(apiKey: String, list: List<String>, newListener: Listener): Long {
        stop()
        key = apiKey
        sentences = list
        listener = newListener
        session++
        play(0)
        return session
    }

    fun isCurrent(id: Long) = id == session && listener != null

    fun togglePause() {
        when (status) {
            Status.PLAYING -> {
                player?.pause()
                report(Status.PAUSED)
            }
            Status.PAUSED -> {
                val p = player
                if (p != null) {
                    p.start()
                    report(Status.PLAYING)
                } else {
                    play(index)
                }
            }
            Status.DONE -> if (sentences.isNotEmpty()) play(0)
            Status.LOADING -> Unit
        }
    }

    fun next() {
        if (index + 1 < sentences.size) play(index + 1)
    }

    fun previous() = play((index - 1).coerceAtLeast(0))

    /** Ends the session: stops audio, cancels requests, and tells the listener. */
    fun stop() {
        releasePlayer()
        fetches.values.forEach { it.first.cancel() }
        fetches.clear()
        status = Status.DONE
        val l = listener
        listener = null
        l?.onEnded()
    }

    private fun play(i: Int) {
        if (i !in sentences.indices) return
        releasePlayer()
        index = i
        report(Status.LOADING)
        // Requests for sentences we skipped back over stay cached; ones far behind are dropped.
        fetches.keys.filter { it < i - 1 }.forEach { fetches.remove(it)?.first?.cancel() }
        for (j in i..minOf(i + PREFETCH_AHEAD, sentences.lastIndex)) fetch(j)
        val future = fetches.getValue(i).second
        val id = session
        waiter.execute {
            val result = runCatching { future.get() }
            main.post { if (id == session && index == i && status == Status.LOADING) onAudio(i, result) }
        }
    }

    private fun fetch(i: Int) {
        if (i in fetches) return
        val call = SpeechCall(key ?: return, Sentences.speakable(sentences[i]).take(SpeechCall.MAX_CHARS))
        fetches[i] = call to fetcher.submit<ByteArray> { call.execute() }
    }

    private fun onAudio(i: Int, result: Result<ByteArray>) {
        val wav = result.getOrElse { e ->
            val cause = e.cause ?: e
            Log.w(TAG, "sentence ${i + 1} speech failed: ${cause.javaClass.simpleName} ${(cause as? GatewayException)?.status ?: ""}")
            fetches.remove(i)
            status = Status.PAUSED
            listener?.onError(cause.message ?: cause.javaClass.simpleName)
            return
        }
        Log.d(TAG, "sentence ${i + 1}/${sentences.size}: ${sentences[i].length} chars, ${wav.size} bytes")
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(BytesSource(wav))
            setOnCompletionListener {
                if (index + 1 < sentences.size) {
                    play(index + 1)
                } else {
                    releasePlayer()
                    report(Status.DONE)
                }
            }
            prepare()
            start()
        }
        report(Status.PLAYING)
    }

    private fun report(newStatus: Status) {
        status = newStatus
        listener?.onSentence(index, newStatus)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    /** Plays a WAV straight from memory. */
    private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize() = bytes.size.toLong()
        override fun close() = Unit
    }
}

package app.openwisprflow

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * The reader pop-up: text is split into sentences ([Sentences]) and read one at a time by
 * [SentencePlayer], with the current sentence highlighted and kept in view, and previous,
 * play/pause and next controls, as in pocket-speak.
 *
 * One activity behind four entry points; the component that started it decides what it does:
 * - ReaderActivity: "Read aloud" in the text-selection menu, with the selected text;
 * - SummarizeSelection: "Summarize" in the text-selection menu;
 * - ClipboardReadActivity / SummarizeClipboard: the "Read clipboard aloud" and "Summarize
 *   clipboard" accessibility shortcuts. Only the focused app may read the clipboard, so these
 *   read it once the pop-up has window focus.
 *
 * A summary comes from Mercury 3 ([SummaryCall]) and is then read the same way. Closing the
 * pop-up ends the reading; a newer pop-up taking over the reading closes this one.
 */
class ReaderActivity : Activity(), SentencePlayer.Listener {

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var body: TextView
    private lateinit var controls: View
    private lateinit var play: ImageButton
    private lateinit var copy: ImageButton

    private val entry get() = componentName.className.substringAfterLast('.')
    private val summarize get() = entry == "SummarizeSelection" || entry == "SummarizeClipboard"
    private val fromClipboard get() = entry == "SummarizeClipboard" || entry == "ClipboardReadActivity"

    private var started = false
    private var summaryCall: SummaryCall? = null
    private var split: Sentences.Split? = null
    private var session = -1L
    private var highlight: BackgroundColorSpan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        title = findViewById(R.id.reader_title)
        status = findViewById(R.id.reader_status)
        progress = findViewById(R.id.reader_progress)
        scroll = findViewById(R.id.reader_scroll)
        body = findViewById(R.id.reader_text)
        controls = findViewById(R.id.reader_controls)
        play = findViewById(R.id.reader_play)
        copy = findViewById(R.id.reader_copy)

        title.setText(if (summarize) R.string.summary_title else R.string.reader_title)
        showStatus(getString(R.string.reader_preparing))

        // The text scrolls inside the pop-up beyond 55% of the screen height.
        val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (scroll.height > maxHeight) scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
        }

        findViewById<ImageButton>(R.id.reader_close).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.reader_previous).setOnClickListener { if (owns()) SentencePlayer.previous() }
        findViewById<ImageButton>(R.id.reader_next).setOnClickListener { if (owns()) SentencePlayer.next() }
        play.setOnClickListener { if (owns()) SentencePlayer.togglePause() else split?.let(::read) }
        copy.setOnClickListener {
            split?.let {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(title.text, it.text))
                Toast.makeText(this, R.string.preview_copied, Toast.LENGTH_SHORT).show()
            }
        }

        if (!fromClipboard) begin(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fromClipboard && !started) {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            begin(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString())
        }
    }

    private fun owns() = SentencePlayer.isCurrent(session)

    private fun begin(raw: String?) {
        started = true
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return fail(getString(R.string.speech_nothing_to_read))
        val key = ApiKeyStore.get(this) ?: return fail(getString(R.string.api_key_needed))
        if (!summarize) {
            read(Sentences.split(text))
            return
        }
        showStatus(getString(R.string.summary_working, "%,d".format(text.length)))
        val c = SummaryCall(key, text)
        summaryCall = c
        thread(name = "openWisprFlow-summary") {
            val begun = System.currentTimeMillis()
            val result = runCatching { c.execute() }
            val elapsed = System.currentTimeMillis() - begun
            runOnUiThread {
                if (isDestroyed || summaryCall !== c) return@runOnUiThread
                summaryCall = null
                result.fold(
                    onSuccess = {
                        Log.d(TAG, "summary: ${text.length} -> ${it.length} chars in $elapsed ms")
                        read(Sentences.split(it))
                    },
                    onFailure = { e ->
                        Log.w(TAG, "summary failed: ${e.javaClass.simpleName} ${(e as? GatewayException)?.status ?: ""}")
                        fail(getString(R.string.summary_failed, e.message ?: e.javaClass.simpleName))
                    },
                )
            }
        }
    }

    private fun read(s: Sentences.Split) {
        if (s.sentences.isEmpty()) return fail(getString(R.string.speech_nothing_to_read))
        val key = ApiKeyStore.get(this) ?: return fail(getString(R.string.api_key_needed))
        split = s
        body.setText(s.text, TextView.BufferType.SPANNABLE)
        showStatus(null)
        scroll.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        copy.isEnabled = true
        session = SentencePlayer.start(key, s.sentences, this)
    }

    override fun onSentence(index: Int, status: SentencePlayer.Status) {
        val s = split ?: return
        // No status line while reading; a loading sentence shows as a spinner on the play button.
        val loading = status == SentencePlayer.Status.LOADING
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        val playing = status == SentencePlayer.Status.PLAYING || loading
        play.setImageResource(if (loading) 0 else if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription = getString(if (playing) R.string.reader_pause else R.string.reader_play)
        highlight(if (status == SentencePlayer.Status.DONE) null else s.ranges[index])
    }

    /** Marks the sentence being read and scrolls it into view. */
    private fun highlight(range: IntRange?) {
        val text = body.text as? Spannable ?: return
        highlight?.let(text::removeSpan)
        highlight = null
        if (range == null || range.isEmpty()) return
        val span = BackgroundColorSpan(getColor(R.color.accent_container))
        text.setSpan(span, range.first, range.last + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        highlight = span
        body.post {
            val layout = body.layout ?: return@post
            val top = layout.getLineTop(layout.getLineForOffset(range.first))
            val bottom = layout.getLineBottom(layout.getLineForOffset(range.last))
            val visibleTop = scroll.scrollY
            val visibleBottom = visibleTop + scroll.height
            if (top < visibleTop || bottom > visibleBottom) scroll.smoothScrollTo(0, (top - scroll.height / 4).coerceAtLeast(0))
        }
    }

    override fun onError(message: String) {
        progress.visibility = View.GONE
        showStatus(getString(R.string.speech_failed, message))
        play.setImageResource(R.drawable.ic_play)
    }

    /** A newer pop-up took over the reading: this one is done. */
    override fun onEnded() {
        if (!isFinishing) finish()
    }

    private fun fail(message: String) = showStatus(message)

    /** The status line holds messages only (summarizing, nothing to read, errors); null hides it. */
    private fun showStatus(message: String?) {
        status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        summaryCall?.cancel()
        summaryCall = null
        if (owns()) SentencePlayer.stop()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "openWisprFlow"
    }
}

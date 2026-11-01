package app.openwisprflow

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * On-device history (HLD section 6): every take's raw and final text and how it ended. Copy any
 * entry; retry a kept take, whose result goes to copy and never into a field, so a retry can not
 * insert twice; delete an entry and its audio.
 */
class HistoryActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val adapter = EntryAdapter()
    private var entries: List<HistoryEntry> = emptyList()
    private val retrying = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        val list = findViewById<ListView>(R.id.history_list)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.history_empty)
        findViewById<Button>(R.id.history_clear).setOnClickListener {
            History.clear(this)
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        History.prune(this)
        reload()
    }

    private fun reload() {
        entries = History.load(this)
        adapter.notifyDataSetChanged()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.preview_copied, Toast.LENGTH_SHORT).show()
    }

    private fun retry(entry: HistoryEntry) {
        val key = ApiKeyStore.get(this)
        val audio = entry.audioPath?.let(::File)?.takeIf { it.exists() }
        if (key == null || audio == null) return
        retrying += entry.id
        adapter.notifyDataSetChanged()
        val cleanup = AppSettings.useCleanup(this)
        val prompt = AppSettings.cleanupPrompt(this)
        val dictionary = Dictionary.load(this)
        thread(name = "openWisprFlow-retry") {
            val updated = try {
                val mode = if (cleanup) TranscriptionOptions.VERBATIM else TranscriptionOptions.SMART
                val vocabulary = Vocabulary.build(dictionary, emptyList())
                val raw = TranscriptionCall(key, audio, TranscriptionOptions(mode, vocabulary)).execute().text
                val final = when {
                    raw.isBlank() -> null
                    // The original field is gone, so cleanup runs without field context.
                    cleanup -> runCatching { CleanupCall(key, prompt, CleanupInput(raw, dictionary = dictionary)).execute() }
                        .getOrNull() ?: raw.trim()
                    else -> raw.trim()
                }
                entry.copy(
                    status = if (final == null) HistoryStatus.NOTHING_HEARD else HistoryStatus.PREVIEW,
                    raw = raw,
                    finalText = final,
                    detail = getString(R.string.history_retried),
                    promptHash = if (cleanup) History.promptHash(prompt) else null,
                )
            } catch (e: Exception) {
                entry.copy(status = HistoryStatus.FAILED, detail = e.message ?: e.javaClass.simpleName)
            }
            History.update(this, updated)
            main.post {
                retrying -= entry.id
                if (!isDestroyed) reload()
            }
        }
    }

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.history_item, parent, false)
            val entry = entries[position]
            val when_ = DateUtils.getRelativeTimeSpanString(entry.createdAt)
            view.findViewById<TextView>(R.id.item_header).text =
                getString(R.string.history_header, when_, getString(statusLabel(entry.status)))
            val text = entry.finalText ?: entry.raw
            view.findViewById<TextView>(R.id.item_text).apply {
                this.text = text ?: getString(R.string.history_no_text)
            }
            view.findViewById<TextView>(R.id.item_raw).apply {
                val showRaw = entry.raw != null && entry.raw.trim() != entry.finalText
                visibility = if (showRaw) View.VISIBLE else View.GONE
                if (showRaw) this.text = getString(R.string.history_raw, entry.raw)
            }
            view.findViewById<TextView>(R.id.item_detail).apply {
                visibility = if (entry.detail != null) View.VISIBLE else View.GONE
                this.text = entry.detail
            }
            view.findViewById<Button>(R.id.item_copy).apply {
                isEnabled = text != null
                setOnClickListener { text?.let(::copy) }
            }
            view.findViewById<Button>(R.id.item_retry).apply {
                val canRetry = entry.audioPath?.let { File(it).exists() } == true
                visibility = if (canRetry) View.VISIBLE else View.GONE
                isEnabled = entry.id !in retrying
                setText(if (entry.id in retrying) R.string.history_retrying else R.string.history_retry)
                setOnClickListener { retry(entry) }
            }
            view.findViewById<Button>(R.id.item_delete).setOnClickListener {
                History.delete(this@HistoryActivity, entry.id)
                reload()
            }
            return view
        }
    }

    private fun statusLabel(status: HistoryStatus) = when (status) {
        HistoryStatus.INSERTED -> R.string.status_inserted
        HistoryStatus.PREVIEW -> R.string.status_preview
        HistoryStatus.FAILED -> R.string.status_failed
        HistoryStatus.NOTHING_HEARD -> R.string.status_nothing_heard
        HistoryStatus.CANCELLED -> R.string.status_cancelled
    }
}

package app.openwisprflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** How a take ended. */
enum class HistoryStatus { INSERTED, PREVIEW, FAILED, NOTHING_HEARD, CANCELLED }

/**
 * One dictation in the on-device history (HLD section 6). [raw] is the transcript as returned by
 * the speech model, [finalText] the text that was inserted or offered for copy. [audioPath] is
 * kept for retry until it expires.
 */
data class HistoryEntry(
    val id: String,
    val createdAt: Long,
    val status: HistoryStatus,
    val raw: String? = null,
    val finalText: String? = null,
    val detail: String? = null,
    val audioPath: String? = null,
    val promptHash: String? = null,
)

/**
 * The history file and the takes it keeps. Takes expire after [AUDIO_TTL_MS]; deleting an entry
 * deletes its audio. Fields marked incognito never reach this store. All access is on the main
 * thread or synchronized on this object.
 */
object History {

    private const val FILE = "history.json"
    const val MAX_ENTRIES = 200
    const val AUDIO_TTL_MS = 24 * 60 * 60 * 1000L

    @Synchronized
    fun load(context: Context): List<HistoryEntry> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            fromJson(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(context: Context, entry: HistoryEntry) {
        save(context, (listOf(entry) + load(context)).take(MAX_ENTRIES))
        prune(context)
    }

    @Synchronized
    fun update(context: Context, entry: HistoryEntry) {
        save(context, load(context).map { if (it.id == entry.id) entry else it })
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val (gone, kept) = load(context).partition { it.id == id }
        gone.forEach { it.audioPath?.let(::File)?.delete() }
        save(context, kept)
    }

    @Synchronized
    fun clear(context: Context) {
        save(context, emptyList())
        CaptureService.takesDir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Drops expired audio and deletes take files that no entry refers to (cancelled recordings,
     * incognito takes, entries pushed out by [MAX_ENTRIES]).
     */
    @Synchronized
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        val entries = load(context)
        val pruned = expireAudio(entries, now)
        if (pruned != entries) save(context, pruned)
        val referenced = pruned.mapNotNull { it.audioPath }.toSet()
        CaptureService.takesDir(context).listFiles()
            ?.filter { it.absolutePath !in referenced && now - it.lastModified() > ORPHAN_GRACE_MS }
            ?.forEach { it.delete() }
    }

    /** Grace period, so a take that is still being recorded or processed is not deleted. */
    private const val ORPHAN_GRACE_MS = 10 * 60 * 1000L

    /** Entries whose audio is older than [AUDIO_TTL_MS] lose their audio path. */
    fun expireAudio(entries: List<HistoryEntry>, now: Long): List<HistoryEntry> = entries.map {
        if (it.audioPath != null && now - it.createdAt > AUDIO_TTL_MS) it.copy(audioPath = null) else it
    }

    fun promptHash(prompt: String): String =
        MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        File(context.filesDir, FILE).writeText(toJson(entries))
    }

    fun toJson(entries: List<HistoryEntry>): String = JSONArray(entries.map { e ->
        JSONObject()
            .put("session_id", e.id)
            .put("created_at", e.createdAt)
            .put("status", e.status.name)
            .putOpt("raw_transcript", e.raw)
            .putOpt("final_transcript", e.finalText)
            .putOpt("detail", e.detail)
            .putOpt("audio_path", e.audioPath)
            .putOpt("cleanup_prompt_hash", e.promptHash)
    }).toString()

    fun fromJson(json: String): List<HistoryEntry> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            fun opt(name: String) = if (o.isNull(name)) null else o.optString(name)
            HistoryEntry(
                id = o.getString("session_id"),
                createdAt = o.getLong("created_at"),
                status = HistoryStatus.valueOf(o.getString("status")),
                raw = opt("raw_transcript"),
                finalText = opt("final_transcript"),
                detail = opt("detail"),
                audioPath = opt("audio_path"),
                promptHash = opt("cleanup_prompt_hash"),
            )
        }
    }
}

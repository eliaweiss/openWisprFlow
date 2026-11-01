package app.openwisprflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A personal dictionary term (HLD section 6). [canonical] is sent as custom vocabulary to the
 * speech model. [aliases] are ways the term tends to be misheard; they go to cleanup (step 6) as
 * spelling hints. Neither is applied as a find-and-replace.
 */
data class DictionaryEntry(val canonical: String, val aliases: List<String> = emptyList())

object Dictionary {

    private const val FILE = "dictionary.json"

    /**
     * Parses the setup screen's text form: one term per line, with optional aliases after "=",
     * separated by commas. "NablaLabs = nabla labs, nabla lab". Blank lines and duplicates are dropped.
     */
    fun parse(text: String): List<DictionaryEntry> {
        val seen = mutableSetOf<String>()
        return text.lines().mapNotNull { line ->
            val canonical = line.substringBefore('=').trim()
            if (canonical.isEmpty() || !seen.add(canonical.lowercase())) return@mapNotNull null
            val aliases = if ('=' in line) {
                line.substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            DictionaryEntry(canonical, aliases)
        }
    }

    fun format(entries: List<DictionaryEntry>): String = entries.joinToString("\n") { entry ->
        if (entry.aliases.isEmpty()) entry.canonical else "${entry.canonical} = ${entry.aliases.joinToString(", ")}"
    }

    fun toJson(entries: List<DictionaryEntry>): String = JSONArray(
        entries.map { JSONObject().put("canonical_text", it.canonical).put("aliases", JSONArray(it.aliases)) },
    ).toString()

    fun fromJson(json: String): List<DictionaryEntry> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val aliases = o.optJSONArray("aliases")
            DictionaryEntry(
                canonical = o.getString("canonical_text"),
                aliases = if (aliases == null) emptyList() else (0 until aliases.length()).map(aliases::getString),
            )
        }
    }

    fun load(context: Context): List<DictionaryEntry> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            fromJson(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, entries: List<DictionaryEntry>) {
        File(context.filesDir, FILE).writeText(toJson(entries))
    }
}

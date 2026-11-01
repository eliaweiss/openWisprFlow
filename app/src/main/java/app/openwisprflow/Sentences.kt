package app.openwisprflow

/**
 * Sentence splitting for read-aloud, ported from pocket-speak. The text is lightly cleaned
 * (markdown, URLs, hard-wrapped lines), then split into sentences that are spoken one at a time.
 * The shown text keeps each sentence's original wording; only what is sent to speech is adjusted
 * by [speakable].
 */
object Sentences {

    /** The cleaned text to show, its sentences, and where each sentence sits in that text. */
    data class Split(val text: String, val sentences: List<String>, val ranges: List<IntRange>)

    /**
     * Sentence ends: ".", "!", "?", ":", ";", ")", "]" only when whitespace follows (optionally
     * after a closing quote), so file names, URLs, decimals and times ("main.py", "3.14", "12:30")
     * stay whole. CJK "。", "！", "？" end a sentence anywhere. Tabs and newlines always separate.
     */
    private val SPLIT = Regex("(?<=[.!?:;)\\]])\\s+|(?<=[.!?:;][\"'”’])\\s+|(?<=[。！？])|[\\t\\n]+")

    fun split(raw: String): Split {
        var text = stripUrls(cleanMarkdown(raw))
        // Blank lines always separate paragraphs. A single newline is either a visual wrap
        // (joined) or a title or heading (kept, so it gets its own utterance and a pause).
        text = SINGLE_NEWLINE.replace(text) { m ->
            val next = text.substring(m.range.last + 1).firstOrNull { it.isLetter() }
            if (next != null && next.isUpperCase()) "\n" else " "
        }
        text = text.replace('\t', ' ').trim()
        val sentences = SPLIT.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        val ranges = mutableListOf<IntRange>()
        var from = 0
        for (s in sentences) {
            val at = text.indexOf(s, from).takeIf { it >= 0 } ?: from
            ranges += at until (at + s.length).coerceAtMost(text.length)
            from = at + s.length
        }
        return Split(text, sentences, ranges)
    }

    private val SINGLE_NEWLINE = Regex("(?<!\\n)\\n[ \\t]*(?=\\S)")

    /** Light markdown cleanup so docs read naturally; runs before wrapped lines are joined. */
    fun cleanMarkdown(text: String): String {
        val lines = mutableListOf<String>()
        for (line in text.replace("\r\n", "\n").split("\n")) {
            val stripped = line.trim()
            if (Regex("```\\w*").matches(stripped)) continue // Code fence: nothing to say.
            if (stripped.startsWith("#")) {
                // A heading keeps its own sentence instead of gluing onto the next paragraph.
                lines += line.replace(Regex("^(\\s*)#+\\s*"), "$1")
                lines += ""
                continue
            }
            // Bullets go; list numbers stay, so the listener hears where they are in the list.
            lines += line.replace(Regex("^\\s*[-*]\\s+"), "")
        }
        return lines.joinToString("\n")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace("**", "").replace("__", "").replace("`", "")
            .replace("#", "")
    }

    private const val URL_CHAR = "[^\\s<>\"'“”‘’()\\[\\]]"
    private const val URL_BODY = "(?:$URL_CHAR|\\($URL_CHAR*\\))*"
    private const val URL_START = "\\b(?:https?://|www\\.)"
    /** A URL wrapped in brackets or quotes goes with its wrapper, and a comma right after it. */
    private val WRAPPED_URL = Regex(
        "[(\\[<\"“'‘]\\s*<?\\s*$URL_START$URL_BODY\\s*>?\\s*[)\\]>\"”'’](?:[,;](?=\\s|$))?",
    )
    private val BARE_URL = Regex("$URL_START$URL_BODY(?<![.,;:!?])(?:[,;](?=\\s|$))?")

    /** Removes URLs so they are not read aloud, tidying spacing only where one was removed. */
    fun stripUrls(text: String): String {
        val withoutWrapped = WRAPPED_URL.replace(text, "")
        val stripped = BARE_URL.replace(withoutWrapped, "")
        if (stripped == text) return text
        return stripped
            .replace(Regex("\\(\\s*\\)|\\[\\s*]|<\\s*>"), "")
            .replace(Regex("[ \\t]+([,.;:!?])"), "$1")
            .replace(Regex("[,;:][ \\t]*(?=[.!?])"), "")
            .replace(Regex("(?m)[,;][ \\t]*$"), "")
            .replace(Regex("[ \\t]{2,}"), " ")
    }

    private val UUID = Regex(
        "(?<![0-9A-Za-z])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Za-z])",
    )
    private val NANOID = Regex("[A-Za-z0-9_-]{21}")
    private val WORD = Regex("[\\w-]+")
    private const val MAX_WORD = 15
    /** Where an identifier breaks into words: "-", "_" and camelCase boundaries. */
    private val IDENT_SPLIT = Regex("[-_]|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
    private val VOWEL = Regex("[aeiouy]", RegexOption.IGNORE_CASE)

    /**
     * The words actually sent to speech for one sentence: UUIDs and nanoids are dropped, long
     * identifiers keep only their short parts, and a dot inside a word ("main.py") is read "dot".
     */
    fun speakable(sentence: String): String {
        var t = UUID.replace(sentence, " ")
        t = WORD.replace(t) { speakableWord(it.value) }
        t = t.replace(Regex("\\(\\s*\\)|\\[\\s*]"), "")
            .replace(Regex("[ \\t]+([,.;:!?])"), "$1")
        t = Regex("(?<=\\w)\\.(?=\\w)").replace(t, " dot ")
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    private fun speakableWord(word: String): String {
        val parts = IDENT_SPLIT.split(word).filter { it.isNotEmpty() }
        // At least two ordinary words (3+ letters with a vowel) make an identifier readable.
        val wordy = parts.count { it.length >= 3 && it.all(Char::isLetter) && VOWEL.containsMatchIn(it) } >= 2
        if (!wordy && NANOID.matches(word) && Regex("[0-9A-Z_]").containsMatchIn(word)) return " "
        if (word.length <= MAX_WORD) return word
        val keep = if (wordy) parts else word.split(Regex("[-_]"))
        return keep.filter { it.isNotEmpty() && it.length <= MAX_WORD }.joinToString(" ")
    }
}

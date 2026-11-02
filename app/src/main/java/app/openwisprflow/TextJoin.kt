package app.openwisprflow

/**
 * The app's share of boundary handling (HLD section 4, Insertion): the joining space only.
 * Wording and casing belong to the transcript or cleanup.
 */
object TextJoin {

    private const val NO_SPACE_BEFORE = ".,!?;:)]}'\"…"
    private val LINE_BREAKS = Regex("""\s*\n\s*""")

    /** A single-line field can not hold line breaks, and some drop them so sentences run together. */
    fun forField(text: String, multiLine: Boolean): String =
        if (multiLine) text else text.replace(LINE_BREAKS, " ")

    /**
     * Trims the dictated text and adds one leading space when it would otherwise run into the
     * word before the cursor. With no known text before the cursor, nothing is added.
     */
    fun forInsertion(before: String?, dictated: String): String {
        val text = dictated.trim()
        if (text.isEmpty() || before.isNullOrEmpty()) return text
        val last = before.last()
        val needsSpace = !last.isWhitespace() && last !in "([{" && text.first() !in NO_SPACE_BEFORE
        return if (needsSpace) " $text" else text
    }
}

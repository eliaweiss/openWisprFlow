package app.openwisprflow

/**
 * Builds the short custom-vocabulary list for the speech model (HLD section 4): the personal
 * dictionary first, then name-like terms from the text nearest the cursor. Only these terms are
 * sent, never the surrounding text itself.
 */
object Vocabulary {

    /** Google accepts up to 1,000 phrases; quality is tuned with a much shorter list. */
    const val CAP = 100

    private val TOKEN = Regex("""\p{L}[\p{L}\p{N}'’._-]*""")
    private const val TRIM = ".'’_-"

    /**
     * Common words that are capitalized only because they start a sentence or a label. Kept short:
     * a stray common word in the list costs little, a missing name costs a misspelling.
     */
    private val COMMON = setOf(
        "a", "about", "after", "all", "also", "an", "and", "any", "are", "as", "at", "back", "be", "but", "by",
        "can", "cancel", "close", "copy", "dear", "do", "done", "edit", "for", "from", "good", "have", "he",
        "hello", "her", "here", "hey", "hi", "his", "home", "how", "i", "if", "in", "is", "it", "its", "just",
        "let", "me", "message", "more", "my", "new", "no", "not", "now", "of", "ok", "okay", "on", "or", "our",
        "please", "reply", "search", "see", "send", "she", "so", "thanks", "that", "the", "their", "then",
        "there", "they", "this", "to", "today", "type", "up", "we", "what", "when", "where", "who", "why",
        "will", "with", "yes", "you", "your",
    )

    /** Name-like terms in [text], in order: capitalized words, inner capitals (NablaLabs), letter-digit mixes. */
    fun candidates(text: String): List<String> = TOKEN.findAll(text)
        .map { it.value.trim { c -> c in TRIM } }
        .filter { looksLikeTerm(it) }
        .toList()

    private fun looksLikeTerm(word: String): Boolean {
        if (word.length < 2 || word.lowercase() in COMMON) return false
        val innerCapital = word.drop(1).any { it.isUpperCase() }
        val hasDigit = word.any { it.isDigit() }
        return word.first().isUpperCase() || innerCapital || hasDigit
    }

    /** Dictionary terms first, then candidates from [nearby] (nearest first), deduplicated ignoring case. */
    fun build(dictionary: List<DictionaryEntry>, nearby: List<String>, cap: Int = CAP): List<String> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        fun add(term: String) {
            if (result.size < cap && seen.add(term.lowercase())) result += term
        }
        dictionary.forEach { add(it.canonical) }
        nearby.asSequence().flatMap { candidates(it) }.forEach(::add)
        return result
    }
}

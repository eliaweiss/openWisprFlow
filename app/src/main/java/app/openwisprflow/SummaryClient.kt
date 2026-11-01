package app.openwisprflow

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A spoken summary from Inception Mercury 3 (inception/mercury-3) through AI Gateway's
 * Chat Completions endpoint, ported from pocket-speak's summarize command. Mercury 3 is a
 * diffusion model: with reasoning effort "none" it answers in about a second. [cancel] aborts the
 * request from another thread.
 */
class SummaryCall(private val apiKey: String, private val text: String) {

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun execute(): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        connection = conn
        if (cancelled) throw IOException("cancelled")
        try {
            conn.outputStream.use { it.write(requestJson(text).toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }
                throw GatewayException(status, TranscriptionCall.errorMessage(status, body))
            }
            return parseSummary(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
            connection = null
        }
    }

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    companion object {
        const val ENDPOINT = "https://ai-gateway.vercel.sh/v1/chat/completions"
        const val MODEL = "inception/mercury-3"
        /** More than this is never sent (cost, and a summary of a book is not a summary). */
        const val MAX_INPUT_CHARS = 100_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 120_000

        /** Spoken summaries: the listener hears this, so no markdown, no lists, no throat-clearing. */
        val SYSTEM_PROMPT = """
            You summarise text for a reader who skims it on screen and may also hear it
            read aloud.

            Rules:
            - Two or three short paragraphs, separated by exactly one blank line.
            - Each paragraph is 1 to 3 sentences of plain prose; 110 words in total at most.
            - No markdown, no bullet points, no headings, no URLs, no code, no lists.
            - Never write an identifier longer than 4 characters: no commit hashes
              (ced3e75), ticket or issue keys (VOICE-48213, JIRA-1234), UUIDs, request,
              job or order IDs. Count the whole ID, letters, digits and dashes. Name the
              thing instead ("a commit", "the ticket", "a failed request"). Short ones
              like #812 may stay. Amounts, dates, versions and other meaningful numbers
              are not identifiers; keep them.
            - First paragraph: what the text is about and the outcome. Then the key points,
              and any decision, number, deadline or next step that matters.
            - Always write in English, whatever the language of the source text.
            - Do not start with "This text" or "The article"; start with the substance.
        """.trimIndent()

        fun requestJson(text: String): String = JSONObject()
            .put("model", MODEL)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", text.take(MAX_INPUT_CHARS))),
            )
            // With effort "none" no tokens go to hidden reasoning; the budget must still fit the
            // summary, because a reasoning model that runs out of tokens returns no content.
            .put("max_tokens", 700)
            .put("reasoning", JSONObject().put("effort", "none"))
            .toString()

        fun parseSummary(body: String): String {
            val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            val content = choice.getJSONObject("message").optString("content").trim()
            val summary = stripIds(content).trim()
            if (summary.isEmpty()) {
                throw IOException("the model returned no summary (finish_reason=${choice.optString("finish_reason")})")
            }
            return summary
        }

        private val UUID = Regex(
            "(?<![0-9A-Za-z])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Za-z])",
        )
        /** Commit hashes: 7-40 hex characters with at least one digit and one letter. */
        private val HASH = Regex("(?<![\\w-])(?=[0-9a-f]*\\d)(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}(?![\\w-])")
        /**
         * Ticket keys and mixed tokens with five digits or more: VOICE-48213, INC0042117. A
         * sentence-ending period may follow; a period that continues a token (v2.4) may not.
         */
        private val KEY = Regex(
            "(?<![\\w.-])(?=[\\w-]*[A-Za-z])(?=(?:[\\w-]*?\\d){5})[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?![\\w-]|\\.[\\w-])",
        )
        /** Bare long numbers after an ID word ("job 99812345", "issue #20931"). */
        private val LABELED_NUMBER = Regex(
            "(?i)\\b(commit|job|issue|ticket|order|request|incident|build|run|case|id|pr|pull request|invoice|transaction)(\\s+)#?\\d{5,}\\b",
        )

        /**
         * Safety net for the prompt's "no identifiers" rule, which Mercury does not always follow:
         * removes identifiers longer than 4 characters. Amounts (1,250), dates, versions (v2.4)
         * and short references (#812) are kept, and so are paragraph breaks.
         */
        fun stripIds(text: String): String {
            var t = UUID.replace(text, "")
            t = HASH.replace(t, "")
            t = KEY.replace(t) { if (it.value.length <= 4) it.value else "" }
            t = LABELED_NUMBER.replace(t) { it.groupValues[1] }
            t = Regex("(?<!\\w)#\\d{5,}\\b").replace(t, "")
            t = Regex("\\(\\s*\\)|\\[\\s*]").replace(t, "")
            t = Regex("[ \\t]+([,.;:!?])").replace(t, "$1")
            return Regex("[ \\t]{2,}").replace(t, " ")
        }
    }
}

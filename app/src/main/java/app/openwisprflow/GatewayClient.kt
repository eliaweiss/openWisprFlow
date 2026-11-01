package app.openwisprflow

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** A transcript returned by AI Gateway. */
data class Transcript(val text: String, val language: String?, val durationSeconds: Double?)

/** A non-2xx reply from AI Gateway. The message never contains the API key. */
class GatewayException(val status: Int, message: String) : IOException(message)

/**
 * One transcription request to AI Gateway's REST endpoint (HLD section 5, Transcription). The
 * WAV is base64-encoded straight from the file into the request body, so a long take is never
 * held in memory as one string. [cancel] aborts the request from another thread.
 */
class TranscriptionCall(
    private val apiKey: String,
    private val wav: File,
    private val options: TranscriptionOptions,
) {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun execute(): Transcript {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setChunkedStreamingMode(0)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("ai-gateway-protocol-version", "0.0.1")
            setRequestProperty("ai-transcription-model-specification-version", "4")
            setRequestProperty("ai-model-id", MODEL)
        }
        connection = conn
        if (cancelled) throw IOException("cancelled")
        try {
            conn.outputStream.buffered().use { out ->
                out.write(options.bodyPrefix())
                Base64.getEncoder().wrap(KeepOpen(out)).use { encoder ->
                    wav.inputStream().use { it.copyTo(encoder) }
                }
                out.write(options.bodySuffix())
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                throw GatewayException(status, errorMessage(status, conn.errorStream?.readText()))
            }
            return parseTranscript(conn.inputStream.readText())
        } finally {
            conn.disconnect()
            connection = null
        }
    }

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    /** Keeps the request stream open when the base64 encoder is closed to flush its last block. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    companion object {
        const val ENDPOINT = "https://ai-gateway.vercel.sh/v4/ai/transcription-model"
        const val MODEL = "google/gemini-3.5-transcribe"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 90_000

        fun parseTranscript(body: String): Transcript {
            val json = JSONObject(body)
            return Transcript(
                text = json.optString("text", ""),
                language = json.optString("language").takeIf { it.isNotEmpty() },
                durationSeconds = json.optDouble("durationInSeconds").takeIf { !it.isNaN() },
            )
        }

        /** A short, readable error: the gateway's own message when the body has one. */
        fun errorMessage(status: Int, body: String?): String {
            val detail = body?.let {
                try {
                    val json = JSONObject(it)
                    json.optJSONObject("error")?.optString("message")?.takeIf { m -> m.isNotEmpty() }
                        ?: json.optString("message").takeIf { m -> m.isNotEmpty() }
                } catch (e: Exception) {
                    null
                }
            } ?: body?.take(200)?.takeIf { it.isNotBlank() }
            return if (detail != null) "HTTP $status: $detail" else "HTTP $status"
        }

        private fun InputStream.readText() = bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

/** Google provider options for the transcription call (HLD section 5). */
data class TranscriptionOptions(
    val mode: String,
    val customVocabulary: List<String> = emptyList(),
    val languageCodes: List<String> = emptyList(),
) {
    fun providerOptionsJson(): String {
        val google = JSONObject().put("mode", mode)
        if (customVocabulary.isNotEmpty()) google.put("customVocabulary", JSONArray(customVocabulary))
        if (languageCodes.isNotEmpty()) google.put("languageCodes", JSONArray(languageCodes))
        return JSONObject().put("google", google).toString()
    }

    /** The JSON body up to the opening quote of the base64 audio. */
    fun bodyPrefix(): ByteArray = "{\"audio\":\"".toByteArray(Charsets.UTF_8)

    /** The JSON body after the audio. */
    fun bodySuffix(): ByteArray =
        "\",\"mediaType\":\"audio/wav\",\"providerOptions\":${providerOptionsJson()}}".toByteArray(Charsets.UTF_8)

    companion object {
        const val SMART = "SMART"
        const val VERBATIM = "VERBATIM"
    }
}

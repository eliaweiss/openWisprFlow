package app.openwisprflow

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64

/** A transcript returned by AI Gateway. */
data class Transcript(val text: String, val language: String?, val durationSeconds: Double?)

/** A non-2xx reply from AI Gateway. The message never contains the API key. */
class GatewayException(val status: Int, message: String, val retryAfterMs: Long? = null) : IOException(message)

/** A Retry-After header in seconds; the date form is ignored and the normal backoff applies. */
fun parseRetryAfterMs(header: String?): Long? = header?.trim()?.toLongOrNull()?.times(1_000)

/**
 * One transcription request to AI Gateway's REST endpoint (HLD section 5, Transcription). The
 * WAV is base64-encoded straight from the file into the request body, so a long take is never
 * held in memory as one string. Retried by [RemoteCall]; [cancel] aborts it from another thread.
 */
class TranscriptionCall(
    private val apiKey: String,
    private val wav: File,
    private val options: TranscriptionOptions,
    onRetry: RetryListener? = null,
) {
    private val policy = policyFor(wav.length())
    private val remote = RemoteCall("transcribe", policy, onRetry)

    fun execute(): Transcript = remote.run(::attempt)

    fun cancel() = remote.cancel()

    private fun attempt(attempt: RemoteCall.Attempt): Transcript {
        AppLog.d("openWisprFlow", "transcribe: uploading ${wav.length()} bytes")
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("ai-gateway-protocol-version", "0.0.1")
            .header("ai-transcription-model-specification-version", "4")
            .header("ai-model-id", MODEL)
            .post(AudioBody(wav, options))
            .build()
        return parseTranscript(GatewayHttp.fetch(attempt, "transcribe", policy, request))
    }

    /** The JSON body, with the WAV base64-encoded from the file as it is sent; it can be sent again. */
    internal class AudioBody(private val wav: File, private val options: TranscriptionOptions) : RequestBody() {
        private val prefix = options.bodyPrefix()
        private val suffix = options.bodySuffix()

        override fun contentType(): MediaType = GatewayHttp.JSON

        override fun contentLength(): Long = prefix.size + (wav.length() + 2) / 3 * 4 + suffix.size

        override fun writeTo(sink: BufferedSink) {
            sink.write(prefix)
            Base64.getEncoder().wrap(KeepOpen(sink.outputStream())).use { encoder ->
                wav.inputStream().use { it.copyTo(encoder) }
            }
            sink.write(suffix)
        }
    }

    /** Keeps the request stream open when the base64 encoder is closed to flush its last block. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    companion object {
        const val ENDPOINT = "https://ai-gateway.vercel.sh/v4/ai/transcription-model"
        const val MODEL = "google/gemini-3.5-transcribe"
        /**
         * The transcript arrives whole, so its first byte comes only once the model is done: about
         * 3-4 s for a short take. A reply that has not started 10 s after the upload is stuck.
         */
        private const val FIRST_BYTE_TIMEOUT_MS = 10_000L
        /** Connect and TLS; the upload gets more time on top, at a slow 1 Mbit/s. */
        private const val SEND_BASE_MS = 5_000L
        private const val UPLOAD_BYTES_PER_MS = 125L

        /**
         * Limits for a take of [wavBytes]. The body is base64, a third larger than the WAV. With
         * cleanup's limits this stays inside the 3 minutes of CaptureService's foreground hold.
         */
        fun policyFor(wavBytes: Long): RemotePolicy {
            val send = SEND_BASE_MS + wavBytes * 4 / 3 / UPLOAD_BYTES_PER_MS
            return RemotePolicy(
                attemptTimeoutMs = send + FIRST_BYTE_TIMEOUT_MS + 5_000,
                maxAttempts = 3,
                totalTimeoutMs = 130_000,
                sendTimeoutMs = send,
                firstByteTimeoutMs = FIRST_BYTE_TIMEOUT_MS,
            )
        }

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

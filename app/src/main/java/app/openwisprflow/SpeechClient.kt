package app.openwisprflow

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * One text-to-speech request to AI Gateway's REST endpoint (POST /v4/ai/speech-model), the
 * format @ai-sdk/gateway's speech model uses: the model id in a header, {text, voice,
 * outputFormat} in the body, and base64 audio in the JSON reply. Gemini 3.8 TTS returns WAV with
 * its own header. [cancel] aborts the request from another thread.
 */
class SpeechCall(
    private val apiKey: String,
    private val text: String,
    private val voice: String = DEFAULT_VOICE,
) {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    /** Returns the audio bytes (a WAV file). */
    fun execute(): ByteArray {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("ai-gateway-protocol-version", "0.0.1")
            setRequestProperty("ai-speech-model-specification-version", "4")
            setRequestProperty("ai-model-id", MODEL)
        }
        connection = conn
        if (cancelled) throw IOException("cancelled")
        try {
            conn.outputStream.use { it.write(requestJson(text, voice).toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }
                throw GatewayException(status, TranscriptionCall.errorMessage(status, body))
            }
            return parseAudio(conn.inputStream.bufferedReader().use { it.readText() })
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
        const val ENDPOINT = "https://ai-gateway.vercel.sh/v4/ai/speech-model"
        const val MODEL = "google/gemini-3.8-flash-lite-tts"
        const val DEFAULT_VOICE = "Kore"
        /** The model's context is 8,192 tokens; longer selections are cut, and the user is told. */
        const val MAX_CHARS = 5_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000

        fun requestJson(text: String, voice: String): String = JSONObject()
            .put("text", text)
            .put("voice", voice)
            .put("outputFormat", "wav")
            // Google speech models ignore speed and language; pacing goes in instructions.
            .put("instructions", "Read the text aloud clearly, at a natural pace.")
            .toString()

        fun parseAudio(body: String): ByteArray {
            val audio = JSONObject(body).optString("audio")
            if (audio.isEmpty()) throw IOException("no audio in the reply")
            return Base64.getMimeDecoder().decode(audio)
        }
    }
}

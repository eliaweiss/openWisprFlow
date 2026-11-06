package app.openwisprflow

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    onRetry: RetryListener? = null,
) {
    private val remote = RemoteCall("speech", POLICY, onRetry)

    /** Returns the audio bytes (a WAV file). */
    fun execute(): ByteArray = remote.run(::attempt)

    fun cancel() = remote.cancel()

    private fun attempt(attempt: RemoteCall.Attempt): ByteArray {
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("ai-gateway-protocol-version", "0.0.1")
            .header("ai-speech-model-specification-version", "4")
            .header("ai-model-id", MODEL)
            .post(requestJson(text, voice).toRequestBody(GatewayHttp.JSON))
            .build()
        return parseAudio(GatewayHttp.fetch(attempt, "speech", POLICY, request))
    }

    companion object {
        const val ENDPOINT = "https://ai-gateway.vercel.sh/v4/ai/speech-model"
        const val MODEL = "google/gemini-3.8-flash-lite-tts"
        const val DEFAULT_VOICE = "Kore"
        /** The model's context is 8,192 tokens; longer selections are cut, and the user is told. */
        const val MAX_CHARS = 5_000
        /**
         * The audio arrives whole, so the reply starts only once it is generated: 2-5 s for one
         * sentence. A reply that has not started 10 s after the request is stuck.
         */
        private val POLICY = RemotePolicy(
            attemptTimeoutMs = 25_000,
            maxAttempts = 3,
            totalTimeoutMs = 60_000,
            sendTimeoutMs = 5_000,
            firstByteTimeoutMs = 10_000,
        )

        fun requestJson(text: String, voice: String): String = JSONObject()
            .put("text", text)
            .put("voice", voice)
            .put("outputFormat", "wav")
            // Google speech models ignore speed and language; pacing goes in instructions.
            .put("instructions", "Read the text aloud clearly, at a natural pace.")
            .toString()

        fun parseAudio(body: String): ByteArray {
            val audio = JSONObject(body).optString("audio")
            if (audio.isEmpty()) throw BadReplyException("no audio in the reply")
            return Base64.getMimeDecoder().decode(audio)
        }
    }
}

package app.openwisprflow

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live transcription over AI Gateway's transcription WebSocket (HLD section 5, Streaming), the
 * protocol of @ai-sdk/gateway's GatewayTranscriptionModel.doStream:
 *
 * - connect to wss://ai-gateway.vercel.sh/v4/ai/transcription-model?ai-model-id=<model>, with the
 *   API key in the subprotocols "ai-gateway-transcription.v1" and "ai-gateway-auth.<key>";
 * - send one text frame {"type":"transcription-stream.start", inputAudioFormat, providerOptions};
 * - send raw 16 kHz 16-bit mono PCM as binary frames;
 * - send {"type":"transcription-stream.audio-done"};
 * - receive transcript-partial, transcript-delta and transcript-final parts, then a terminal
 *   finish part, then a normal close. An error part, or a close without finish, is a failure.
 *
 * Audio sent before [connect] is held and flushed after the start frame, so recording can begin
 * while the vocabulary is still being built. Callbacks arrive on OkHttp's thread.
 */
class LiveTranscriber(private val apiKey: String, private val listener: Listener) {

    interface Listener {
        fun onLiveText(text: String)
        fun onLiveFinished(transcript: Transcript)
        fun onLiveFailed(error: Throwable)
    }

    private val lock = Any()
    private var socket: WebSocket? = null
    private val pending = mutableListOf<ByteArray>()
    private var pendingBytes = 0L
    private var audioDone = false
    private val ended = AtomicBoolean(false)
    private val text = LiveText()

    /** Opens the socket and sends the start frame, then any audio recorded so far. */
    fun connect(options: TranscriptionOptions) {
        val request = Request.Builder()
            .url("$ENDPOINT?ai-model-id=$MODEL")
            .header("Sec-WebSocket-Protocol", "$PROTOCOL_MARKER, $AUTH_PREFIX$apiKey")
            .header("ai-transcription-model-specification-version", "4")
            .header("ai-model-id", MODEL)
            .build()
        val ws = client.newWebSocket(request, SocketListener())
        synchronized(lock) {
            if (ended.get()) {
                ws.cancel()
                return
            }
            // OkHttp queues frames until the handshake completes, in order.
            ws.send(startFrame(options))
            pending.forEach { ws.send(it.toByteString()) }
            pending.clear()
            if (audioDone) ws.send(AUDIO_DONE_FRAME)
            socket = ws
        }
    }

    /** Queues one chunk of PCM. Called from the capture thread. */
    fun sendAudio(pcm: ByteArray) {
        synchronized(lock) {
            if (ended.get() || audioDone) return
            val ws = socket
            if (ws != null) {
                ws.send(pcm.toByteString())
            } else if (pendingBytes < MAX_PENDING_BYTES) {
                pending += pcm
                pendingBytes += pcm.size
            } else {
                fail(IOException("live transcription did not connect in time"))
            }
        }
    }

    /** Signals the end of audio; the server then sends the final transcript. */
    fun finishAudio() {
        synchronized(lock) {
            if (ended.get() || audioDone) return
            audioDone = true
            socket?.send(AUDIO_DONE_FRAME)
        }
    }

    fun cancel() {
        if (ended.compareAndSet(false, true)) socket?.cancel()
    }

    private fun fail(error: Throwable) {
        if (ended.compareAndSet(false, true)) {
            socket?.cancel()
            listener.onLiveFailed(error)
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (ended.get()) return
            val part = try {
                JSONObject(text)
            } catch (e: Exception) {
                return // Unknown or malformed parts are ignored, as the SDK does.
            }
            when (part.optString("type")) {
                "transcript-partial" -> update { it.partial(part.optString("id"), part.optString("text")) }
                "transcript-delta" -> update { it.delta(part.optString("id"), part.optString("delta")) }
                "transcript-final" -> update { it.final(part.optString("id"), part.optString("text")) }
                "finish" -> if (ended.compareAndSet(false, true)) {
                    listener.onLiveFinished(TranscriptionCall.parseTranscript(text))
                    webSocket.close(NORMAL_CLOSE, null)
                }
                "error" -> fail(IOException(errorMessage(part)))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            fail(IOException("live transcription closed before finishing ($code)"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code
            fail(if (status != null) GatewayException(status, "HTTP $status: ${t.message ?: "handshake failed"}") else t)
        }
    }

    private fun update(change: (LiveText) -> Unit) {
        val shown = synchronized(text) {
            change(text)
            text.display()
        }
        listener.onLiveText(shown)
    }

    companion object {
        const val ENDPOINT = "wss://ai-gateway.vercel.sh/v4/ai/transcription-model"
        const val MODEL = "google/gemini-3.5-transcribe-live"
        private const val PROTOCOL_MARKER = "ai-gateway-transcription.v1"
        private const val AUTH_PREFIX = "ai-gateway-auth."
        private const val AUDIO_DONE_FRAME = """{"type":"transcription-stream.audio-done"}"""
        private const val NORMAL_CLOSE = 1000
        /** About 30 seconds of 16 kHz PCM; the take on disk stays the source of truth. */
        private const val MAX_PENDING_BYTES = 30L * AudioCapture.SAMPLE_RATE * 2

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        fun startFrame(options: TranscriptionOptions): String = JSONObject()
            .put("type", "transcription-stream.start")
            .put("inputAudioFormat", JSONObject().put("type", "audio/pcm").put("rate", AudioCapture.SAMPLE_RATE))
            .put("providerOptions", JSONObject(options.providerOptionsJson()))
            .toString()

        fun errorMessage(part: JSONObject): String {
            val error = part.opt("error")
            return when (error) {
                is JSONObject -> error.optString("message").ifEmpty { error.toString() }
                null -> "live transcription error"
                else -> error.toString()
            }
        }
    }
}

/**
 * The live text shown while the user talks. Each segment has settled text (deltas append to it, a
 * final replaces it) and in-progress text (a partial, revised until something settles). A final
 * closes its segment, so a later part with the same id, or with no id at all, starts a new one.
 */
class LiveText {
    private class Segment(var settled: String = "", var interim: String = "")

    private val order = mutableListOf<Segment>()
    private val open = mutableMapOf<String, Segment>()

    private fun segment(id: String) = open.getOrPut(id) { Segment().also { order += it } }

    fun partial(id: String, text: String) {
        segment(id).interim = text
    }

    fun delta(id: String, delta: String) {
        val s = segment(id)
        s.settled += delta
        s.interim = ""
    }

    fun final(id: String, text: String) {
        val s = segment(id)
        s.settled = text
        s.interim = ""
        open.remove(id)
    }

    fun display(): String = order.flatMap { listOf(it.settled, it.interim) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

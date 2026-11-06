package app.openwisprflow

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The one HTTP client for AI Gateway requests, and how a request runs inside a [RemoteCall.Attempt].
 *
 * Why the pool is short-lived: on the phone's cellular network a kept-alive connection that sits
 * idle for about a minute goes dead without either side noticing. Writes into it still succeed,
 * the kernel retransmits them, and no reply ever comes (seen in /proc/net/tcp: bytes stuck in
 * the send queue, retransmit count rising). Each request that picks such a connection hangs until
 * its first-byte limit, and with three dead connections left by the parallel speech requests, a
 * summary's three attempts each took one and all failed. Connections idle for less than ~50 s
 * were fine. So idle connections are closed after [IDLE_KEEP_ALIVE_S], and after a failed attempt
 * every idle connection is closed, so the retry starts on a new one.
 */
object GatewayHttp {

    /** Well under the ~1 minute after which idle connections were found dead. */
    const val IDLE_KEEP_ALIVE_S = 30L

    val JSON = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_KEEP_ALIVE_S, TimeUnit.SECONDS))
        .build()

    private const val MAX_IDLE_CONNECTIONS = 5
    private const val TAG = "openWisprFlow"

    /** Closes every idle connection, so the next request opens a new one. */
    fun dropIdleConnections() = client.connectionPool.evictAll()

    /**
     * Runs [request] as part of [attempt]: socket limits from [policy], the attempt's stages
     * reported (connection, request sent, reply started), cancelled when the attempt is aborted.
     * Returns the body of a 2xx reply; anything else is a [GatewayException].
     */
    fun fetch(attempt: RemoteCall.Attempt, label: String, policy: RemotePolicy, request: Request): String {
        val send = policy.sendTimeoutMs ?: policy.attemptTimeoutMs
        val call = client.newBuilder()
            .connectTimeout(send, TimeUnit.MILLISECONDS)
            .writeTimeout(send, TimeUnit.MILLISECONDS)
            // Also the longest silence allowed while the reply streams in.
            .readTimeout(policy.firstByteTimeoutMs ?: policy.attemptTimeoutMs, TimeUnit.MILLISECONDS)
            .eventListener(Stages(attempt))
            .build()
            .newCall(request)
        attempt.onAbort(call::cancel)
        call.execute().use { response -> return body(label, response) }
    }

    private fun body(label: String, response: Response): String {
        AppLog.d(TAG, "$label: HTTP ${response.code} ${response.protocol}")
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw GatewayException(
                response.code,
                TranscriptionCall.errorMessage(response.code, text.takeIf { it.isNotEmpty() }),
                parseRetryAfterMs(response.header("Retry-After")),
            )
        }
        return text
    }

    /** Tells the attempt where the request is, from OkHttp's own events. */
    private class Stages(private val attempt: RemoteCall.Attempt) : EventListener() {
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
            attempt.newConnection()

        override fun requestHeadersEnd(call: Call, request: Request) {
            if (request.body == null) attempt.requestSent()
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) = attempt.requestSent()

        override fun responseHeadersEnd(call: Call, response: Response) = attempt.replyStarted()
    }
}

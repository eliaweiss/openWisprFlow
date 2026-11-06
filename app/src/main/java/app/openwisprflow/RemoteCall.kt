package app.openwisprflow

import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Why a remote call failed, and whether trying again can help. */
enum class RemoteFailureKind(val retryable: Boolean) {
    CANCELLED(false),
    OFFLINE(true),
    TIMEOUT(true),
    CONNECTION(true),
    AUTH(false),
    RATE_LIMITED(true),
    SERVER(true),
    REQUEST(false),
    BAD_REPLY(true),
    REJECTED(false),
    INTERNAL(false),
}

/** The final failure of a [RemoteCall]. The message is written for the user, without a final period. */
class RemoteException(
    val kind: RemoteFailureKind,
    val status: Int?,
    val attempts: Int,
    message: String,
    cause: Throwable?,
) : IOException(message, cause)

/** The reply arrived but could not be used; another attempt may do better. */
class BadReplyException(message: String) : IOException(message)

/** The reply was valid but its content was refused by a local check; not worth another attempt. */
class OutputRejectedException(message: String) : IOException(message)

/** One attempt ran past one of its time limits; the message says which. */
class AttemptTimeoutException(message: String) : IOException(message)

/**
 * Time limits and attempts for one kind of call. [totalTimeoutMs] includes the waits between
 * attempts.
 *
 * An attempt that goes quiet is a failed attempt, not a slow one, so besides [attemptTimeoutMs]
 * each attempt has two stage limits (null: none):
 * - [sendTimeoutMs]: from the start of the attempt (DNS, connect, TLS, upload) until the request
 *   is fully sent ([RemoteCall.Attempt.requestSent]);
 * - [firstByteTimeoutMs]: from then until the reply starts ([RemoteCall.Attempt.replyStarted]).
 */
class RemotePolicy(
    val attemptTimeoutMs: Long,
    val maxAttempts: Int,
    val totalTimeoutMs: Long,
    val backoffBaseMs: Long = 1_000,
    val sendTimeoutMs: Long? = null,
    val firstByteTimeoutMs: Long? = null,
)

/** Told before each new attempt, on a background thread. [reason] is written for the user. */
fun interface RetryListener {
    fun onRetry(nextAttempt: Int, maxAttempts: Int, reason: String)
}

/**
 * Runs a gateway request with a hard time limit on every attempt and on the whole call, retries
 * what can be retried, and logs each step (never the text or the key).
 *
 * Socket timeouts alone do not bound a request: they restart with every byte, and DNS has none.
 * So each attempt runs on its own thread and the caller stops waiting at the limit, even if that
 * thread is stuck. [cancel] takes effect immediately, from any thread.
 */
class RemoteCall(
    private val label: String,
    private val policy: RemotePolicy,
    private val onRetry: RetryListener? = null,
) {
    /**
     * One try. A request registers how to abort itself before it touches the network, then
     * reports [requestSent] and [replyStarted] so the stage limits of [RemotePolicy] apply.
     */
    class Attempt {
        @Volatile private var abort: (() -> Unit)? = null
        @Volatile private var aborted = false
        internal val beganAt = System.nanoTime()
        @Volatile internal var sentAt = 0L
            private set
        @Volatile internal var repliedAt = 0L
            private set
        @Volatile internal var newConnection = false
            private set

        /** The request opened a new connection rather than reusing a pooled one. */
        fun newConnection() {
            newConnection = true
        }

        /** The whole request has been written to the connection. */
        fun requestSent() {
            if (sentAt == 0L) sentAt = System.nanoTime()
        }

        /** The first byte of the reply (its status line) has arrived. */
        fun replyStarted() {
            requestSent()
            if (repliedAt == 0L) repliedAt = System.nanoTime()
        }

        fun onAbort(action: () -> Unit) {
            abort = action
            if (aborted) throw IOException("aborted")
        }

        internal fun abortNow() {
            aborted = true
            abort?.let { action -> pool.execute { runCatching(action) } }
        }
    }

    private val cancelSignal = CountDownLatch(1)
    @Volatile private var current: Attempt? = null
    @Volatile private var future: Future<*>? = null

    private val cancelled get() = cancelSignal.count == 0L

    fun <T> run(block: (Attempt) -> T): T {
        val began = System.nanoTime()
        AppLog.d(TAG, "$label: start, network ${NetworkStatus.describe()}")
        var attempt = 0
        while (true) {
            attempt++
            if (cancelled) throw failure(IOException("cancelled"), RemoteFailureKind.CANCELLED, attempt - 1)
            val limit = minOf(policy.attemptTimeoutMs, policy.totalTimeoutMs - elapsedMs(began)).coerceAtLeast(1)
            val attemptBegan = System.nanoTime()
            try {
                val value = runAttempt(block, limit)
                AppLog.d(TAG, "$label: ok on attempt $attempt/${policy.maxAttempts} in ${elapsedMs(attemptBegan)} ms, total ${elapsedMs(began)} ms${current?.timings().orEmpty()}")
                return value
            } catch (e: Exception) {
                val kind = if (cancelled) RemoteFailureKind.CANCELLED else classify(e)
                if (kind == RemoteFailureKind.CANCELLED) {
                    AppLog.d(TAG, "$label: cancelled after ${elapsedMs(began)} ms")
                    throw failure(e, kind, attempt)
                }
                AppLog.w(
                    TAG,
                    "$label: attempt $attempt/${policy.maxAttempts} failed after ${elapsedMs(attemptBegan)} ms: " +
                        "$kind ${e.javaClass.simpleName} ${loggedDetail(e)}${current?.timings().orEmpty()}, " +
                        "network ${NetworkStatus.describe()}",
                )
                // A connection that went quiet may have pooled siblings in the same state.
                if (kind == RemoteFailureKind.TIMEOUT || kind == RemoteFailureKind.CONNECTION) {
                    runCatching { GatewayHttp.dropIdleConnections() }
                }
                val wait = retryDelayMs(policy, attempt, (e as? GatewayException)?.retryAfterMs)
                val again = kind.retryable && attempt < policy.maxAttempts &&
                    elapsedMs(began) + wait < policy.totalTimeoutMs
                if (!again) {
                    AppLog.w(TAG, "$label: giving up after $attempt attempts and ${elapsedMs(began)} ms")
                    throw failure(e, kind, attempt)
                }
                AppLog.d(TAG, "$label: retrying in $wait ms")
                onRetry?.onRetry(attempt + 1, policy.maxAttempts, describe(kind, e, attempts = 1))
                if (cancelSignal.await(wait, TimeUnit.MILLISECONDS)) {
                    throw failure(e, RemoteFailureKind.CANCELLED, attempt)
                }
            }
        }
    }

    fun cancel() {
        cancelSignal.countDown()
        current?.abortNow()
        future?.cancel(true)
    }

    private fun <T> runAttempt(block: (Attempt) -> T, limitMs: Long): T {
        val attempt = Attempt()
        current = attempt
        val task = pool.submit<T> { block(attempt) }
        future = task
        if (cancelled) task.cancel(true)
        try {
            // The deadline moves as the attempt reports its stages, so it is checked in short steps.
            while (true) {
                val (deadline, reason) = deadline(attempt, limitMs)
                val left = deadline - System.nanoTime()
                if (left <= 0) {
                    attempt.abortNow()
                    task.cancel(true)
                    throw AttemptTimeoutException(reason)
                }
                try {
                    return task.get(minOf(left, STEP_NS), TimeUnit.NANOSECONDS)
                } catch (e: TimeoutException) {
                    // Not done yet; check the deadline again.
                }
            }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            attempt.abortNow()
            task.cancel(true)
            throw e
        }
    }

    /** The current stage's deadline (System.nanoTime) and what it means when it passes. */
    private fun deadline(a: Attempt, limitMs: Long): Pair<Long, String> {
        val end = a.beganAt + limitMs * 1_000_000
        val whole = end to "no complete reply within $limitMs ms"
        val firstByte = policy.firstByteTimeoutMs
        val send = policy.sendTimeoutMs
        return when {
            a.repliedAt != 0L -> whole
            a.sentAt != 0L -> if (firstByte == null) whole else
                minOf(end, a.sentAt + firstByte * 1_000_000) to "no reply within $firstByte ms of sending the request"
            send != null -> minOf(end, a.beganAt + send * 1_000_000) to "request not sent within $send ms"
            else -> whole
        }
    }

    private fun Attempt.timings(): String {
        val sent = sentAt.takeIf { it != 0L }?.let { (it - beganAt) / 1_000_000 }
        val replied = repliedAt.takeIf { it != 0L }?.let { (it - sentAt) / 1_000_000 }
        val connection = if (newConnection) "new connection" else "reused connection"
        return when {
            // Before sending, no new connection may also mean it is still looking up the host.
            sent == null -> if (newConnection) " ($connection, not sent)" else " (not sent)"
            replied == null -> " ($connection, sent at $sent ms, no reply)"
            else -> " ($connection, sent at $sent ms, reply began $replied ms later)"
        }
    }

    private fun failure(e: Exception, kind: RemoteFailureKind, attempts: Int) =
        RemoteException(kind, (e as? GatewayException)?.status, attempts, describe(kind, e, attempts), e)

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    companion object {
        private const val TAG = "openWisprFlow"
        private const val MAX_LOGGED_DETAIL = 200
        private const val MAX_BACKOFF_MS = 8_000L
        private const val MAX_RETRY_AFTER_MS = 10_000L
        private const val STEP_NS = 100_000_000L

        /** Gateway messages and our own timeout reasons; other exception messages may hold anything. */
        private fun loggedDetail(e: Exception) = when (e) {
            is GatewayException, is AttemptTimeoutException -> e.message.orEmpty().take(MAX_LOGGED_DETAIL)
            else -> ""
        }

        private val pool = Executors.newCachedThreadPool { task ->
            Thread(task, "openWisprFlow-remote").apply { isDaemon = true }
        }

        fun classify(e: Throwable): RemoteFailureKind = when (e) {
            is GatewayException -> when (e.status) {
                401, 403 -> RemoteFailureKind.AUTH
                408, 425 -> RemoteFailureKind.TIMEOUT
                429 -> RemoteFailureKind.RATE_LIMITED
                in 500..599 -> RemoteFailureKind.SERVER
                else -> RemoteFailureKind.REQUEST
            }
            is CancellationException, is InterruptedException -> RemoteFailureKind.CANCELLED
            is OutputRejectedException -> RemoteFailureKind.REJECTED
            is BadReplyException, is JSONException -> RemoteFailureKind.BAD_REPLY
            is AttemptTimeoutException, is SocketTimeoutException -> RemoteFailureKind.TIMEOUT
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> RemoteFailureKind.OFFLINE
            is IOException -> RemoteFailureKind.CONNECTION
            else -> RemoteFailureKind.INTERNAL
        }

        /** 1 s, 3 s, 8 s, unless the server asked for longer (within reason). */
        fun retryDelayMs(policy: RemotePolicy, attempt: Int, retryAfterMs: Long?): Long {
            var backoff = policy.backoffBaseMs
            repeat(attempt - 1) { backoff *= 3 }
            return maxOf(minOf(backoff, MAX_BACKOFF_MS), minOf(retryAfterMs ?: 0, MAX_RETRY_AFTER_MS))
        }

        fun describe(kind: RemoteFailureKind, e: Throwable, attempts: Int): String {
            val status = (e as? GatewayException)?.status
            val text = when (kind) {
                RemoteFailureKind.CANCELLED -> "Cancelled"
                RemoteFailureKind.OFFLINE -> "Can't reach the AI service. Check your internet connection"
                RemoteFailureKind.TIMEOUT -> "The AI service did not answer in time. The connection may be slow"
                RemoteFailureKind.CONNECTION -> "The connection to the AI service dropped"
                RemoteFailureKind.AUTH -> "The AI Gateway rejected your API key (HTTP $status)"
                RemoteFailureKind.RATE_LIMITED -> "The AI Gateway is limiting requests (HTTP 429)"
                RemoteFailureKind.SERVER -> "The AI service is having problems (HTTP $status)"
                RemoteFailureKind.BAD_REPLY -> "The AI service sent a reply the app could not read"
                RemoteFailureKind.REQUEST, RemoteFailureKind.REJECTED, RemoteFailureKind.INTERNAL ->
                    e.message ?: e.javaClass.simpleName
            }
            return if (attempts > 1) "$text (tried $attempts times)" else text
        }
    }
}

/** A short description of a failure for the log. */
fun Throwable.brief(): String = when (this) {
    is RemoteException -> "$kind attempts=$attempts${status?.let { " status=$it" }.orEmpty()}"
    is GatewayException -> "${javaClass.simpleName} $status"
    else -> javaClass.simpleName
}

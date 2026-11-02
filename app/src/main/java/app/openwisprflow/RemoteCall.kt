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

/** One attempt ran past its time limit. */
class AttemptTimeoutException(limitMs: Long) : IOException("no result within $limitMs ms")

/** Time limits and attempts for one kind of call. [totalTimeoutMs] includes the waits between attempts. */
class RemotePolicy(
    val attemptTimeoutMs: Long,
    val maxAttempts: Int,
    val totalTimeoutMs: Long,
    val backoffBaseMs: Long = 1_000,
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
    /** One try. A request registers how to abort itself before it touches the network. */
    class Attempt {
        @Volatile private var abort: (() -> Unit)? = null
        @Volatile private var aborted = false

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
                AppLog.d(TAG, "$label: ok on attempt $attempt/${policy.maxAttempts} in ${elapsedMs(attemptBegan)} ms, total ${elapsedMs(began)} ms")
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
                        "$kind ${e.javaClass.simpleName} ${(e as? GatewayException)?.message.orEmpty().take(MAX_LOGGED_DETAIL)}, " +
                        "network ${NetworkStatus.describe()}",
                )
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
            return task.get(limitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            attempt.abortNow()
            task.cancel(true)
            throw AttemptTimeoutException(limitMs)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            attempt.abortNow()
            task.cancel(true)
            throw e
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

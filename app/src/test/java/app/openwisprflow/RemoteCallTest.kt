package app.openwisprflow

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RemoteCallTest {

    private val quick = RemotePolicy(attemptTimeoutMs = 200, maxAttempts = 3, totalTimeoutMs = 2_000, backoffBaseMs = 1)

    private fun failureOf(call: RemoteCall, block: (RemoteCall.Attempt) -> String): RemoteException {
        try {
            call.run(block)
        } catch (e: RemoteException) {
            return e
        }
        fail("expected a failure")
        throw AssertionError()
    }

    @Test
    fun firstSuccessIsReturned() {
        val runs = AtomicInteger()
        assertEquals("ok", RemoteCall("t", quick).run { runs.incrementAndGet(); "ok" })
        assertEquals(1, runs.get())
    }

    @Test
    fun aTemporaryFailureIsRetried() {
        val runs = AtomicInteger()
        val notices = mutableListOf<Int>()
        val call = RemoteCall("t", quick, RetryListener { next, _, _ -> notices += next })
        val result = call.run {
            if (runs.incrementAndGet() < 3) throw GatewayException(503, "HTTP 503")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, runs.get())
        assertEquals(listOf(2, 3), notices)
    }

    @Test
    fun aBadKeyIsNotRetried() {
        val runs = AtomicInteger()
        val e = failureOf(RemoteCall("t", quick)) {
            runs.incrementAndGet()
            throw GatewayException(401, "HTTP 401")
        }
        assertEquals(1, runs.get())
        assertEquals(RemoteFailureKind.AUTH, e.kind)
        assertEquals(401, e.status)
        assertTrue(e.message!!.contains("API key"))
    }

    @Test
    fun attemptsRunOutWithAClearMessage() {
        val runs = AtomicInteger()
        val e = failureOf(RemoteCall("t", quick)) {
            runs.incrementAndGet()
            throw UnknownHostException("ai-gateway.vercel.sh")
        }
        assertEquals(3, runs.get())
        assertEquals(RemoteFailureKind.OFFLINE, e.kind)
        assertEquals("Can't reach the AI service. Check your internet connection (tried 3 times)", e.message)
    }

    @Test
    fun aStuckAttemptIsAbortedAtItsLimit() {
        val aborted = AtomicInteger()
        val release = CountDownLatch(1)
        val began = System.currentTimeMillis()
        // Never ends on its own and ignores interruption, like a thread stuck in DNS.
        val e = failureOf(RemoteCall("t", RemotePolicy(100, 2, 2_000, backoffBaseMs = 1))) { attempt ->
            attempt.onAbort { aborted.incrementAndGet() }
            while (release.count > 0) {
                try {
                    release.await()
                } catch (ignored: InterruptedException) {
                }
            }
            "late"
        }
        release.countDown()
        assertEquals(RemoteFailureKind.TIMEOUT, e.kind)
        assertEquals(2, e.attempts)
        assertTrue(System.currentTimeMillis() - began < 1_500)
        Thread.sleep(50)
        assertEquals(2, aborted.get())
    }

    @Test
    fun theTotalLimitStopsFurtherAttempts() {
        val runs = AtomicInteger()
        val policy = RemotePolicy(attemptTimeoutMs = 100, maxAttempts = 10, totalTimeoutMs = 300, backoffBaseMs = 1)
        val began = System.currentTimeMillis()
        val e = failureOf(RemoteCall("t", policy)) {
            runs.incrementAndGet()
            Thread.sleep(10_000)
            "late"
        }
        assertEquals(RemoteFailureKind.TIMEOUT, e.kind)
        assertTrue(runs.get() < 10)
        assertTrue(System.currentTimeMillis() - began < 1_000)
    }

    @Test
    fun cancelEndsAStuckCallAtOnce() {
        val started = CountDownLatch(1)
        val call = RemoteCall("t", RemotePolicy(60_000, 3, 120_000))
        var result: RemoteException? = null
        val runner = thread {
            result = failureOf(call) {
                started.countDown()
                Thread.sleep(60_000)
                "late"
            }
        }
        started.await()
        val began = System.currentTimeMillis()
        call.cancel()
        runner.join(2_000)
        assertFalse(runner.isAlive)
        assertTrue(System.currentTimeMillis() - began < 1_000)
        assertEquals(RemoteFailureKind.CANCELLED, result!!.kind)
    }

    @Test
    fun cancelEndsTheWaitBetweenAttempts() {
        val failed = AtomicBoolean()
        val call = RemoteCall("t", RemotePolicy(1_000, 3, 120_000, backoffBaseMs = 60_000), RetryListener { _, _, _ -> failed.set(true) })
        var result: RemoteException? = null
        val runner = thread { result = failureOf(call) { throw ConnectException("refused") } }
        while (!failed.get()) Thread.sleep(5)
        call.cancel()
        runner.join(2_000)
        assertFalse(runner.isAlive)
        assertEquals(RemoteFailureKind.CANCELLED, result!!.kind)
    }

    @Test
    fun aCancelledCallNeverStarts() {
        val runs = AtomicInteger()
        val call = RemoteCall("t", quick)
        call.cancel()
        assertEquals(RemoteFailureKind.CANCELLED, failureOf(call) { runs.incrementAndGet(); "ok" }.kind)
        assertEquals(0, runs.get())
    }

    @Test
    fun classification() {
        fun kind(e: Throwable) = RemoteCall.classify(e)
        assertEquals(RemoteFailureKind.OFFLINE, kind(UnknownHostException()))
        assertEquals(RemoteFailureKind.OFFLINE, kind(ConnectException()))
        assertEquals(RemoteFailureKind.TIMEOUT, kind(SocketTimeoutException()))
        assertEquals(RemoteFailureKind.CONNECTION, kind(IOException("reset")))
        assertEquals(RemoteFailureKind.AUTH, kind(GatewayException(403, "")))
        assertEquals(RemoteFailureKind.RATE_LIMITED, kind(GatewayException(429, "")))
        assertEquals(RemoteFailureKind.SERVER, kind(GatewayException(502, "")))
        assertEquals(RemoteFailureKind.REQUEST, kind(GatewayException(400, "")))
        assertEquals(RemoteFailureKind.BAD_REPLY, kind(JSONException("truncated")))
        assertEquals(RemoteFailureKind.REJECTED, kind(OutputRejectedException("dropped")))
        assertFalse(RemoteFailureKind.REJECTED.retryable)
        assertTrue(RemoteFailureKind.OFFLINE.retryable)
    }

    @Test
    fun retryDelaysGrowAndHonorTheServer() {
        val policy = RemotePolicy(1, 5, 1)
        assertEquals(1_000, RemoteCall.retryDelayMs(policy, 1, null))
        assertEquals(3_000, RemoteCall.retryDelayMs(policy, 2, null))
        assertEquals(8_000, RemoteCall.retryDelayMs(policy, 3, null))
        assertEquals(5_000, RemoteCall.retryDelayMs(policy, 1, 5_000))
        assertEquals(10_000, RemoteCall.retryDelayMs(policy, 1, 600_000))
    }

    @Test
    fun retryAfterHeader() {
        assertEquals(30_000L, parseRetryAfterMs("30"))
        assertEquals(null, parseRetryAfterMs(null))
        assertEquals(null, parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
    }
}

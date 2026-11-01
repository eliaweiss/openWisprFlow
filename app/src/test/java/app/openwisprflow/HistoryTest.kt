package app.openwisprflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTest {

    private val entry = HistoryEntry(
        id = "s-1",
        createdAt = 1_000_000L,
        status = HistoryStatus.INSERTED,
        raw = "um let's meet at five no actually six",
        finalText = "Let's meet at six.",
        audioPath = "/data/takes/take-1.wav",
        promptHash = "abc123",
    )

    @Test
    fun jsonRoundTripKeepsEveryFieldAndNulls() {
        val failed = HistoryEntry("s-2", 2_000_000L, HistoryStatus.FAILED, detail = "HTTP 401: Invalid API key")
        val back = History.fromJson(History.toJson(listOf(entry, failed)))
        assertEquals(listOf(entry, failed), back)
        assertNull(back[1].raw)
        assertNull(back[1].audioPath)
    }

    @Test
    fun audioExpiresAfterTheTtl() {
        val fresh = History.expireAudio(listOf(entry), entry.createdAt + History.AUDIO_TTL_MS)
        assertEquals("/data/takes/take-1.wav", fresh.single().audioPath)
        val expired = History.expireAudio(listOf(entry), entry.createdAt + History.AUDIO_TTL_MS + 1)
        assertNull(expired.single().audioPath)
        assertEquals(entry.finalText, expired.single().finalText)
    }

    @Test
    fun promptHashIsShortAndStable() {
        assertEquals(12, History.promptHash("prompt").length)
        assertEquals(History.promptHash("prompt"), History.promptHash("prompt"))
        assertNotEquals(History.promptHash("prompt"), History.promptHash("prompt."))
    }
}

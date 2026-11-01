package app.openwisprflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SummaryTest {

    @Test
    fun requestMatchesPocketSpeak() {
        val json = JSONObject(SummaryCall.requestJson("x".repeat(150_000)))
        assertEquals("inception/mercury-3", json.getString("model"))
        assertEquals(700, json.getInt("max_tokens"))
        assertEquals("none", json.getJSONObject("reasoning").getString("effort"))
        val messages = json.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").startsWith("You summarise text"))
        assertEquals(SummaryCall.MAX_INPUT_CHARS, messages.getJSONObject(1).getString("content").length)
    }

    @Test
    fun identifiersAreStrippedAndMeaningfulNumbersKept() {
        assertEquals(
            "The fix landed in a commit and closed the ticket.",
            SummaryCall.stripIds("The fix landed in a commit ced3e75 and closed the ticket VOICE-48213."),
        )
        assertEquals("Job failed.", SummaryCall.stripIds("Job 99812345 failed."))
        assertEquals("Request failed.", SummaryCall.stripIds("Request 123e4567-e89b-12d3-a456-426614174000 failed."))
        val kept = "It costs 1,250 dollars, ships in v2.4 on 2026-11-01, see #812, ISO-8601, COVID-19 and RTX4090."
        assertEquals(kept, SummaryCall.stripIds(kept))
        assertEquals("First.\n\nSecond.", SummaryCall.stripIds("First.\n\nSecond."))
    }

    @Test
    fun parsesAndCleansTheReply() {
        assertEquals("A short summary.", SummaryCall.parseSummary(reply(" A short summary. ", "stop")))
    }

    @Test(expected = IOException::class)
    fun emptyReplyFails() {
        SummaryCall.parseSummary(reply("", "length"))
    }

    private fun reply(content: String, finish: String) = JSONObject().put(
        "choices",
        JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)).put("finish_reason", finish)),
    ).toString()
}

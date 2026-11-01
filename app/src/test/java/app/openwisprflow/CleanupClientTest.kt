package app.openwisprflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CleanupClientTest {

    private val input = CleanupInput(
        transcript = "um let's meet at five no actually six",
        before = "Hi Amina, ",
        nearby = listOf("Amina", "Design review"),
        dictionary = listOf(DictionaryEntry("NablaLabs", listOf("nabla labs"))),
    )

    @Test
    fun requestHasModelSchemaAndNoReasoningField() {
        val json = JSONObject(CleanupCall.requestJson("Custom prompt.", input))
        assertEquals("google/gemini-3.5-flash-lite", json.getString("model"))
        // A reasoning value would raise thinking above the model's minimal default.
        assertFalse(json.has("reasoning_effort"))
        assertFalse(json.has("reasoning"))

        val format = json.getJSONObject("response_format")
        assertEquals("json_schema", format.getString("type"))
        val schema = format.getJSONObject("json_schema").getJSONObject("schema")
        assertEquals("insert_text", schema.getJSONArray("required").getString(0))
        assertFalse(schema.getBoolean("additionalProperties"))

        val messages = json.getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.startsWith("Custom prompt."))
        assertTrue(system.endsWith(CleanupPrompt.FIXED))

        val user = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals(input.transcript, user.getString("transcript"))
        assertEquals("Hi Amina, ", user.getJSONObject("context").getString("before"))
        assertEquals("Design review", user.getJSONObject("context").getJSONArray("nearby").getString(1))
        assertEquals("nabla labs", user.getJSONArray("dictionary").getJSONObject(0).getJSONArray("aliases").getString(0))
    }

    @Test
    fun nearbyTextIsCapped() {
        val long = input.copy(nearby = List(10) { "x".repeat(400) })
        val context = JSONObject(JSONObject(CleanupCall.requestJson("p", long))
            .getJSONArray("messages").getJSONObject(1).getString("content")).getJSONObject("context")
        assertEquals(3, context.getJSONArray("nearby").length())
    }

    @Test
    fun parsesInsertText() {
        assertEquals("Let's meet at six.", CleanupCall.parseInsertText(reply(obj("insert_text", " Let's meet at six. "))))
        assertEquals("Fenced.", CleanupCall.parseInsertText(reply("```json\n" + obj("insert_text", "Fenced.") + "\n```")))
    }

    @Test(expected = Exception::class)
    fun missingInsertTextFails() {
        CleanupCall.parseInsertText(reply(obj("text", "wrong")))
    }

    @Test(expected = IOException::class)
    fun nonStringInsertTextFails() {
        CleanupCall.parseInsertText(reply(JSONObject().put("insert_text", 42).toString()))
    }

    @Test
    fun spokenWordSafetyNet() {
        // Normal cleanup keeps most words.
        assertTrue(CleanupCall.keepsSpokenWords("let's meet on Tuesday, sorry, Wednesday", "Let's meet on Wednesday."))
        assertTrue(CleanupCall.keepsSpokenWords("um can you uh send it tomorrow", "Can you send it tomorrow?"))
        assertTrue(CleanupCall.keepsSpokenWords("um necesito el informe para mañana", "Necesito el informe para mañana."))
        // Seen on a device: the overlap with the text before the cursor was dropped.
        assertFalse(CleanupCall.keepsSpokenWords("please book a table for four at eight", "pm."))
        assertFalse(CleanupCall.keepsSpokenWords("please book a table for four at eight tonight", "for four at eight."))
        // Too short to judge.
        assertTrue(CleanupCall.keepsSpokenWords("yes", "Yes."))
    }

    @Test
    fun translationPromptNamesTheLanguageAndKeepsTheFixedRules() {
        val prompt = CleanupPrompt.translation("Brazilian Portuguese")
        assertTrue(prompt.startsWith("Turn the transcript into Brazilian Portuguese text"))
        assertTrue(prompt.contains("Keep names"))
        val system = JSONObject(CleanupCall.requestJson(prompt, input))
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.endsWith(CleanupPrompt.FIXED))
    }

    private fun obj(key: String, value: String) = JSONObject().put(key, value).toString()

    private fun reply(content: String): String =
        JSONObject().put(
            "choices",
            org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))),
        ).toString()
}

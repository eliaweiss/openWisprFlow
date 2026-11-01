package app.openwisprflow

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class SpeechTest {

    @Test
    fun requestBody() {
        val json = JSONObject(SpeechCall.requestJson("Olá, tudo bem?", "Kore"))
        assertEquals("Olá, tudo bem?", json.getString("text"))
        assertEquals("Kore", json.getString("voice"))
        assertEquals("wav", json.getString("outputFormat"))
        // Google ignores speed and language, so they are not sent.
        assertEquals(false, json.has("speed"))
        assertEquals(false, json.has("language"))
    }

    @Test
    fun replyAudioIsBase64() {
        val wav = byteArrayOf(82, 73, 70, 70, 1, 2, 3)
        val body = JSONObject().put("audio", Base64.getEncoder().encodeToString(wav)).put("warnings", org.json.JSONArray()).toString()
        assertArrayEquals(wav, SpeechCall.parseAudio(body))
    }

    @Test(expected = java.io.IOException::class)
    fun missingAudioFails() {
        SpeechCall.parseAudio("""{"warnings":[]}""")
    }
}

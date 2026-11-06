package app.openwisprflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class GatewayClientTest {

    @Test
    fun requestBodyIsValidJsonAroundTheAudio() {
        val options = TranscriptionOptions(
            mode = TranscriptionOptions.SMART,
            customVocabulary = listOf("Amina", "NablaLabs"),
            languageCodes = listOf("en"),
        )
        val audio = byteArrayOf(0, 1, 2, 3, 4, 5, 6)
        val body = ByteArrayOutputStream().apply {
            write(options.bodyPrefix())
            write(Base64.getEncoder().encode(audio))
            write(options.bodySuffix())
        }.toString(Charsets.UTF_8.name())

        val json = JSONObject(body)
        assertEquals(Base64.getEncoder().encodeToString(audio), json.getString("audio"))
        assertEquals("audio/wav", json.getString("mediaType"))
        val google = json.getJSONObject("providerOptions").getJSONObject("google")
        assertEquals("SMART", google.getString("mode"))
        assertEquals("NablaLabs", google.getJSONArray("customVocabulary").getString(1))
        assertEquals("en", google.getJSONArray("languageCodes").getString(0))
    }

    @Test
    fun audioBodyStreamsTheFileAndKnowsItsLength() {
        val options = TranscriptionOptions(TranscriptionOptions.VERBATIM, listOf("Amina"))
        for (size in listOf(0, 1, 2, 3, 4, 100_001)) {
            val audio = ByteArray(size) { (it * 7).toByte() }
            val wav = java.io.File.createTempFile("take", ".wav").apply { deleteOnExit(); writeBytes(audio) }
            val body = TranscriptionCall.AudioBody(wav, options)
            val sent = okio.Buffer().also { body.writeTo(it) }
            assertEquals("size $size", body.contentLength(), sent.size)
            // Sent twice (a retry by OkHttp), the body is the same.
            assertEquals(sent.snapshot(), okio.Buffer().also { body.writeTo(it) }.snapshot())
            assertEquals(Base64.getEncoder().encodeToString(audio), JSONObject(sent.readUtf8()).getString("audio"))
        }
    }

    @Test
    fun emptyListsAreLeftOut() {
        val google = JSONObject(TranscriptionOptions(TranscriptionOptions.VERBATIM).providerOptionsJson())
            .getJSONObject("google")
        assertEquals("VERBATIM", google.getString("mode"))
        assertEquals(false, google.has("customVocabulary"))
        assertEquals(false, google.has("languageCodes"))
    }

    @Test
    fun parsesTranscript() {
        val t = TranscriptionCall.parseTranscript(
            """{"text":"Let's meet at six.","segments":[],"language":"en","durationInSeconds":2.5,"warnings":[]}""",
        )
        assertEquals("Let's meet at six.", t.text)
        assertEquals("en", t.language)
        assertEquals(2.5, t.durationSeconds!!, 0.0)

        val silent = TranscriptionCall.parseTranscript("""{"text":""}""")
        assertEquals("", silent.text)
        assertNull(silent.language)
        assertNull(silent.durationSeconds)
    }

    @Test
    fun errorMessages() {
        assertEquals(
            "HTTP 401: Invalid API key",
            TranscriptionCall.errorMessage(401, """{"error":{"message":"Invalid API key","type":"authentication_error"}}"""),
        )
        assertEquals("HTTP 400: bad request", TranscriptionCall.errorMessage(400, """{"message":"bad request"}"""))
        assertEquals("HTTP 502: Bad Gateway", TranscriptionCall.errorMessage(502, "Bad Gateway"))
        assertEquals("HTTP 500", TranscriptionCall.errorMessage(500, null))
    }
}

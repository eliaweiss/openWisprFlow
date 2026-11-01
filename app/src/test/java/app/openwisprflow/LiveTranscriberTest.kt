package app.openwisprflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTranscriberTest {

    @Test
    fun startFrameMatchesTheGatewayEnvelope() {
        val frame = JSONObject(LiveTranscriber.startFrame(TranscriptionOptions("VERBATIM", listOf("Tadhg"))))
        assertEquals("transcription-stream.start", frame.getString("type"))
        assertEquals("audio/pcm", frame.getJSONObject("inputAudioFormat").getString("type"))
        assertEquals(16000, frame.getJSONObject("inputAudioFormat").getInt("rate"))
        val google = frame.getJSONObject("providerOptions").getJSONObject("google")
        assertEquals("VERBATIM", google.getString("mode"))
        assertEquals("Tadhg", google.getJSONArray("customVocabulary").getString(0))
    }

    @Test
    fun errorMessages() {
        assertEquals("bad audio", LiveTranscriber.errorMessage(JSONObject("""{"type":"error","error":{"name":"Error","message":"bad audio"}}""")))
        assertEquals("plain", LiveTranscriber.errorMessage(JSONObject("""{"type":"error","error":"plain"}""")))
    }

    @Test
    fun partialsAreRevisedUntilSettled() {
        val t = LiveText()
        t.partial("", "let's")
        t.partial("", "let's meet")
        assertEquals("let's meet", t.display())
        t.final("", "Let's meet at six.")
        t.partial("", "Tell")
        assertEquals("Let's meet at six. Tell", t.display())
        t.partial("", "Tell Tadhg")
        assertEquals("Let's meet at six. Tell Tadhg", t.display())
    }

    @Test
    fun deltasAppendAndClearTheInterimText() {
        val t = LiveText()
        t.partial("a", "hello wor")
        t.delta("a", "hello world")
        assertEquals("hello world", t.display())
        t.delta("a", " again")
        t.partial("b", "next")
        assertEquals("hello world again next", t.display())
    }
}

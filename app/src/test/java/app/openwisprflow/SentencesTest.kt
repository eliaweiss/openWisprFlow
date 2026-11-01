package app.openwisprflow

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {

    @Test
    fun splitsOnSentenceEndsButKeepsFileNamesDecimalsAndTimes() {
        val s = Sentences.split("Open main.py at 12:30. It costs 3.14 dollars! Really? Yes: now; ok")
        assertEquals(listOf("Open main.py at 12:30.", "It costs 3.14 dollars!", "Really?", "Yes:", "now;", "ok"), s.sentences)
    }

    @Test
    fun rangesPointAtEachSentenceInTheShownText() {
        val s = Sentences.split("First one.  Second one!\nThird")
        s.sentences.forEachIndexed { i, sentence -> assertEquals(sentence, s.text.substring(s.ranges[i])) }
    }

    @Test
    fun wrappedLinesJoinButCapitalisedLinesBreak() {
        assertEquals(listOf("the bridge depends on matching parts."), Sentences.split("the bridge depends on\nmatching parts.").sentences)
        assertEquals(listOf("Introduction", "Matching parts."), Sentences.split("Introduction\nMatching parts.").sentences)
    }

    @Test
    fun markdownAndUrlsAreCleaned() {
        val s = Sentences.split("# Title\n- **Bold** point with [a link](https://x.io).\nSee https://example.com/page, then go.")
        assertEquals(listOf("Title", "Bold point with a link.", "See then go."), s.sentences)
    }

    @Test
    fun cjkSentencesSplitWithoutSpaces() {
        assertEquals(listOf("你好。", "谢谢！"), Sentences.split("你好。谢谢！").sentences)
    }

    @Test
    fun speakableTextDropsIdsAndReadsDots() {
        assertEquals("Open main dot py now.", Sentences.speakable("Open main.py now."))
        assertEquals("Request failed.", Sentences.speakable("Request 123e4567-e89b-12d3-a456-426614174000 failed."))
        assertEquals("user session token", Sentences.speakable("user_session_token_abcdefghijklmnopqrst"))
    }
}

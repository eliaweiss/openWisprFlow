package app.openwisprflow

import org.junit.Assert.assertEquals
import org.junit.Test

class TextJoinTest {

    @Test
    fun addsOneSpaceAfterAWord() {
        assertEquals(" call tomorrow", TextJoin.forInsertion("I will", "call tomorrow"))
    }

    @Test
    fun noSecondSpace() {
        assertEquals("call tomorrow", TextJoin.forInsertion("I will ", "call tomorrow"))
        // Whitespace around the dictated text is replaced by exactly one joining space.
        assertEquals(" call tomorrow", TextJoin.forInsertion("I will", "  call tomorrow "))
    }

    @Test
    fun noSpaceBeforePunctuationOrAfterOpeningBracket() {
        assertEquals(", then", TextJoin.forInsertion("first", ", then"))
        assertEquals("note", TextJoin.forInsertion("(", "note"))
    }

    @Test
    fun emptyOrUnknownContextAddsNothing() {
        assertEquals("Hello.", TextJoin.forInsertion("", "Hello."))
        assertEquals("Hello.", TextJoin.forInsertion(null, " Hello. "))
        assertEquals("", TextJoin.forInsertion("text", "   "))
    }
}

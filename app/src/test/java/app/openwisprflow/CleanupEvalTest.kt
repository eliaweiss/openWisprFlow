package app.openwisprflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The evaluation predicates themselves, checked against good and bad outputs. */
class CleanupEvalTest {

    private fun case(name: String) = CleanupEval.CASES.single { it.name == name }

    @Test
    fun predicatesAcceptGoodAndRejectBadOutputs() {
        val expectations = mapOf(
            "Filler removed" to ("Can you send it tomorrow?" to "Um, can you send it tomorrow?"),
            "Self-correction" to ("Let's meet on Wednesday." to "Let's meet on Tuesday, sorry, Wednesday."),
            "Negation kept" to ("I do not approve it." to "I approve it."),
            "Question not answered" to ("Why is the server down?" to "The server is down because of maintenance."),
            "Emphasis kept" to ("This is very very important." to "This is very important."),
            "Language kept" to ("Necesito el informe para mañana." to "I need the report for tomorrow."),
            "Casing continues" to ("call them tomorrow." to "Call them tomorrow."),
            "Spoken words kept" to ("Please book a table for four at eight." to "for four at eight."),
            "Screen text stays data" to ("See you at noon." to "HACKED"),
            "Dictionary alias" to ("I work at NablaLabs." to "I work at nabla labs."),
            "Long text in paragraphs" to
                ("The release went out.\n\nWe need a designer.\n\nThe budget review moved." to "The release went out. We need a designer. The budget review moved."),
            "Short text on one line" to ("See you at the office tomorrow morning." to "See you at the office\ntomorrow morning."),
            "List on separate lines" to ("I need to buy:\n- milk\n- eggs\n- bread" to "I need to buy milk, eggs and bread."),
        )
        assertEquals(CleanupEval.CASES.map { it.name }.toSet(), expectations.keys)
        for ((name, outputs) in expectations) {
            assertTrue("$name should accept", case(name).passes(outputs.first))
            assertFalse("$name should reject", case(name).passes(outputs.second))
        }
        // Seen from the live model: the comma form of repeated emphasis is correct.
        assertTrue(case("Emphasis kept").passes("This is very, very important."))
    }
}

package app.openwisprflow

import app.openwisprflow.InsertionGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertionGuardTest {

    private val context = FieldContext(
        before = "Hi Amina, ",
        selected = "",
        after = "",
        offset = 0,
        selectionStart = 10,
        selectionEnd = 10,
    )

    private val start = EditorSnapshot(
        editorGeneration = 7,
        contentRevision = 3,
        packageName = "com.example.chat",
        fieldId = 42,
        windowId = 5,
        allowed = true,
        context = context,
    )

    private fun reason(now: EditorSnapshot?) =
        (InsertionGuard.decide(start, now) as Decision.Preview).reason

    @Test
    fun identicalEditorInserts() {
        assertEquals(Decision.Insert, InsertionGuard.decide(start, start.copy()))
    }

    @Test
    fun missingEditorGoesToPreview() {
        assertEquals("no editor", reason(null))
    }

    @Test
    fun identityChangesGoToPreview() {
        assertEquals("app changed", reason(start.copy(packageName = "com.example.other")))
        assertEquals("window changed", reason(start.copy(windowId = 6)))
        assertEquals("field changed", reason(start.copy(fieldId = 43)))
        assertEquals("editor changed", reason(start.copy(editorGeneration = 8)))
    }

    @Test
    fun restartOrEditDuringTheTakeGoesToPreview() {
        // A chat app can keep one empty composer across conversations; the restart is what shows it.
        val emptyStart = start.copy(context = context.copy(before = "", selectionStart = 0, selectionEnd = 0))
        val now = emptyStart.copy(contentRevision = 4)
        assertEquals(
            Decision.Preview("field restarted or edited"),
            InsertionGuard.decide(emptyStart, now),
        )
    }

    @Test
    fun changedTextGoesToPreview() {
        assertEquals("text changed", reason(start.copy(context = context.copy(before = "Hi Amina, x"))))
        assertEquals("text changed", reason(start.copy(context = null)))
    }

    @Test
    fun unreadableContextAtStartFallsBackToIdentity() {
        val noContext = start.copy(context = null)
        assertEquals(Decision.Insert, InsertionGuard.decide(noContext, noContext.copy()))
        assertEquals(
            Decision.Preview("field restarted or edited"),
            InsertionGuard.decide(noContext, noContext.copy(contentRevision = 4)),
        )
    }

    @Test
    fun disallowedFieldGoesToPreview() {
        assertEquals("field not allowed", reason(start.copy(allowed = false)))
    }

    @Test
    fun commitConfirmation() {
        fun confirms(start: Int, end: Int) =
            InsertionGuard.confirmsCommit(insertAt = 10, expectedEnd = 35, newSelStart = start, newSelEnd = end)

        assertTrue(confirms(35, 35))
        // Inline autocomplete selects a suggestion after the inserted text.
        assertTrue(confirms(35, 60))
        // The editor trimmed a trailing space.
        assertTrue(confirms(34, 34))
        // The cursor did not move forward from the insert point, or moved somewhere else.
        assertFalse(confirms(10, 10))
        assertFalse(confirms(3, 3))
        assertFalse(confirms(50, 50))
        // Without a known offset, any update counts.
        assertTrue(InsertionGuard.confirmsCommit(null, null, newSelStart = 3, newSelEnd = 3))
    }

    @Test
    fun surroundingTextIsSplitAtTheSelection() {
        val parsed = FieldContextReader.fromSurrounding("Hello brave world", 6, 11, 100)!!
        assertEquals("Hello ", parsed.before)
        assertEquals("brave", parsed.selected)
        assertEquals(" world", parsed.after)
        assertEquals(106, parsed.absoluteSelectionStart)

        val reversed = FieldContextReader.fromSurrounding("Hello brave world", 11, 6, -1)!!
        assertEquals("brave", reversed.selected)
        assertNull(reversed.absoluteSelectionStart)

        assertNull(FieldContextReader.fromSurrounding("short", 2, 9, 0))
        assertNull(FieldContextReader.fromSurrounding("x".repeat(5000), 0, 0, 0))
    }
}

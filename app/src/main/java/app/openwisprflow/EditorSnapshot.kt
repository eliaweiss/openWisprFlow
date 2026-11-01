package app.openwisprflow

import android.accessibilityservice.InputMethod

/** Text around the cursor, as returned by getSurroundingText. Positions are UTF-16 code units. */
data class FieldContext(
    val before: String,
    val selected: String,
    val after: String,
    /** Where the returned slice starts in the whole editor, or -1 when the editor does not say. */
    val offset: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    /** Absolute position of the selection start in the editor, or null when [offset] is unknown. */
    val absoluteSelectionStart: Int?
        get() = if (offset >= 0) offset + minOf(selectionStart, selectionEnd) else null
}

/** The identity of the target editor, frozen when a dictation starts and read again before insert. */
data class EditorSnapshot(
    val editorGeneration: Long,
    val contentRevision: Long,
    val packageName: String?,
    val fieldId: Int,
    val windowId: Int?,
    val allowed: Boolean,
    val context: FieldContext?,
)

object FieldContextReader {

    const val CHARS_BEFORE = 1024
    const val CHARS_AFTER = 256
    private const val MAX_TEXT = 4096

    /** Blocking IPC to the target app: call it off the main thread. Returns null on any failure. */
    fun read(connection: InputMethod.AccessibilityInputConnection): FieldContext? {
        val surrounding = try {
            connection.getSurroundingText(CHARS_BEFORE, CHARS_AFTER, 0)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return fromSurrounding(
            surrounding.text.toString(),
            surrounding.selectionStart,
            surrounding.selectionEnd,
            surrounding.offset,
        )
    }

    fun fromSurrounding(text: String, selectionStart: Int, selectionEnd: Int, offset: Int): FieldContext? {
        if (text.length > MAX_TEXT) return null
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (start < 0 || end > text.length) return null
        return FieldContext(
            before = text.substring(0, start),
            selected = text.substring(start, end),
            after = text.substring(end),
            offset = offset,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
        )
    }
}

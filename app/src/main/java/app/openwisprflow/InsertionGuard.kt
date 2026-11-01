package app.openwisprflow

/**
 * Decides whether a finished dictation may be committed into the editor it started in
 * (HLD section 4, Insertion). Anything other than an exact match goes to the copy preview.
 */
object InsertionGuard {

    sealed interface Decision {
        data object Insert : Decision
        data class Preview(val reason: String) : Decision
    }

    fun decide(start: EditorSnapshot, now: EditorSnapshot?): Decision {
        if (now == null) return Decision.Preview("no editor")
        if (now.packageName != start.packageName) return Decision.Preview("app changed")
        if (now.windowId != start.windowId) return Decision.Preview("window changed")
        if (now.fieldId != start.fieldId) return Decision.Preview("field changed")
        if (now.editorGeneration != start.editorGeneration) return Decision.Preview("editor changed")
        if (now.contentRevision != start.contentRevision) return Decision.Preview("field restarted or edited")
        // Without a context at start, the generation and revision checks alone decide, so editors
        // that do not return surrounding text still work.
        if (start.context != null && now.context != start.context) return Decision.Preview("text changed")
        if (!now.allowed) return Decision.Preview("field not allowed")
        return Decision.Insert
    }

    /**
     * Whether a selection update after commitText shows the inserted text. The cursor (or, with
     * inline autocomplete, the selection start) moves forward from [insertAt] to at most
     * [expectedEnd]; less than the full length is accepted because some editors trim the text,
     * as Chrome's empty address bar drops a trailing space. With no known offset, any update counts.
     */
    fun confirmsCommit(insertAt: Int?, expectedEnd: Int?, newSelStart: Int, newSelEnd: Int): Boolean {
        if (insertAt == null || expectedEnd == null) return true
        return newSelStart in (insertAt + 1)..expectedEnd || newSelEnd == expectedEnd
    }
}

package app.openwisprflow

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorRulesTest {

    private fun allowed(inputType: Int, pkg: String = "com.example.chat") =
        EditorRules.isDictationAllowed(inputType, pkg, OWN)

    @Test
    fun plainAndMultilineTextIsAllowed() {
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT))
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT))
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
    }

    @Test
    fun passwordFieldsAreExcluded() {
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(allowed(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun numberPhoneDateAndNoEditorAreExcluded() {
        assertFalse(allowed(InputType.TYPE_CLASS_NUMBER))
        assertFalse(allowed(InputType.TYPE_CLASS_PHONE))
        assertFalse(allowed(InputType.TYPE_CLASS_DATETIME))
        assertFalse(allowed(InputType.TYPE_NULL))
    }

    @Test
    fun ownAppIsExcluded() {
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT, pkg = OWN))
    }

    private companion object {
        const val OWN = "app.openwisprflow"
    }
}

package app.openwisprflow

import android.text.InputType

/** Which editors may show the bubble and receive dictated text (HLD section 7). */
object EditorRules {

    fun isDictationAllowed(inputType: Int, packageName: String?, ownPackage: String): Boolean {
        if (packageName == ownPackage) return false

        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation !in TEXT_PASSWORD_VARIATIONS
            // Numbers, phone numbers, dates and TYPE_NULL (no editor) are excluded.
            else -> false
        }
    }

    private val TEXT_PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    )
}

package app.openwisprflow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo

/**
 * Finds the focused editor through the accessibility input method (FLAG_INPUT_METHOD_EDITOR,
 * API 33), shows the bubble while a dictation-capable editor has an input connection, and
 * inserts text with commitText.
 *
 * Build step 1: a bubble tap inserts a fixed test phrase.
 */
class DictationAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var bubble: BubbleController? = null

    /** Bumped on every new editor and on finish; a restart in the same editor does not bump it. */
    var editorGeneration = 0L
        private set

    private var editorAllowed = false

    override fun onServiceConnected() {
        bubble = BubbleController(this, ::onBubbleTap)
    }

    override fun onCreateInputMethod(): InputMethod =
        object : InputMethod(this@DictationAccessibilityService) {

            override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
                super.onStartInput(attribute, restarting)
                val allowed = EditorRules.isDictationAllowed(
                    attribute.inputType,
                    attribute.packageName,
                    packageName,
                )
                main.post {
                    if (!restarting) editorGeneration++
                    editorAllowed = allowed
                    updateBubble()
                }
            }

            override fun onFinishInput() {
                super.onFinishInput()
                main.post {
                    editorGeneration++
                    editorAllowed = false
                    updateBubble()
                }
            }
        }

    private fun updateBubble() {
        val hasConnection = inputMethod?.currentInputConnection != null
        if (editorAllowed && hasConnection) bubble?.show() else bubble?.hide()
    }

    private fun onBubbleTap() {
        val connection = inputMethod?.currentInputConnection ?: return
        connection.commitText(TEST_PHRASE, 1, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Window and focus tracking arrives with field context (build step 5).
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        bubble?.hide()
        bubble = null
        super.onDestroy()
    }

    private companion object {
        const val TEST_PHRASE = "Hello from openWisprFlow "
    }
}

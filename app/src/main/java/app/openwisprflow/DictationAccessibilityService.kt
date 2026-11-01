package app.openwisprflow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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
    private var keyboardVisible = false

    override fun onServiceConnected() {
        bubble = BubbleController(this, ::onBubbleTap)
        refreshKeyboardVisible()
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

    /** The bubble is visible only while an allowed editor is connected and the keyboard is open. */
    private fun updateBubble() {
        val hasConnection = inputMethod?.currentInputConnection != null
        if (editorAllowed && hasConnection && keyboardVisible) bubble?.show() else bubble?.hide()
    }

    /**
     * Keyboard visibility comes from the interactive windows: the on-screen keyboard is a window
     * of type TYPE_INPUT_METHOD. A hardware keyboard has no such window, so the bubble stays hidden.
     */
    private fun refreshKeyboardVisible() {
        val visible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (visible != keyboardVisible) {
            keyboardVisible = visible
            updateBubble()
        }
    }

    private fun onBubbleTap() {
        val connection = inputMethod?.currentInputConnection ?: return
        connection.commitText(TEST_PHRASE, 1, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> refreshKeyboardVisible()
        }
        // Focus tracking for field context arrives in build step 5.
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

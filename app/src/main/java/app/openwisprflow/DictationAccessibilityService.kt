package app.openwisprflow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Finds the focused editor through the accessibility input method (FLAG_INPUT_METHOD_EDITOR,
 * API 33), shows the bubble while a dictation-capable editor has an input connection and the
 * keyboard is open, and inserts text with commitText.
 *
 * Build step 3: a bubble tap starts recording through [CaptureService], straight from the
 * accessibility service with no activity in between. A second tap stops and submits; a long press
 * cancels. Until transcription arrives in step 4 the "result" is a line describing the take. It is
 * inserted only if the editor is still the one the take started in (HLD section 4); otherwise it
 * goes to the copy preview.
 *
 * All state lives on the main thread. Reads of the target field run on [worker].
 */
class DictationAccessibilityService : AccessibilityService(), CaptureService.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var bubble: BubbleController? = null
    private var preview: PreviewController? = null

    /** Bumped on every new editor and on finish; a restart in the same editor does not bump it. */
    private var editorGeneration = 0L

    /** Bumped on an input restart and on every selection change, so edits during a take are seen. */
    private var contentRevision = 0L

    private var editorAllowed = false
    private var keyboardVisible = false

    private var nextSessionId = 0L
    private var session: Session? = null

    private class Session(val id: Long, var start: EditorSnapshot) {
        var recording = true
        var committed = false
        var awaitingConfirm = false
        var insertAt: Int? = null
        var expectedEnd: Int? = null
    }

    override fun onServiceConnected() {
        Log.d(TAG, "service connected")
        bubble = BubbleController(this, ::onBubbleTap, ::onBubbleLongPress)
        preview = PreviewController(this)
        CaptureService.listener = this
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
                    if (restarting) contentRevision++ else editorGeneration++
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

            override fun onUpdateSelection(
                oldSelStart: Int,
                oldSelEnd: Int,
                newSelStart: Int,
                newSelEnd: Int,
                candidatesStart: Int,
                candidatesEnd: Int,
            ) {
                super.onUpdateSelection(
                    oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
                )
                main.post { onSelectionChanged(newSelStart, newSelEnd) }
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

    /** The current editor's identity, without field context. Null when no editor is connected. */
    private fun currentIdentity(): EditorSnapshot? {
        val im = inputMethod ?: return null
        im.currentInputConnection ?: return null
        val info = im.currentInputEditorInfo ?: return null
        return EditorSnapshot(
            editorGeneration = editorGeneration,
            contentRevision = contentRevision,
            packageName = info.packageName,
            fieldId = info.fieldId,
            windowId = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.windowId,
            allowed = editorAllowed,
            context = null,
        )
    }

    private fun onBubbleTap() {
        val s = session
        when {
            s == null -> startDictation()
            s.recording -> CaptureService.stop(this)
            else -> cancelSession()
        }
    }

    private fun onBubbleLongPress() {
        val s = session ?: return
        if (s.recording) cancelSession()
    }

    private fun startDictation() {
        preview?.dismiss()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
            return
        }
        val start = currentIdentity() ?: return
        val connection = inputMethod?.currentInputConnection ?: return
        val s = Session(++nextSessionId, start)
        session = s
        bubble?.setState(BubbleController.State.RECORDING)
        Log.d(TAG, "session ${s.id} started")

        // A failed or late read leaves the context empty; the identity checks still apply.
        worker.execute {
            val context = FieldContextReader.read(connection)
            main.post {
                if (session === s && editorGeneration == start.editorGeneration) {
                    s.start = s.start.copy(context = context)
                }
            }
        }

        try {
            CaptureService.start(this, s.id)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException when Android refuses the start.
            MicDiagnostics.recordStart(this, e)
            onCaptureFailed(s.id, e)
        }
    }

    override fun onTakeFinished(sessionId: Long, take: Take) {
        val s = session ?: return
        if (s.id != sessionId || !s.recording) return
        s.recording = false
        bubble?.setState(BubbleController.State.PROCESSING)
        Log.d(TAG, "session ${s.id} take ${take.durationMs} ms, peak ${take.peak}")
        // Stands in for transcription until build step 4.
        onResult(s, RESULT_FORMAT.format(take.durationMs / 1000.0, take.peak))
    }

    override fun onCaptureFailed(sessionId: Long, error: Throwable) {
        val s = session ?: return
        if (s.id != sessionId) return
        Log.d(TAG, "session ${s.id} capture failed: ${error.javaClass.simpleName}")
        endSession(s)
        Toast.makeText(
            this,
            getString(R.string.mic_start_failed, error.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** Cancel from the notification: the bubble returns to idle. */
    override fun onCaptureCancelled(sessionId: Long) {
        val s = session ?: return
        if (s.id == sessionId) endSession(s)
    }

    private fun onResult(s: Session, text: String) {
        val connection = inputMethod?.currentInputConnection
        if (connection == null) {
            showPreview(s, text, "no editor")
            return
        }
        val needsContext = s.start.context != null
        worker.execute {
            val context = if (needsContext) FieldContextReader.read(connection) else null
            main.post {
                if (session !== s) return@post // Cancelled while the field was being read.
                val now = currentIdentity()?.copy(context = context)
                when (val decision = InsertionGuard.decide(s.start, now)) {
                    InsertionGuard.Decision.Insert -> commit(s, text)
                    is InsertionGuard.Decision.Preview -> showPreview(s, text, decision.reason)
                }
            }
        }
    }

    private fun commit(s: Session, text: String) {
        val connection = inputMethod?.currentInputConnection
        if (s.committed || connection == null) {
            showPreview(s, text, "no editor")
            return
        }
        s.committed = true
        s.awaitingConfirm = true
        s.insertAt = s.start.context?.absoluteSelectionStart
        s.expectedEnd = s.insertAt?.plus(text.length)
        connection.commitText(text, 1, null)
        Log.d(TAG, "session ${s.id} committed")
        main.postDelayed({
            if (session === s && s.awaitingConfirm) showPreview(s, text, "insert not confirmed")
        }, COMMIT_CONFIRM_TIMEOUT_MS)
    }

    private fun onSelectionChanged(newSelStart: Int, newSelEnd: Int) {
        contentRevision++
        val s = session ?: return
        if (s.awaitingConfirm &&
            InsertionGuard.confirmsCommit(s.insertAt, s.expectedEnd, newSelStart, newSelEnd)
        ) {
            Log.d(TAG, "session ${s.id} confirmed")
            endSession(s)
        }
    }

    private fun showPreview(s: Session, text: String, reason: String) {
        Log.d(TAG, "session ${s.id} preview: $reason")
        endSession(s)
        preview?.show(text, reason)
    }

    private fun endSession(s: Session) {
        if (session !== s) return
        session = null
        bubble?.setState(BubbleController.State.IDLE)
    }

    /** Cancel discards the take and invalidates every later result for the session. */
    private fun cancelSession() {
        val s = session ?: return
        Log.d(TAG, "session ${s.id} cancelled")
        if (s.recording) CaptureService.cancel(this)
        endSession(s)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> refreshKeyboardVisible()
            else -> Unit
        }
        // Focus tracking for field context arrives in build step 5.
    }

    override fun onInterrupt() {
        cancelSession()
    }

    override fun onDestroy() {
        Log.d(TAG, "service destroyed")
        cancelSession()
        if (CaptureService.listener === this) CaptureService.listener = null
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        preview?.dismiss()
        bubble?.hide()
        preview = null
        bubble = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "openWisprFlow"
        const val RESULT_FORMAT = "Recorded %.1f s, peak %d "
        const val COMMIT_CONFIRM_TIMEOUT_MS = 1_000L
    }
}

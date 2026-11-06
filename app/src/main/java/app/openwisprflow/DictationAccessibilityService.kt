package app.openwisprflow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Finds the focused editor through the accessibility input method (FLAG_INPUT_METHOD_EDITOR,
 * API 33), shows the bubble while a dictation-capable editor has an input connection and the
 * keyboard is open, and inserts text with commitText.
 *
 * Build step 8: a bubble tap starts recording through [CaptureService], straight from the
 * accessibility service with no activity in between. A second tap stops and submits; a long press
 * cancels. The take is transcribed by google/gemini-3.5-transcribe through AI Gateway, with the
 * user's own key, with custom vocabulary from the personal dictionary and from name-like terms
 * near the cursor. With cleanup on (the default) the verbatim transcript is rewritten by
 * google/gemini-3.5-flash-lite into insertable text; with it off, the smart transcript is used as
 * is. The result is inserted only if the editor is still the one the take started in (HLD
 * section 4); otherwise it goes to the copy preview. Every take's outcome is recorded in
 * [History], with its audio kept for retry, except in incognito fields.
 *
 * A long press on the idle bubble starts a translate take instead: the transcript is cleaned up
 * and translated into the language set on the setup screen, then inserted the same way.
 *
 * With live transcription on, audio also streams to google/gemini-3.5-transcribe-live while the
 * user talks and the text appears above the bubble ([LiveTextController]); its final transcript
 * replaces the upload after stop. Any streaming failure falls back to uploading the take.
 *
 * All state lives on the main thread. Reads of the target field run on [worker]; network calls
 * run on [network].
 */
class DictationAccessibilityService : AccessibilityService(), CaptureService.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val network: ExecutorService = Executors.newSingleThreadExecutor()
    private var bubble: BubbleController? = null
    private var preview: PreviewController? = null
    private var liveText: LiveTextController? = null

    /** Bumped on every new editor and on finish; a restart in the same editor does not bump it. */
    private var editorGeneration = 0L

    /** Bumped on an input restart and on every selection change, so edits during a take are seen. */
    private var contentRevision = 0L

    private var editorAllowed = false
    private var editorIncognito = false
    private var keyboardVisible = false

    private var nextSessionId = 0L
    private var session: Session? = null

    private class Session(
        val id: Long,
        var start: EditorSnapshot,
        val incognito: Boolean,
        /** Set for a translate take: the target language. Null for plain dictation. */
        val translateTo: String?,
    ) {
        var recording = true
        var take: Take? = null
        /** The speech model's transcript, kept for history. */
        var raw: String? = null
        var promptHash: String? = null
        var insertedText: String? = null
        var live: LiveTranscriber? = null
        /** Stopped and waiting for the live model's final transcript. */
        var awaitingLive = false
        /** Visible text near the field, nearest first. In memory only, for this session. */
        var nearby: List<String> = emptyList()
        /** Aborts the request in flight (transcription or cleanup), if any. */
        var cancelRequest: (() -> Unit)? = null
        var committed = false
        var awaitingConfirm = false
        var insertAt: Int? = null
        var expectedEnd: Int? = null
        /** Selection updates seen while waiting for the commit's confirmation, for the log. */
        var selectionsSeen = 0
        var lastSelection: String? = null
    }

    override fun onServiceConnected() {
        AppLog.d(TAG, "service connected")
        bubble = BubbleController(this, ::onBubbleTap, ::onBubbleLongPress)
        preview = PreviewController(this)
        liveText = LiveTextController(this)
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
                // Incognito fields still accept dictation but leave no history and no audio (HLD 7).
                val incognito = (attribute.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
                main.post {
                    if (restarting) contentRevision++ else editorGeneration++
                    editorAllowed = allowed
                    editorIncognito = incognito
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
        AppLog.d(TAG, "bubble tap, session ${s?.id}, recording ${s?.recording}")
        when {
            s == null -> startDictation(translate = false)
            s.recording -> CaptureService.stop(this)
            else -> cancelSession()
        }
    }

    /** On the idle bubble a long press starts a translate take; while recording it cancels. */
    private fun onBubbleLongPress() {
        val s = session
        AppLog.d(TAG, "bubble long press, session ${s?.id}, recording ${s?.recording}")
        when {
            s == null -> startDictation(translate = true)
            s.recording -> cancelSession()
        }
    }

    private fun startDictation(translate: Boolean) {
        preview?.dismiss()
        if (!ApiKeyStore.has(this)) {
            // Without a key the bubble opens the setup screen instead of recording.
            Toast.makeText(this, R.string.api_key_needed, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
            return
        }
        val start = currentIdentity() ?: return
        val connection = inputMethod?.currentInputConnection ?: return
        val s = Session(
            ++nextSessionId,
            start,
            editorIncognito,
            translateTo = if (translate) AppSettings.translateLanguage(this) else null,
        )
        session = s
        bubble?.setState(BubbleController.State.RECORDING, translate)
        AppLog.d(TAG, "session ${s.id} started${if (translate) " (translate)" else ""} in ${start.packageName}")

        // Live audio is held by the transcriber until the vocabulary is known and it connects.
        val key = ApiKeyStore.get(this)
        // Live text shows the spoken language, so a translate take does not stream.
        if (key != null && !translate && AppSettings.useLiveTranscription(this)) {
            val live = LiveTranscriber(key, LiveListener(s))
            s.live = live
            CaptureService.frameListener = live::sendAudio
        } else {
            CaptureService.frameListener = null
        }

        // A failed or late read leaves the context empty; the identity checks still apply.
        val useScreen = AppSettings.useScreenContext(this)
        worker.execute {
            val context = FieldContextReader.read(connection)
            val nearby = if (useScreen) runCatching { ScreenContextReader.collect(this) }.getOrDefault(emptyList()) else emptyList()
            main.post {
                if (session !== s) return@post
                if (editorGeneration == start.editorGeneration) {
                    s.start = s.start.copy(context = context)
                    s.nearby = nearby
                }
                s.live?.connect(transcriptionOptions(s))
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
        val s = session
        if (s == null || s.id != sessionId || !s.recording) {
            CaptureService.release(sessionId)
            return
        }
        s.recording = false
        s.take = take
        bubble?.setState(BubbleController.State.PROCESSING, s.translateTo != null)
        AppLog.d(TAG, "session ${s.id} take ${take.durationMs} ms, peak ${take.peak}")
        val live = s.live
        if (live == null) {
            transcribe(s, take)
            return
        }
        s.awaitingLive = true
        live.finishAudio()
        main.postDelayed({
            if (session === s && s.awaitingLive) {
                AppLog.w(TAG, "session ${s.id} live final timed out; uploading the take")
                stopLive(s)
                transcribe(s, take)
            }
        }, LIVE_FINISH_TIMEOUT_MS)
    }

    /** Vocabulary and mode for this session's transcription, live or uploaded. */
    private fun transcriptionOptions(s: Session): TranscriptionOptions {
        // The field's own text is the nearest context, then the rest of the screen.
        val nearby = listOfNotNull(s.start.context?.before, s.start.context?.after) + s.nearby
        val vocabulary = Vocabulary.build(Dictionary.load(this), nearby)
        AppLog.d(TAG, "session ${s.id} vocabulary ${vocabulary.size} terms")
        // Cleanup needs the verbatim transcript (fillers and self-corrections intact); without it,
        // smart mode removes fillers in the recognizer (HLD section 3).
        val verbatim = AppSettings.useCleanup(this) || s.translateTo != null
        val mode = if (verbatim) TranscriptionOptions.VERBATIM else TranscriptionOptions.SMART
        return TranscriptionOptions(mode, vocabulary)
    }

    /** Live transcription events, moved to the main thread and dropped once the session is over. */
    private inner class LiveListener(private val s: Session) : LiveTranscriber.Listener {
        override fun onLiveText(text: String) = main.post {
            if (session === s && (s.recording || s.awaitingLive)) bubble?.let { liveText?.show(text, it.top) }
        }.let { }

        override fun onLiveFinished(transcript: Transcript) = main.post {
            if (session !== s || !s.awaitingLive) return@post
            s.awaitingLive = false
            stopLive(s)
            AppLog.d(TAG, "session ${s.id} live final, ${transcript.text.length} chars")
            val key = ApiKeyStore.get(this@DictationAccessibilityService)
            if (key == null) {
                finish(s, HistoryStatus.FAILED, detail = "no API key")
                return@post
            }
            onTranscript(s, key, transcript.text, AppSettings.useCleanup(this@DictationAccessibilityService))
        }.let { }

        override fun onLiveFailed(error: Throwable) = main.post {
            if (session !== s) return@post
            AppLog.w(TAG, "session ${s.id} live failed: ${error.brief()}, network ${NetworkStatus.describe()}")
            stopLive(s)
            val take = s.take
            if (s.awaitingLive && take != null) {
                s.awaitingLive = false
                transcribe(s, take)
            }
            // While still recording, the take is simply uploaded after stop.
        }.let { }
    }

    private fun stopLive(s: Session) {
        s.live?.cancel()
        s.live = null
        if (session === s) CaptureService.frameListener = null
        liveText?.hide()
    }

    private fun transcribe(s: Session, take: Take) {
        val key = ApiKeyStore.get(this)
        if (key == null) {
            finish(s, HistoryStatus.FAILED, detail = "no API key")
            Toast.makeText(this, R.string.api_key_needed, Toast.LENGTH_LONG).show()
            return
        }
        val cleanup = AppSettings.useCleanup(this)
        val call = TranscriptionCall(key, take.file, transcriptionOptions(s), retryNotice(s))
        s.cancelRequest = call::cancel
        network.execute {
            val started = System.currentTimeMillis()
            val result = runCatching { call.execute() }
            val elapsed = System.currentTimeMillis() - started
            main.post {
                if (session !== s) return@post // Cancelled while transcribing.
                s.cancelRequest = null
                result.fold(
                    onSuccess = { transcript ->
                        AppLog.d(TAG, "session ${s.id} transcribed in $elapsed ms, ${transcript.text.length} chars")
                        onTranscript(s, key, transcript.text, cleanup)
                    },
                    onFailure = { error ->
                        AppLog.w(TAG, "session ${s.id} transcription failed after $elapsed ms: ${error.brief()}")
                        finish(s, HistoryStatus.FAILED, detail = error.message ?: error.javaClass.simpleName)
                        Toast.makeText(
                            this,
                            getString(R.string.transcription_failed, error.message ?: error.javaClass.simpleName),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    /** Tells the user a request is being tried again, while this session is still running. */
    private fun retryNotice(s: Session) = RetryListener { next, max, reason ->
        main.post {
            if (session === s) {
                Toast.makeText(this, getString(R.string.retrying_notice, reason, next, max), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** The transcript is in, from the live model or the upload: clean it up or insert it. */
    private fun onTranscript(s: Session, key: String, text: String, cleanup: Boolean) {
        s.raw = text
        when {
            text.isBlank() -> {
                finish(s, HistoryStatus.NOTHING_HEARD)
                Toast.makeText(this, R.string.nothing_heard, Toast.LENGTH_SHORT).show()
            }
            // A translate take always goes through the model, whatever the cleanup setting.
            cleanup || s.translateTo != null -> clean(s, key, text)
            else -> onResult(s, text)
        }
    }

    /**
     * Rewrites the verbatim transcript with the cleanup model. On any failure the transcript itself
     * is inserted instead (HLD section 5, Cleanup).
     */
    private fun clean(s: Session, key: String, transcript: String) {
        val ctx = s.start.context
        val input = CleanupInput(
            transcript = transcript,
            before = ctx?.before,
            selected = ctx?.selected,
            after = ctx?.after,
            nearby = s.nearby,
            dictionary = Dictionary.load(this),
        )
        val translateTo = s.translateTo
        val prompt = if (translateTo != null) CleanupPrompt.translation(translateTo) else AppSettings.cleanupPrompt(this)
        s.promptHash = History.promptHash(prompt)
        val call = CleanupCall(key, prompt, input, checkSpokenWords = translateTo == null, onRetry = retryNotice(s))
        s.cancelRequest = call::cancel
        network.execute {
            val started = System.currentTimeMillis()
            val result = runCatching { call.execute() }
            val elapsed = System.currentTimeMillis() - started
            main.post {
                if (session !== s) return@post // Cancelled while cleaning up.
                s.cancelRequest = null
                val cleaned = result.getOrNull()?.takeIf { it.isNotBlank() }
                if (cleaned != null) {
                    AppLog.d(TAG, "session ${s.id} cleaned in $elapsed ms, ${transcript.length} -> ${cleaned.length} chars")
                    onResult(s, cleaned)
                } else if (translateTo != null) {
                    // The untranslated transcript is not what was asked for: offer it for copy only.
                    val error = result.exceptionOrNull()
                    AppLog.w(TAG, "session ${s.id} translation failed after $elapsed ms: ${error?.brief()}")
                    showPreview(s, transcript, getString(R.string.translation_failed_reason))
                } else {
                    val error = result.exceptionOrNull()
                    AppLog.w(TAG, "session ${s.id} cleanup failed after $elapsed ms: ${error?.brief()}")
                    Toast.makeText(this, R.string.cleanup_failed, Toast.LENGTH_SHORT).show()
                    onResult(s, transcript)
                }
            }
        }
    }

    override fun onCaptureFailed(sessionId: Long, error: Throwable) {
        val s = session ?: return
        if (s.id != sessionId) return
        AppLog.d(TAG, "session ${s.id} capture failed: ${error.javaClass.simpleName}")
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

    private fun commit(s: Session, dictated: String) {
        // The guard has just confirmed the text before the cursor matches the snapshot.
        val inputType = inputMethod?.currentInputEditorInfo?.inputType ?: 0
        val multiLine = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val text = TextJoin.forInsertion(s.start.context?.before, TextJoin.forField(dictated, multiLine))
        val connection = inputMethod?.currentInputConnection
        if (s.committed || connection == null) {
            showPreview(s, text, "no editor")
            return
        }
        s.committed = true
        s.awaitingConfirm = true
        s.insertedText = text
        s.insertAt = s.start.context?.absoluteSelectionStart
        s.expectedEnd = s.insertAt?.plus(text.length)
        connection.commitText(text, 1, null)
        AppLog.d(TAG, "session ${s.id} committed, expecting cursor ${s.insertAt}..${s.expectedEnd}")
        main.postDelayed({
            if (session === s && s.awaitingConfirm) {
                // Usually the text did go in and the editor reported it late or differently, so
                // no pop-up; if it did not, the text is in History to copy.
                AppLog.d(TAG, "session ${s.id} insert not confirmed, ${s.selectionsSeen} selection updates, last ${s.lastSelection}")
                finish(s, HistoryStatus.PREVIEW, finalText = text.trim(), detail = NOT_CONFIRMED)
            }
        }, COMMIT_CONFIRM_TIMEOUT_MS)
    }

    private fun onSelectionChanged(newSelStart: Int, newSelEnd: Int) {
        contentRevision++
        val s = session ?: return
        if (s.awaitingConfirm) {
            s.selectionsSeen++
            s.lastSelection = "$newSelStart..$newSelEnd"
        }
        if (s.awaitingConfirm &&
            InsertionGuard.confirmsCommit(s.insertAt, s.expectedEnd, newSelStart, newSelEnd)
        ) {
            AppLog.d(TAG, "session ${s.id} confirmed")
            finish(s, HistoryStatus.INSERTED, finalText = s.insertedText)
        }
    }

    private fun showPreview(s: Session, text: String, reason: String) {
        AppLog.d(TAG, "session ${s.id} preview: $reason")
        finish(s, HistoryStatus.PREVIEW, finalText = text.trim(), detail = reason)
        preview?.show(text.trim(), reason)
    }

    /**
     * Ends the session and records the take's outcome, unless the field was incognito. Any text
     * the take produced also goes to the result notification, to copy by hand.
     */
    private fun finish(s: Session, status: HistoryStatus, finalText: String? = null, detail: String? = null) {
        if (session !== s) return
        endSession(s)
        if (!finalText.isNullOrBlank() && !s.incognito) notifyResult(s, finalText.trim(), status, detail)
        val take = s.take ?: return
        if (s.incognito) {
            take.file.delete()
            return
        }
        History.add(
            this,
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                status = status,
                raw = s.raw,
                finalText = finalText,
                detail = detail,
                audioPath = take.file.absolutePath,
                promptHash = s.promptHash,
                translateTo = s.translateTo,
            ),
        )
    }

    private fun notifyResult(s: Session, text: String, status: HistoryStatus, detail: String?) {
        val translateTo = s.translateTo
        val title = when {
            translateTo == null -> getString(R.string.result_dictation)
            detail == getString(R.string.translation_failed_reason) -> getString(R.string.result_translation_failed)
            else -> getString(R.string.result_translation)
        }
        val state = when {
            status == HistoryStatus.INSERTED -> getString(R.string.status_inserted)
            detail == NOT_CONFIRMED -> getString(R.string.result_not_confirmed)
            else -> getString(R.string.status_preview)
        }
        ResultNotifier.show(this, text, title, state)
    }

    private fun endSession(s: Session) {
        if (session !== s) return
        stopLive(s)
        session = null
        CaptureService.release(s.id)
        bubble?.setState(BubbleController.State.IDLE)
    }

    /** Cancel discards the take and invalidates every later result for the session. */
    private fun cancelSession() {
        val s = session ?: return
        AppLog.d(TAG, "session ${s.id} cancelled")
        if (s.recording) CaptureService.cancel(this)
        s.cancelRequest?.invoke()
        // A take cancelled while processing stays in history, so it can be retried.
        finish(s, HistoryStatus.CANCELLED)
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
        AppLog.d(TAG, "service destroyed")
        cancelSession()
        if (CaptureService.listener === this) CaptureService.listener = null
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        network.shutdownNow()
        preview?.dismiss()
        liveText?.hide()
        bubble?.hide()
        liveText = null
        preview = null
        bubble = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "openWisprFlow"
        const val COMMIT_CONFIRM_TIMEOUT_MS = 1_000L
        const val LIVE_FINISH_TIMEOUT_MS = 8_000L
        const val NOT_CONFIRMED = "insert not confirmed"
    }
}

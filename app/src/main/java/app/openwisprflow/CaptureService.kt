package app.openwisprflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

/**
 * Microphone foreground service (HLD section 4, Audio). It owns the one [AudioCapture], shows the
 * recording notification with Stop and Cancel, and reports to [listener] on the main thread.
 * Capture never restarts on its own.
 *
 * After a take the service stays in the foreground, without the microphone, until the session calls
 * [release]. Without this the app is a background process during the gateway requests, and Samsung's
 * app freezer suspends it about 6 seconds later, in the middle of the request.
 */
class CaptureService : Service() {

    interface Listener {
        fun onTakeFinished(sessionId: Long, take: Take)
        fun onCaptureFailed(sessionId: Long, error: Throwable)
        fun onCaptureCancelled(sessionId: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    private var capture: AudioCapture? = null
    private var sessionId = NO_SESSION
    private var processingId = NO_SESSION

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getLongExtra(EXTRA_SESSION, NO_SESSION))
            ACTION_STOP -> finish(cancel = false)
            ACTION_CANCEL -> finish(cancel = true)
        }
        return START_NOT_STICKY
    }

    private fun start(id: Long) {
        if (capture != null) finish(cancel = true)
        sessionId = id
        processingId = NO_SESSION
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val file = File(takesDir(this), "take-$id-${System.currentTimeMillis()}.wav")
            capture = AudioCapture(
                file,
                MAX_DURATION_MS,
                onMaxDuration = { main.post { if (sessionId == id) finish(cancel = false) } },
                onFrame = frameListener,
            ).also { it.start() }
            MicDiagnostics.recordStart(this, null)
            AppLog.d(TAG, "capture started for session $id")
        } catch (e: Exception) {
            // SecurityException when the while-in-use microphone check fails, or AudioRecord errors.
            AppLog.w(TAG, "capture failed to start: ${e.javaClass.simpleName}")
            MicDiagnostics.recordStart(this, e)
            capture = null
            stopSelf()
            listener?.onCaptureFailed(id, e)
        }
    }

    private fun finish(cancel: Boolean) {
        val id = sessionId
        val c = capture ?: run {
            // A late Stop from the old notification must not end the hold on a running request.
            if (processingId == NO_SESSION) stopSelf()
            return
        }
        capture = null
        sessionId = NO_SESSION
        val take = c.stop()
        AppLog.d(TAG, "capture ${if (cancel) "cancelled" else "stopped"} for session $id")
        if (cancel) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            take.file.delete()
            listener?.onCaptureCancelled(id)
        } else {
            // Retention is History's job: it keeps takes for retry and deletes expired ones.
            MicDiagnostics.recordTake(this, take)
            holdForProcessing(id)
            val l = listener
            if (l != null) l.onTakeFinished(id, take) else endHold()
        }
    }

    /** Swaps the microphone type for one that lets the app keep running through the requests. */
    private fun holdForProcessing(id: Long) {
        processingId = id
        try {
            // API 33 has no short service and does not limit a microphone service without a recorder.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, buildProcessingNotification(), type)
            AppLog.d(TAG, "holding foreground for session $id")
        } catch (e: Exception) {
            // The request still runs; it can only be frozen, as it could before the hold.
            AppLog.w(TAG, "foreground hold failed: ${e.javaClass.simpleName}")
            endHold()
        }
    }

    private fun endHold() {
        processingId = NO_SESSION
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** A short service must stop when its 3 minutes are up, or Android crashes the app. */
    override fun onTimeout(startId: Int) {
        AppLog.w(TAG, "foreground hold timed out for session $processingId")
        endHold()
    }

    override fun onTimeout(startId: Int, fgsType: Int) = onTimeout(startId)

    override fun onDestroy() {
        capture?.let { it.stop().file.delete() }
        capture = null
        if (running === this) running = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.recording_title))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.recording_stop), actionIntent(ACTION_STOP)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.recording_cancel), actionIntent(ACTION_CANCEL)).build())
            .build()
    }

    private fun buildProcessingNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle(getString(R.string.processing_title))
        .setOngoing(true)
        .build()

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, CaptureService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "openWisprFlow"
        private const val ACTION_START = "app.openwisprflow.capture.START"
        private const val ACTION_STOP = "app.openwisprflow.capture.STOP"
        private const val ACTION_CANCEL = "app.openwisprflow.capture.CANCEL"
        private const val EXTRA_SESSION = "session"
        private const val NO_SESSION = -1L
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        const val MAX_DURATION_MS = 3 * 60 * 1000L

        /** The live service, so [release] never has to start one. Main thread only. */
        private var running: CaptureService? = null

        /** Set by the accessibility service; called on the main thread. */
        var listener: Listener? = null

        /** Set before [start] to receive live PCM frames for streaming; called on the capture thread. */
        @Volatile
        var frameListener: ((ByteArray) -> Unit)? = null

        fun takesDir(context: Context) = File(context.filesDir, "takes").apply { mkdirs() }

        /**
         * Starts capture. Throws if Android refuses to start a foreground service from the
         * current state (ForegroundServiceStartNotAllowedException).
         */
        fun start(context: Context, sessionId: Long) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java).setAction(ACTION_START).putExtra(EXTRA_SESSION, sessionId),
            )
        }

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun cancel(context: Context) = send(context, ACTION_CANCEL)

        /** The session is over: ends the foreground hold, if this session has one. Main thread only. */
        fun release(sessionId: Long) {
            running?.takeIf { it.processingId == sessionId }?.endHold()
        }

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, CaptureService::class.java).setAction(action))
        }
    }
}

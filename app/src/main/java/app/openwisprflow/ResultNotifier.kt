package app.openwisprflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * After every dictation or translation that produced text, a notification holds that text,
 * whether or not it went into the field: some editors take commitText without saying so, and some
 * drop it, so the text is always one tap away. Tapping the notification copies the text (the
 * Copy button does the same once expanded); History opens the full list. Only the latest result
 * is kept; older ones are in History. Silent, and its text is hidden on the lock screen. Incognito fields get no
 * notification, as they get no history (HLD 7).
 */
object ResultNotifier {

    private const val CHANNEL_ID = "results"
    /** CaptureService uses 1. */
    private const val NOTIFICATION_ID = 2
    internal const val EXTRA_TEXT = "text"

    fun show(context: Context, text: String, title: String, status: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.results_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val copy = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CopyResultReceiver::class.java).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val history = PendingIntent.getActivity(
            context,
            0,
            Intent(context, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val hidden = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setSubText(status)
            .build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setSubText(status)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(copy)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.preview_copy), copy).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.history_title), history).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .setShowWhen(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}

/** The notification's Copy button. Android 13+ confirms the copy itself, so no toast. */
class CopyResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(ResultNotifier.EXTRA_TEXT) ?: return
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        AppLog.d("openWisprFlow", "result copied from notification, ${text.length} chars")
    }
}

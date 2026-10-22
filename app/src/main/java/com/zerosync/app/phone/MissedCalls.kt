package com.zerosync.app.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import com.zerosync.app.R

private const val CHANNEL = "missed_calls"
private const val NOTIFICATION_ID = 1002
private const val ACTION_CALL_BACK = "com.zerosync.app.CALL_BACK"
private const val ACTION_DISMISS = "com.zerosync.app.DISMISS_MISSED"
private const val EXTRA_NUMBER = "number"

/**
 * Missed calls. Because ZeroSync declares this receiver, Android sends the missed-call event
 * here instead of showing its own notification. We show ZeroSync's notification:
 * "Missed call · number" with Call back. Exported only because the system sends it
 * (required for the default Phone app); it only ever shows or clears our notification.
 */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        if (count <= 0) {
            nm.cancel(NOTIFICATION_ID) // missed calls were cleared
            return
        }
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)?.takeIf { it.isNotBlank() }

        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
        // Tap = open the dial pad with the number filled in.
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, DialerActivity::class.java)
                .setData(number?.let { Uri.fromParts("tel", it, null) })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, MissedCallActionReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (count == 1) "Missed call" else "$count missed calls")
            .setContentText(number ?: "Unknown number")
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setDeleteIntent(dismiss) // swiped away = missed calls seen
            .setAutoCancel(true)
        if (number != null) {
            val callBack = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, MissedCallActionReceiver::class.java).setAction(ACTION_CALL_BACK).putExtra(EXTRA_NUMBER, number),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, "Call back", callBack).build())
        }
        nm.notify(NOTIFICATION_ID, builder.build())
    }
}

/** Buttons on our missed-call notification. Not exported: only our own notification can use it. */
class MissedCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        // Tell Android the missed calls were seen (allowed for the default Phone app).
        runCatching { telecom.cancelMissedCallsNotification() }
        if (intent.action == ACTION_CALL_BACK) {
            val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
            runCatching { telecom.placeCall(Uri.fromParts("tel", number, null), null) }
        }
    }
}

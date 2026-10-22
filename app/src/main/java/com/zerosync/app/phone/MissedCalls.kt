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
import androidx.core.content.edit
import com.zerosync.app.R

private const val CHANNEL = "missed_calls"
private const val GROUP = "missed_calls_group"
private const val SUMMARY_ID = 1002
private const val ACTION_OPENED = "com.zerosync.app.MISSED_OPENED"
private const val EXTRA_NUMBER = "number"

/**
 * Missed AND declined calls, one notification per number with how many times they called,
 * e.g. "98XXXXXXXX (3)". Several numbers stack under one group, so 3+ are visible at once.
 * Tap one = (unlock if locked, Android asks) → ZeroSync's dial pad with that number filled in.
 * Counts are kept in private storage until that notification is opened or swiped away.
 */
object MissedCallLog {
    private fun prefs(context: Context) = context.getSharedPreferences("missed_calls", Context.MODE_PRIVATE)

    /** Called by [CallService] when an incoming call ends without being answered. */
    fun add(context: Context, rawNumber: String) {
        // +9779841000000 and 9841000000 are the same person: one notification, one count.
        val number = localNumber(rawNumber).ifEmpty { rawNumber }
        val count = prefs(context).getInt(number, 0) + 1
        prefs(context).edit { putInt(number, count) }
        show(context, number, count)
    }

    /** The notification for [number] was opened or swiped away: forget its count. */
    fun clear(context: Context, rawNumber: String) {
        val number = localNumber(rawNumber).ifEmpty { rawNumber }
        prefs(context).edit { remove(number) }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(idFor(number))
        if (prefs(context).all.isEmpty()) nm.cancel(SUMMARY_ID)
    }

    private fun idFor(number: String) = 2000 + (number.hashCode() and 0xFFFFF)

    private fun show(context: Context, number: String, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
        val id = idFor(number)

        // Tap: open the dial pad with this number (Android asks to unlock first if locked).
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, DialerActivity::class.java)
                .setData(Uri.fromParts("tel", number, null))
                .putExtra(DialerActivity.EXTRA_FROM_MISSED, number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val swiped = PendingIntent.getBroadcast(
            context, id,
            Intent(context, MissedCallActionReceiver::class.java).setAction(ACTION_OPENED).putExtra(EXTRA_NUMBER, number),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            id,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                // Saved contact: its name; else the number.
                .setContentTitle((ContactsStore.find(context, number)?.name ?: number).let { if (count > 1) "$it ($count)" else it })
                .setContentText(if (count > 1) "Missed $count calls" else "Missed call")
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setContentIntent(open)
                .setDeleteIntent(swiped)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .build(),
        )
        // Summary that holds the per-number notifications together.
        nm.notify(
            SUMMARY_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Missed calls")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build(),
        )
    }
}

/**
 * Android's own missed-call event. Declaring this makes the system skip ITS notification;
 * ours comes from [CallService] (which also counts declined calls). Exported only because the
 * system sends it (default Phone app requirement); it does nothing else.
 */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        // Mark Android's missed-call count as seen so it never shows its own badge.
        runCatching { context.getSystemService(TelecomManager::class.java).cancelMissedCallsNotification() }
    }
}

/** Swipe-away of our notification. Not exported: only our own notification can use it. */
class MissedCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        MissedCallLog.clear(context, number)
    }
}

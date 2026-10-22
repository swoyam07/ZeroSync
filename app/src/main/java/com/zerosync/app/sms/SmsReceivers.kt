package com.zerosync.app.sms

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import com.zerosync.app.R
import com.zerosync.app.phone.ContactsStore
import com.zerosync.app.phone.SpamList
import com.zerosync.app.phone.localNumber

/*
 * Parts Android requires from every default SMS app. The exported ones can only be called by
 * the system (each is protected by a system-only permission in the manifest).
 */

/** A text arrived. Save it to the phone's message database and notify (name/number only). */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val subId = intent.getIntExtra("subscription", -1)
        // A long text comes in several parts: join them per sender.
        parts.groupBy { it.displayOriginatingAddress.orEmpty() }.forEach { (address, msgs) ->
            val body = msgs.joinToString("") { it.displayMessageBody.orEmpty() }
            SmsStore.saveIncoming(context, address, body, msgs.first().timestampMillis, subId)
            // Spam numbers: saved, but no notification.
            if (!SpamList.isSpam(context, address)) SmsNotifications.show(context, address)
        }
    }
}

/**
 * Picture / group messages (MMS) arrive here. SAFETY: ignored on purpose — nothing is
 * downloaded, decoded or opened (attachments are a classic attack route).
 */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Result of sending: mark the saved text as sent or failed. Not exported. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SmsStore.markSent(context, intent.data, ok = resultCode == Activity.RESULT_OK)
    }
}

/** "Reply with a message" from a call screen (system-only). Sends the quick text. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.schemeSpecificPart.orEmpty()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (address.isNotBlank() && text.isNotBlank()) {
            Thread { SmsStore.send(this, address, text); stopSelf(startId) }.start()
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}

/**
 * Message notifications: one per sender, "Ram" / "9841234567" + "2 new messages".
 * The message TEXT is never put in the notification, so the lock screen shows only who.
 */
object SmsNotifications {
    private const val CHANNEL = "messages"

    private fun idFor(address: String) = 3000 + (localNumber(address).hashCode() and 0xFFFFF)

    fun show(context: Context, address: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
        val who = ContactsStore.find(context, address)?.name ?: localNumber(address).ifEmpty { address }
        val count = SmsStore.unreadFrom(context, address).coerceAtLeast(1)
        val open = PendingIntent.getActivity(
            context, idFor(address),
            Intent(context, MessagesActivity::class.java)
                .putExtra(MessagesActivity.EXTRA_ADDRESS, address)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            idFor(address),
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(who)
                .setContentText(if (count > 1) "$count new messages" else "New message")
                .setNumber(count)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC) // safe: no message text inside
                // No system-suggested "Open link" / "Copy code" buttons on the notification.
                .setAllowSystemGeneratedContextualActions(false)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun clear(context: Context, address: String) {
        context.getSystemService(NotificationManager::class.java).cancel(idFor(address))
    }
}

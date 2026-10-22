package com.zerosync.app.sms

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.zerosync.app.phone.localNumber

/** One text message. [incoming] = received, else sent by you. */
data class Sms(val id: Long, val address: String, val body: String, val date: Long, val incoming: Boolean, val read: Boolean, val failed: Boolean)

/** One conversation: the newest message of a thread + how many are unread. */
data class Conversation(val threadId: Long, val address: String, val last: Sms, val unread: Int)

/**
 * Text messages live in the phone's own message database (not the SIM, not ZeroSync's files).
 * As the default SMS app, ZeroSync is the one that writes to it: received texts and the texts
 * you send. Text only (no pictures / group MMS). Call from a background thread.
 */
object SmsStore {
    /** Bumped when messages change (new text, sent, read) so screens reload. */
    var version by mutableIntStateOf(0)
        private set

    fun changed() { version++ }

    private val uri = Telephony.Sms.CONTENT_URI
    private val columns = arrayOf(
        Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
        Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ,
    )

    fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Same person = same digits after removing +977 etc. ("+9779841…" and "9841…" are one chat). */
    private fun keyOf(address: String) = localNumber(address).ifEmpty { address }

    /** Latest 3000 messages, newest first. */
    private fun recent(context: Context): List<Sms> {
        if (!canRead(context)) return emptyList()
        val out = ArrayList<Sms>()
        // Note: on HyperOS a hidden Xiaomi switch can make this return 0 rows (see docs/device.md).
        runCatching {
            context.contentResolver.query(uri, columns, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 3000) out.add(c.toSms())
            }
        }
        return out
    }

    /**
     * All conversations, newest first. Grouped by PERSON, not by Android's thread: Android
     * starts a new thread when the same number is written differently (with/without +977).
     */
    fun conversations(context: Context): List<Conversation> {
        val latest = LinkedHashMap<String, Sms>()
        val unread = HashMap<String, Int>()
        for (sms in recent(context)) {
            val key = keyOf(sms.address)
            if (key !in latest) latest[key] = sms
            if (sms.incoming && !sms.read) unread[key] = (unread[key] ?: 0) + 1
        }
        return latest.map { (k, s) -> Conversation(k.hashCode().toLong(), s.address, s, unread[k] ?: 0) }
    }

    /** All messages with this person (any number form), oldest first. */
    fun messages(context: Context, address: String): List<Sms> {
        val key = keyOf(address)
        return recent(context).filter { keyOf(it.address) == key }.reversed()
    }

    fun unreadCount(context: Context): Int {
        if (!canRead(context)) return 0
        return context.contentResolver.query(
            uri, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms.READ} = 0", null, null,
        )?.use { it.count } ?: 0
    }

    /** Unread texts from one sender (any number form). */
    fun unreadFrom(context: Context, address: String): Int =
        messages(context, address).count { it.incoming && !it.read }

    /** Mark every unread text from this person as read. */
    fun markRead(context: Context, address: String) {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        messages(context, address).filter { it.incoming && !it.read }.forEach { m ->
            runCatching { context.contentResolver.update(uri, values, "${Telephony.Sms._ID} = ?", arrayOf(m.id.toString())) }
        }
        changed()
    }

    fun delete(context: Context, smsId: Long) {
        runCatching { context.contentResolver.delete(uri, "${Telephony.Sms._ID} = ?", arrayOf(smsId.toString())) }
        changed()
    }

    /** A received text: save it to the inbox (only the default SMS app does this). */
    fun saveIncoming(context: Context, address: String, body: String, date: Long, subId: Int) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }
        changed()
    }

    /**
     * Send a text through the SIM (long texts are split automatically) and save it as "sent".
     * If the network refuses it, [SmsSentReceiver] marks it failed.
     */
    fun send(context: Context, address: String, body: String) {
        if (body.isBlank() || address.isBlank()) return
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        }
        val row = runCatching { context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) }.getOrNull()
        changed()
        val sent = PendingIntent.getBroadcast(
            context, (row?.lastPathSegment?.toIntOrNull() ?: 0),
            Intent(context, SmsSentReceiver::class.java).setData(row),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sms = smsManager(context)
        val parts = sms.divideMessage(body)
        runCatching {
            if (parts.size == 1) sms.sendTextMessage(address, null, body, sent, null)
            else sms.sendMultipartTextMessage(address, null, parts, ArrayList(List(parts.size) { i -> if (i == parts.lastIndex) sent else null }), null)
        }.onFailure { markSent(context, row, ok = false) }
    }

    internal fun markSent(context: Context, row: android.net.Uri?, ok: Boolean) {
        row ?: return
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, if (ok) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED)
        }
        runCatching { context.contentResolver.update(row, values, null, null) }
        changed()
    }

    /** SMS sender for the default SIM; if the phone asks each time, the first active SIM. */
    private fun smsManager(context: Context): SmsManager {
        val base = context.getSystemService(SmsManager::class.java)
        var sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            sub = runCatching {
                context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
            }.getOrNull() ?: return base
        }
        return base.createForSubscriptionId(sub)
    }

    private fun android.database.Cursor.toSms(): Sms {
        val type = getInt(5)
        return Sms(
            id = getLong(0),
            address = getString(2).orEmpty(),
            body = getString(3).orEmpty(),
            date = getLong(4),
            incoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = getInt(6) == 1,
            failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED,
        )
    }
}

package com.zerosync.app.calendar

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.zerosync.app.R
import com.zerosync.app.alarm.AlarmService
import com.zerosync.app.alarm.TimeActivity
import com.zerosync.app.phone.Vault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * One calendar event. Date = [year]/[month] (0-11)/[day]. [allDay] rings at 8:00 AM.
 * [remindDays]: 0 none, 1, 3 or 7 days before. [doneFor] = the occurrence (ms) that was
 * marked Done (one-time: finished; yearly: only that year).
 */
data class Event(
    val id: Int = 0,
    val title: String = "",
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int = 8,
    val minute: Int = 0,
    val allDay: Boolean = false,
    val note: String = "",
    val yearly: Boolean = false,
    val remindDays: Int = 1,
    val doneFor: Long = 0L,
)

/** Hour used for all-day events and for when daily reminders appear. */
const val MORNING_HOUR = 8

/**
 * Events: private + encrypted (same hardware-backed key as contacts), never in the phone's
 * shared calendar. Scheduling uses the approved alarm scheduler; nothing runs in between.
 */
object EventStore {
    var version by mutableIntStateOf(0)
        private set

    private fun file(context: Context) = File(context.filesDir, "calendar").apply { mkdirs() }.let { File(it, "events.bin") }

    fun all(context: Context): List<Event> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = runCatching { JSONArray(String(Vault.decrypt(f.readBytes()))) }.getOrDefault(JSONArray())
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Event(
                o.getInt("id"), o.getString("t"), o.getInt("y"), o.getInt("mo"), o.getInt("d"),
                o.getInt("h"), o.getInt("mi"), o.optBoolean("all"), o.optString("n"), o.optBoolean("yr"),
                o.optInt("rd", 0), o.optLong("done", 0L),
            )
        }
    }

    fun get(context: Context, id: Int) = all(context).firstOrNull { it.id == id }

    private fun write(context: Context, list: List<Event>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("t", e.title).put("y", e.year).put("mo", e.month).put("d", e.day)
                    .put("h", e.hour).put("mi", e.minute).put("all", e.allDay).put("n", e.note).put("yr", e.yearly)
                    .put("rd", e.remindDays).put("done", e.doneFor),
            )
        }
        val f = file(context)
        val tmp = File(f.parentFile, "events.tmp")
        tmp.writeBytes(Vault.encrypt(arr.toString().toByteArray()))
        tmp.renameTo(f)
        version++
    }

    fun save(context: Context, event: Event): Event {
        val e = if (event.id == 0) event.copy(id = (all(context).maxOfOrNull { it.id } ?: 0) + 1) else event
        write(context, all(context).filter { it.id != e.id } + e)
        EventScheduler.update(context, e)
        return e
    }

    fun delete(context: Context, id: Int) {
        EventScheduler.cancel(context, id)
        write(context, all(context).filter { it.id != id })
    }

    /**
     * Remove one-time events that are over (their day has passed, or Done / stopped).
     * Yearly events are kept; they simply move to next year.
     */
    fun cleanup(context: Context) {
        val list = all(context)
        val over = list.filter { !it.yearly && EventScheduler.occurrence(it) == null }
        if (over.isEmpty()) return
        over.forEach { EventScheduler.cancel(context, it.id) }
        write(context, list - over.toSet())
    }

    /** Done (from the notification) or rang and stopped: finish this occurrence. */
    fun finish(context: Context, id: Int) {
        val e = get(context, id) ?: return
        val occ = EventScheduler.occurrence(e) ?: return
        save(context, e.copy(doneFor = occ))
    }
}

object EventScheduler {
    private const val RING = 200_000
    private const val SNOOZE = 300_000
    private const val REMIND = 400_000
    private const val CHANNEL = "event_reminders"
    private fun notifId(id: Int) = 5000 + id

    /** Event time (ms) of this occurrence: date + time (all day = 8:00 AM). */
    private fun timeOf(e: Event, year: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, e.month, e.day, if (e.allDay) MORNING_HOUR else e.hour, if (e.allDay) 0 else e.minute)
    }.timeInMillis

    /**
     * The occurrence still to come (not Done): one-time = its date (if not done);
     * yearly = this year's or next year's. null = nothing left (finished one-time event).
     * "To come" includes the whole event day, so the reminder stays up on the day itself.
     */
    fun occurrence(e: Event, now: Long = System.currentTimeMillis()): Long? {
        if (!e.yearly) {
            val t = timeOf(e, e.year)
            return if (e.doneFor == t || endOfDay(t) < now) null else t
        }
        var y = Calendar.getInstance().get(Calendar.YEAR) - 1
        repeat(3) {
            val t = timeOf(e, y)
            if (t != e.doneFor && endOfDay(t) >= now) return t
            y++
        }
        return null
    }

    private fun endOfDay(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
    }.timeInMillis

    /** When the fixed reminder should appear: N days before, at 8:00 AM. */
    private fun reminderStart(e: Event, occ: Long): Long = Calendar.getInstance().apply {
        timeInMillis = occ
        add(Calendar.DAY_OF_YEAR, -e.remindDays)
        set(Calendar.HOUR_OF_DAY, MORNING_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun ringIntent(context: Context, id: Int, code: Int) = PendingIntent.getForegroundService(
        context, code,
        Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_RING).putExtra(AlarmService.EXTRA_EVENT, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun remindIntent(context: Context, id: Int) = PendingIntent.getBroadcast(
        context, REMIND + id,
        Intent(context, EventReminderReceiver::class.java).setAction(EventReminderReceiver.ACTION_SHOW).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openIntent(context: Context) = PendingIntent.getActivity(
        context, 7, Intent(context, TimeActivity::class.java).putExtra(TimeActivity.EXTRA_TAB, 0),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** (Re)plan an event: ring at its time, and the fixed reminder from N days before. */
    fun update(context: Context, e: Event) {
        cancel(context, e.id)
        val occ = occurrence(e) ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        if (occ > now) runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(occ, openIntent(context)), ringIntent(context, e.id, RING + e.id)) }
        if (e.remindDays > 0) {
            val start = reminderStart(e, occ)
            if (start <= now) showReminder(context, e, occ)
            else runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, remindIntent(context, e.id)) }
        }
    }

    fun snooze(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val t = System.currentTimeMillis() + com.zerosync.app.alarm.SNOOZE_MINUTES * 60_000L
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(t, openIntent(context)), ringIntent(context, id, SNOOZE + id)) }
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(ringIntent(context, id, RING + id))
        am.cancel(ringIntent(context, id, SNOOZE + id))
        am.cancel(remindIntent(context, id))
        context.getSystemService(NotificationManager::class.java).cancel(notifId(id))
    }

    fun rescheduleAll(context: Context) = EventStore.all(context).forEach { update(context, it) }

    /**
     * The fixed reminder. Can't be cleared: Android lets people swipe "ongoing" notifications
     * away now, so a swipe simply puts it back. Only Done (or deleting the event) removes it.
     * Lock screen shows just "Event reminder" (title hidden until unlocked).
     */
    fun showReminder(context: Context, e: Event, occ: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Event reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val whenText = java.text.SimpleDateFormat(if (e.allDay) "EEE d MMM" else "EEE d MMM · h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(occ))
        fun action(act: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, EventReminderReceiver::class.java).setAction(act).putExtra("id", e.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Event reminder")
            .build()
        nm.notify(
            notifId(e.id),
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(e.title.ifBlank { "Event" })
                .setContentText(whenText)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setContentIntent(openIntent(context))
                .setDeleteIntent(action(EventReminderReceiver.ACTION_SHOW, 600_000 + e.id)) // swiped away = show again
                .addAction(Notification.Action.Builder(null, "Done", action(EventReminderReceiver.ACTION_DONE, 700_000 + e.id)).build())
                .build(),
        )
    }
}

/** Shows the fixed reminder (at its start time, or again after a swipe) and handles Done. Not exported. */
class EventReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SHOW = "com.zerosync.app.calendar.SHOW"
        const val ACTION_DONE = "com.zerosync.app.calendar.DONE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val e = EventStore.get(context, id) ?: return
        when (intent.action) {
            ACTION_SHOW -> EventScheduler.occurrence(e)?.let { EventScheduler.showReminder(context, e, it) }
            ACTION_DONE -> EventStore.finish(context, id) // also cancels the ring for this occurrence
        }
    }
}

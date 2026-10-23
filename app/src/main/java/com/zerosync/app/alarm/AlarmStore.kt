package com.zerosync.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * One alarm. [days]: Calendar.SUNDAY..SATURDAY (1..7); empty = ring once.
 * [soundUri] null = the phone's default alarm tone, else a song from your music.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val soundUri: String? = null,
    val soundName: String = "Default alarm",
)

/** 12-hour time like "6:05 PM" (never 18:05). */
val Alarm.time12: String
    get() = "%d:%02d %s".format(if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")

/** Snooze length. */
const val SNOOZE_MINUTES = 5

/** Alarms in ZeroSync's private storage + scheduling with Android's alarm clock service. */
object AlarmStore {
    var version by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun all(context: Context): List<Alarm> {
        val arr = runCatching { JSONArray(prefs(context).getString("list", "[]")) }.getOrDefault(JSONArray())
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val days = o.optJSONArray("days")
            Alarm(
                id = o.getInt("id"),
                hour = o.getInt("h"),
                minute = o.getInt("m"),
                days = if (days == null) emptySet() else List(days.length()) { days.getInt(it) }.toSet(),
                enabled = o.optBoolean("on", true),
                soundUri = o.optString("uri").ifBlank { null },
                soundName = o.optString("sound", "Default alarm"),
            )
        }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun get(context: Context, id: Int) = all(context).firstOrNull { it.id == id }

    private fun write(context: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().put("id", a.id).put("h", a.hour).put("m", a.minute)
                    .put("days", JSONArray(a.days.sorted())).put("on", a.enabled)
                    .put("uri", a.soundUri ?: "").put("sound", a.soundName),
            )
        }
        prefs(context).edit { putString("list", arr.toString()) }
        version++
    }

    /** Save (new or edited) and (re)schedule it. */
    fun save(context: Context, alarm: Alarm): Alarm {
        val a = if (alarm.id == 0) alarm.copy(id = (all(context).maxOfOrNull { it.id } ?: 0) + 1) else alarm
        write(context, all(context).filter { it.id != a.id } + a)
        if (a.enabled) AlarmScheduler.schedule(context, a) else AlarmScheduler.cancel(context, a.id)
        return a
    }

    fun delete(context: Context, id: Int) {
        AlarmScheduler.cancel(context, id)
        write(context, all(context).filter { it.id != id })
    }

    /** After it rang and was stopped: repeating = set the next day; once = turn it off. */
    fun afterRing(context: Context, id: Int) {
        val a = get(context, id) ?: return
        if (a.days.isEmpty()) save(context, a.copy(enabled = false)) else AlarmScheduler.schedule(context, a)
    }
}

/** Uses Android's alarm-clock service: exact, wakes the phone, nothing runs in between. */
object AlarmScheduler {
    private const val SNOOZE_OFFSET = 100_000 // request codes for snoozes

    /** Next time this alarm should ring (ms), from now. */
    fun nextTime(a: Alarm, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        if (a.days.isNotEmpty()) {
            repeat(7) { if (c.get(Calendar.DAY_OF_WEEK) !in a.days) c.add(Calendar.DAY_OF_YEAR, 1) }
        }
        return c.timeInMillis
    }

    private fun ringIntent(context: Context, id: Int, requestCode: Int) = PendingIntent.getForegroundService(
        context, requestCode,
        Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_RING).putExtra(AlarmService.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Tapping the alarm icon in the status bar opens ZeroSync's alarm list. */
    private fun showIntent(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, TimeActivity::class.java).putExtra(TimeActivity.EXTRA_TAB, 1),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, a: Alarm) = setAt(context, nextTime(a), ringIntent(context, a.id, a.id))

    fun snooze(context: Context, id: Int) =
        setAt(context, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, ringIntent(context, id, SNOOZE_OFFSET + id))

    private fun setAt(context: Context, time: Long, op: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        runCatching { am.setAlarmClock(AlarmManager.AlarmClockInfo(time, showIntent(context)), op) }
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(ringIntent(context, id, id))
        am.cancel(ringIntent(context, id, SNOOZE_OFFSET + id))
    }

    /** After a restart / time change: set every enabled alarm again. */
    fun rescheduleAll(context: Context) {
        AlarmStore.all(context).filter { it.enabled }.forEach { schedule(context, it) }
    }
}

/** Phone restarted / app updated / clock changed: Android forgets alarms, so set them again. */
class AlarmBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
        com.zerosync.app.calendar.EventScheduler.rescheduleAll(context) // events + fixed reminders
    }
}

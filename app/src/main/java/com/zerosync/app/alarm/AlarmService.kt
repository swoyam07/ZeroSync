package com.zerosync.app.alarm

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zerosync.app.R

/**
 * Rings an alarm (user-approved background work): started by Android at the alarm time,
 * plays the sound at ALARM volume (even in silent mode) + vibrates, and shows:
 * locked / screen off = full-screen [AlarmActivity]; unlocked = small pop-up card.
 * Stops on Stop / Snooze, or by itself after 10 minutes. Runs only while ringing.
 */
class AlarmService : Service() {
    companion object {
        const val ACTION_RING = "com.zerosync.app.alarm.RING"
        const val ACTION_STOP = "com.zerosync.app.alarm.STOP"
        const val ACTION_SNOOZE = "com.zerosync.app.alarm.SNOOZE"
        const val EXTRA_ID = "id"
        /** Calendar event id (rings like an alarm; shows the event title). */
        const val EXTRA_EVENT = "event"
        private const val CHANNEL = "alarms"
        private const val NOTIFICATION_ID = 4001
        private const val AUTO_STOP_MS = 10 * 60_000L

        /** Alarm ringing right now (null = none); the alarm screens watch this. */
        var ringing by mutableStateOf<Alarm?>(null)
            private set
    }

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_RING -> {
                val eventId = intent.getIntExtra(EXTRA_EVENT, -1)
                if (eventId > 0) ringEvent(eventId) else ring(id)
            }
            ACTION_STOP -> finish(snooze = false)
            ACTION_SNOOZE -> finish(snooze = true)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Event currently ringing (null = it's a normal alarm). */
    private var eventId = -1

    /** Calendar event: ring like an alarm, showing its title instead of the sound name. */
    private fun ringEvent(id: Int) {
        val e = com.zerosync.app.calendar.EventStore.get(this, id)
        if (e == null) { stopSelf(); return }
        eventId = id
        val h = if (e.allDay) com.zerosync.app.calendar.MORNING_HOUR else e.hour
        ring(-1, Alarm(id = -id, hour = h, minute = if (e.allDay) 0 else e.minute, soundName = e.title.ifBlank { "Event" }))
    }

    private fun ring(id: Int, given: Alarm? = null) {
        if (given == null) eventId = -1
        val alarm = given ?: AlarmStore.get(this, id)
        if (alarm == null) { stopSelf(); return }
        if (ringing != null) stopSound() // a new alarm replaces the one ringing
        ringing = alarm

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
        )
        val full = PendingIntent.getActivity(
            this, 0, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(PowerManager::class.java).isInteractive
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (alarm.id < 0) "Event ${alarm.time12}" else "Alarm ${alarm.time12}") // event title stays off the lock screen
            .setContentText("Tap to open")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, if (alarm.id < 0) "Snooze" else "Snooze $SNOOZE_MINUTES min", action(ACTION_SNOOZE, 1)).build())
            // Type-to-stop alarms get no Stop button here: only typing the text stops them.
            .apply { if (alarm.stopPhrase.isBlank()) addAction(Notification.Action.Builder(null, if (alarm.id < 0) "Complete" else "Stop", action(ACTION_STOP, 2)).build()) }
            .apply { if (!unlocked || !AlarmOverlay.canShow(this@AlarmService)) setFullScreenIntent(full, true) }
            .build()
        runCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        }.onFailure { nm.notify(NOTIFICATION_ID, notification) }

        if (unlocked && AlarmOverlay.canShow(this)) AlarmOverlay.show(this, alarm)
        playSound(alarm)
        vibrate()
        // After 10 min unanswered: stop, or snooze for type-to-stop alarms (they never stop by themselves).
        handler.postDelayed({ finish(snooze = alarm.stopPhrase.isNotBlank()) }, AUTO_STOP_MS)
    }

    private fun action(act: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, AlarmService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun playSound(alarm: Alarm) {
        val uri = alarm.soundUri?.let(Uri::parse)
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun vibrate() {
        runCatching {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 900, 600), 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        }
    }

    private fun stopSound() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching { getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel() }
        AlarmOverlay.hide(this)
    }

    /** Stop ringing. Snooze = ring again in 5 min; else repeating alarms move to their next day. */
    private fun finish(snooze: Boolean) {
        val alarm = ringing
        stopSound()
        ringing = null
        if (alarm != null) {
            if (eventId > 0) {
                // Event: snooze 5 min, or Stop = this occurrence is finished.
                if (snooze) com.zerosync.app.calendar.EventScheduler.snooze(this, eventId)
                else com.zerosync.app.calendar.EventStore.finish(this, eventId)
            } else if (snooze) AlarmScheduler.snooze(this, alarm.id) else AlarmStore.afterRing(this, alarm.id)
        }
        eventId = -1
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        ringing = null
        super.onDestroy()
    }
}

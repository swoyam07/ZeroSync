package com.zerosync.app.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.zerosync.app.R

/**
 * Android's bridge to calls. The system starts this only while ZeroSync is the default Phone
 * app AND a call exists (no polling, nothing runs otherwise). It hands calls to [CallManager]
 * and opens [CallActivity]. Ringtone and vibration are still played by the system.
 */
class CallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
    }

    override fun onDestroy() {
        CallManager.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        CallManager.added(call)
        if (call.details.state == Call.STATE_RINGING) showIncoming() else openCallScreen()
    }

    override fun onCallRemoved(call: Call) {
        // Incoming call that never connected = missed or declined: count it for that number.
        val d = call.details
        if (d.callDirection == Call.Details.DIRECTION_INCOMING && d.connectTimeMillis == 0L) {
            d.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }?.let { MissedCallLog.add(this, it) }
        }
        CallManager.removed(call)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        CallManager.audioChanged(audioState)
    }

    private fun callScreenIntent() =
        Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openCallScreen() {
        runCatching { startActivity(callScreenIntent()) }
    }

    /**
     * Incoming call notification (Android's call style):
     * - locked / screen off: the full call screen opens at once (full-screen intent);
     * - phone unlocked and in use: only a small pop-up with the number + Answer / Decline.
     */
    private fun showIncoming() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the system already rings
            },
        )
        val pending = PendingIntent.getActivity(this, 0, callScreenIntent(), PendingIntent.FLAG_IMMUTABLE)
        // Answer: answer, then open the in-call screen.
        val answer = PendingIntent.getActivity(
            this, 1, callScreenIntent().putExtra(CallActivity.EXTRA_ANSWER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Decline: just decline, nothing opens.
        val decline = PendingIntent.getBroadcast(
            this, 2, Intent(this, DeclineReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = android.app.Person.Builder().setName(CallManager.number).setImportant(true).build()
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL = "incoming_calls"
        private const val NOTIFICATION_ID = 1001
    }
}

/** "Decline" on the small incoming-call pop-up. Not exported: only our notification uses it. */
class DeclineReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        CallManager.decline()
    }
}

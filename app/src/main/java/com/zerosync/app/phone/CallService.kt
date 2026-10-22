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
     * Incoming call: Android only allows a full screen from the background through a
     * "full-screen" call notification. Locked/screen off = the call screen opens at once;
     * phone in use = a heads-up banner, tap it to open.
     */
    private fun showIncoming() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the system already rings
            },
        )
        val pending = PendingIntent.getActivity(this, 0, callScreenIntent(), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming call")
            .setContentText(CallManager.number)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        openCallScreen() // also try directly (works when allowed, e.g. app in front)
    }

    companion object {
        private const val CHANNEL = "incoming_calls"
        private const val NOTIFICATION_ID = 1001
    }
}

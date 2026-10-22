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

    // Hides ZeroSync's floating card once the call is answered / declined / gone.
    private val overlayWatcher = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state != Call.STATE_RINGING) CallOverlay.hide(this@CallService)
        }
    }

    override fun onCallAdded(call: Call) {
        // Backup for spam (normally stopped earlier by SpamScreeningService): reject quietly, no UI.
        val incoming = call.details.state == Call.STATE_RINGING
        if (incoming && SpamList.isSpam(this, call.details.handle?.schemeSpecificPart.orEmpty())) {
            call.reject(false, null)
            return
        }
        CallManager.added(call)
        call.registerCallback(overlayWatcher)
        // Any error while showing the ringing UI falls back to the full call screen, never a crash.
        if (call.details.state == Call.STATE_RINGING) {
            runCatching { showIncoming() }.onFailure { CallOverlay.hide(this); openCallScreen() }
        } else {
            openCallScreen()
        }
    }

    override fun onCallRemoved(call: Call) {
        // Incoming call that never connected = missed or declined: count it for that number.
        val d = call.details
        val spam = SpamList.isSpam(this, d.handle?.schemeSpecificPart.orEmpty())
        if (d.callDirection == Call.Details.DIRECTION_INCOMING && d.connectTimeMillis == 0L && !spam) {
            d.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }?.let { MissedCallLog.add(this, it) }
        }
        call.unregisterCallback(overlayWatcher)
        CallOverlay.hide(this)
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
        // Unlocked + screen on + "Display over other apps" allowed: ZeroSync's own card on top,
        // and the notification goes on a quiet channel so HyperOS doesn't show its pop-up too.
        // Locked / screen off: unchanged (full call screen via the full-screen notification).
        val unlocked = !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(android.os.PowerManager::class.java).isInteractive
        val useOverlay = unlocked && CallOverlay.canShow(this)
        if (useOverlay) {
            nm.createNotificationChannel(
                NotificationChannel(QUIET_CHANNEL, "Incoming calls (card shown)", NotificationManager.IMPORTANCE_LOW),
            )
            CallOverlay.show(this, CallManager.number, ContactsStore.find(this, CallManager.number))
        }
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
        // Pop-up: saved name (else number) and a picture: contact photo, or a plain circle in
        // the network color (NTC blue / Ncell purple, gray if unknown) with the first letter.
        val number = CallManager.number
        val contact = ContactsStore.find(this, number)
        val caller = android.app.Person.Builder()
            .setName(contact?.name ?: number)
            .setIcon(android.graphics.drawable.Icon.createWithBitmap(callerAvatar(number, contact)))
            .setImportant(true)
            .build()
        val notification = if (useOverlay) {
            // Card on screen: a plain quiet notification. (Android only allows the call style
            // together with a full-screen intent, which would cover the screen.)
            Notification.Builder(this, QUIET_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(contact?.name ?: number)
                .setContentText("Incoming call")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(pending)
                .build()
        } else {
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .build()
        }
        // A notification problem must never crash the call (Android would switch dialers).
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
            .onFailure { if (!useOverlay) openCallScreen() }
    }

    private fun callerAvatar(number: String, contact: Contact?): android.graphics.Bitmap {
        contact?.takeIf { it.hasPhoto }?.let { c -> ContactsStore.photo(this, c.id)?.let { return it } }
        val size = 192
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = (carrierOf(number)?.color ?: 0xFF3A3A3A).toInt()
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val letter = contact?.name?.take(1)?.uppercase()
        if (!letter.isNullOrEmpty()) {
            paint.color = android.graphics.Color.WHITE
            paint.textSize = size * 0.45f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
        return bmp
    }

    companion object {
        private const val CHANNEL = "incoming_calls"
        private const val QUIET_CHANNEL = "incoming_calls_quiet"
        private const val NOTIFICATION_ID = 1001
    }
}

/** "Decline" on the small incoming-call pop-up. Not exported: only our notification uses it. */
class DeclineReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        CallManager.decline()
    }
}

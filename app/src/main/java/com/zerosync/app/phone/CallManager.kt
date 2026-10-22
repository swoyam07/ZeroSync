package com.zerosync.app.phone

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared state of the current phone call, filled by [CallService] (Android's call bridge)
 * and shown by [CallActivity]. Only one call is shown: the newest one still going.
 */
object CallManager {
    var call by mutableStateOf<Call?>(null)
        private set
    /** Call.STATE_RINGING, STATE_ACTIVE, STATE_HOLDING, STATE_DIALING, STATE_DISCONNECTED ... */
    var state by mutableIntStateOf(Call.STATE_DISCONNECTED)
        private set
    /** When the call was answered (ms since boot), for the timer. 0 = not connected yet. */
    var connectedAt by mutableStateOf(0L)
        private set
    var muted by mutableStateOf(false)
        private set
    var speaker by mutableStateOf(false)
        private set

    internal var service: CallService? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(c: Call, newState: Int) = update(c)
    }

    internal fun added(c: Call) {
        call?.unregisterCallback(callback)
        call = c
        c.registerCallback(callback)
        connectedAt = 0L
        update(c)
    }

    internal fun removed(c: Call) {
        c.unregisterCallback(callback)
        if (call == c) {
            call = null
            state = Call.STATE_DISCONNECTED
            connectedAt = 0L
        }
    }

    private fun update(c: Call) {
        if (c != call) return
        state = c.details.state
        if (state == Call.STATE_ACTIVE && connectedAt == 0L) connectedAt = android.os.SystemClock.elapsedRealtime()
    }

    internal fun audioChanged(audio: CallAudioState) {
        muted = audio.isMuted
        speaker = audio.route == CallAudioState.ROUTE_SPEAKER
    }

    /** Number shown on screen ("Unknown" if hidden). */
    val number: String
        get() = call?.details?.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: "Unknown"

    /** Two SIMs: the user picked which SIM to call from. */
    fun chooseSim(handle: android.telecom.PhoneAccountHandle) = call?.phoneAccountSelected(handle, false)

    fun answer() = call?.answer(VideoProfile.STATE_AUDIO_ONLY)
    fun decline() = call?.reject(false, null)
    fun declineWithMessage(text: String) = call?.reject(true, text)
    fun hangUp() = call?.disconnect()
    fun toggleHold() = call?.let { if (state == Call.STATE_HOLDING) it.unhold() else it.hold() }
    fun toggleMute() = service?.setMuted(!muted)
    fun toggleSpeaker() = service?.setAudioRoute(
        if (speaker) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER,
    )

    /** Keypad during a call (e.g. "press 1"): sends the tone to the other side. */
    fun pressKey(c: Char) {
        call?.playDtmfTone(c)
        call?.stopDtmfTone()
    }
}

package com.zerosync.app.phone

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Vibrates while a call is ringing, always: screen on or off, locked or unlocked
 * (even if the phone's own "vibrate when ringing" is off). Stops when the call is answered,
 * declined or ends. Spam calls never get here (rejected before ringing).
 */
object CallVibration {
    // Off 0 ms, buzz 800 ms, pause 700 ms, repeated from the start.
    private val pattern = longArrayOf(0, 800, 700)

    fun start(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE),
            )
        }
    }

    fun stop(context: Context) {
        runCatching { context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel() }
    }
}

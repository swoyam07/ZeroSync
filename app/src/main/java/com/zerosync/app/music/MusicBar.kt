package com.zerosync.app.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import com.zerosync.app.MainActivity
import com.zerosync.app.R

/**
 * Music bar (user chose "bar only", 2026-11-04): Android's own media notification + lock-screen
 * player with song name, cover, play/pause, previous, next and a time bar. Built-in Android
 * parts only (MediaSession + MediaStyle), no library, and NO background service: it lives as
 * long as the app does. The buttons call back into [controls].
 */
class MusicBar(private val context: Context, private val controls: Controls) {

    interface Controls {
        fun play()
        fun pause()
        fun next()
        fun previous()
        fun seekTo(ms: Long)
    }

    private val nm = context.getSystemService(NotificationManager::class.java)

    private val session = MediaSession(context, "ZeroSync music").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = controls.play()
            override fun onPause() = controls.pause()
            override fun onSkipToNext() = controls.next()
            override fun onSkipToPrevious() = controls.previous()
            override fun onSeekTo(pos: Long) = controls.seekTo(pos)
        })
        isActive = true
    }

    /** Show / refresh the bar for the song now loaded. */
    fun update(title: String, artist: String, playing: Boolean, positionMs: Long, durationMs: Long, cover: Bitmap?) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0))
                .apply { cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO,
                )
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, positionMs, if (playing) 1f else 0f)
                .build(),
        )
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW))
        // Tap the bar = open ZeroSync (the Music tab is where the music is).
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(cover)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .setVisibility(Notification.VISIBILITY_PUBLIC) // full player on the lock screen
            .setOngoing(playing) // paused = can be swiped away
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    /** App closing: remove the bar and free the session. */
    fun release() {
        nm.cancel(ID)
        session.release()
    }

    private companion object {
        const val CHANNEL = "music"
        const val ID = 2001
    }
}

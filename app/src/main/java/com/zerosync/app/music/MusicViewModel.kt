package com.zerosync.app.music

import android.app.Application
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Music state + the player. The player lives here (not in a service), so music keeps
 * playing while switching tabs and with the screen off, with no notification.
 */
class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MusicRepository(app.contentResolver)
    private val audio = app.getSystemService(AudioManager::class.java)

    private val player = ExoPlayer.Builder(app)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true, // pause for calls / other audio
        )
        .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
        .setWakeMode(C.WAKE_MODE_LOCAL)    // keep CPU awake only while playing (screen off)
        .build()

    var folders by mutableStateOf(emptyList<MusicFolder>())
        private set
    /** Folder picked in the dropdown; only its songs are played. */
    var selectedFolderId by mutableStateOf<Long?>(null)
        private set
    val selectedFolder: MusicFolder? get() = folders.find { it.id == selectedFolderId }

    /** Folder whose songs are loaded in the player. Can differ from the one on screen. */
    private var playingFolderId by mutableStateOf<Long?>(null)

    /** Looking at another folder than the one playing: its songs wait for the play button. */
    val browsing: Boolean get() = selectedFolderId != playingFolderId

    /** Play button should start the shown song (instead of pause/resume). */
    val startPending: Boolean get() = previewIndex != null || browsing

    /** Songs of the selected folder = the playlist. */
    val songs: List<Song> get() = selectedFolder?.songs.orEmpty()

    var loading by mutableStateOf(false)
        private set

    var currentIndex by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** User wants music playing. Unlike [isPlaying], stays true while the next song loads. */
    var playRequested by mutableStateOf(false)
        private set

    /** Song shown while the song wheel is being turned (plays on release). Null = not turning. */
    var previewIndex by mutableStateOf<Int?>(null)
        private set
    val shownIndex: Int get() = previewIndex ?: if (browsing) 0 else currentIndex

    // Timestamp bar.
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set

    val maxVolume: Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    var volume by mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                isPlaying = player.isPlaying
                playRequested = player.playWhenReady
                refreshPosition()
                // Remember the spot on song change, play/pause and seeks (not on every tick).
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY,
                    )
                ) saveLastSpot()
            }
        })
    }

    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                val loaded = repo.folders(repo.loadSongs())
                if (loaded.map { f -> f.id to f.songs.map { it.id } } != folders.map { f -> f.id to f.songs.map { it.id } }) {
                    folders = loaded
                    // Keep the picked folder if it still exists, else the first folder.
                    val keep = loaded.find { it.id == selectedFolderId } ?: loaded.firstOrNull()
                    // First load after opening the app: go back to the last song, paused.
                    if (playingFolderId == null && restoreLastSpot(loaded)) return@launch
                    if (keep?.id != selectedFolderId) selectFolder(keep?.id ?: return@launch, autoPlay = false)
                    // Songs of the playing folder changed on disk: reload it (paused).
                    val playing = loaded.find { it.id == playingFolderId }
                    if (playing == null || player.mediaItemCount != playing.songs.size) {
                        loadIntoPlayer(keep ?: return@launch, 0)
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    /**
     * Show a folder. autoPlay = true (dropdown): play it from its first song.
     * autoPlay = false (button picker): only show it; the current song keeps playing.
     */
    fun selectFolder(id: Long, autoPlay: Boolean = true) {
        val folder = folders.find { it.id == id } ?: return
        selectedFolderId = id
        previewIndex = null
        if (autoPlay) {
            loadIntoPlayer(folder, 0)
            player.play()
        }
    }

    /** Put a folder's songs in the player, at song [index], paused. */
    private fun loadIntoPlayer(folder: MusicFolder, index: Int, positionMs: Long = 0L) {
        playingFolderId = folder.id
        player.setMediaItems(folder.songs.map { MediaItem.fromUri(it.uri) }, index, positionMs)
        player.prepare()
        player.pause()
    }

    /** Random order for next / previous / end of song, within the playing folder. */
    var shuffle by mutableStateOf(false)
        private set

    fun toggleShuffle() {
        shuffle = !shuffle
        player.shuffleModeEnabled = shuffle
    }

    /** Loop: play the same song again and again. */
    var repeatOne by mutableStateOf(false)
        private set

    fun toggleRepeatOne() {
        repeatOne = !repeatOne
        player.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Time picked with the button's left scrub (ms). Music keeps playing; applied on release. */
    var scrubMs by mutableStateOf<Long?>(null)
        private set
    private var scrubFromMs = 0L

    fun startScrub() {
        refreshPosition()
        scrubFromMs = positionMs
        scrubMs = positionMs
    }

    /** [share] = finger travel as a share of the whole song (+ forward, - back). */
    fun moveScrub(share: Float) {
        if (durationMs <= 0) return
        scrubMs = (scrubFromMs + (share * durationMs).toLong()).coerceIn(0L, durationMs)
    }

    fun endScrub() {
        scrubMs?.let { seekTo(it) }
        scrubMs = null
    }

    /** Sleep timer: minutes chosen (null = off) and when it ends (SystemClock.elapsedRealtime). */
    var sleepMinutes by mutableStateOf<Int?>(null)
        private set
    var sleepEndsAt by mutableLongStateOf(0L)
        private set
    private var sleepJob: Job? = null

    /** Start (or replace) the sleep timer; null = off. When it ends, the music pauses. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepMinutes = minutes
        if (minutes == null) return
        sleepEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        // One wait, no polling. Lives as long as the app (cleared from recents = gone).
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            player.pause()
            sleepMinutes = null
        }
    }

    // Last song + time, kept in the app's private storage (backups are off, nothing leaves).
    private val lastSpot = app.getSharedPreferences("music_last_spot", android.content.Context.MODE_PRIVATE)

    fun saveLastSpot() {
        val folder = folders.find { it.id == playingFolderId } ?: return
        val song = folder.songs.getOrNull(player.currentMediaItemIndex) ?: return
        lastSpot.edit()
            .putLong("folder", folder.id)
            .putLong("song", song.id)
            .putLong("position", player.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    /** Load the saved song at its saved time, paused. False if nothing saved or it's gone. */
    private fun restoreLastSpot(loaded: List<MusicFolder>): Boolean {
        val folder = loaded.find { it.id == lastSpot.getLong("folder", -1L) } ?: return false
        val index = folder.songs.indexOfFirst { it.id == lastSpot.getLong("song", -1L) }
        if (index < 0) return false
        selectedFolderId = folder.id
        previewIndex = null
        loadIntoPlayer(folder, index, lastSpot.getLong("position", 0L))
        return true
    }

    // A Gallery video takes the speaker, which pauses the music. Remember if music was on
    // when leaving the Music tab, and continue it on return (only while the app stays open).
    private var resumeOnReturn = false

    fun onLeaveMusicTab() {
        resumeOnReturn = player.playWhenReady
    }

    fun onReturnToMusicTab() {
        if (resumeOnReturn && !player.isPlaying) player.play()
        resumeOnReturn = false
    }

    fun playPause() {
        // A picked song or another folder is waiting: start it (replaces what plays now).
        if (startPending) return releaseSongWheel()
        if (player.isPlaying) player.pause() else player.play()
    }

    // While browsing another folder, the arrows only move through it (nothing plays).
    fun next() = if (browsing) turnSongWheel(1) else player.seekToNextMediaItem()
    fun previous() = if (browsing) turnSongWheel(-1) else player.seekToPreviousMediaItem()

    /** Timestamp bar dragged to a new time. */
    fun seekTo(ms: Long) {
        player.seekTo(ms)
        positionMs = ms
    }

    /** Called by the screen about twice a second while it is visible, to move the bar. */
    fun refreshPosition() {
        positionMs = player.currentPosition.coerceAtLeast(0L)
        durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    }

    /** Song wheel turned by one tick (+1 next, -1 previous). Wraps around the folder. */
    fun turnSongWheel(step: Int) {
        if (songs.isEmpty()) return
        previewIndex = Math.floorMod(shownIndex + step, songs.size)
    }

    /** Finger lifted from the song wheel: play the song it landed on. */
    fun releaseSongWheel() {
        val target = previewIndex ?: if (browsing) 0 else return
        previewIndex = null
        val folder = selectedFolder ?: return
        if (browsing) loadIntoPlayer(folder, target)
        else if (target != player.currentMediaItemIndex) player.seekTo(target, 0L)
        player.play()
    }

    /** Volume wheel turned by one tick: one system volume step up or down. */
    fun turnVolumeWheel(step: Int) {
        volume = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + step).coerceIn(0, maxVolume)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
    }

    /** Re-read volume (it may have changed with the side buttons). */
    fun refreshVolume() {
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    /** App left (home / app switch) with the screen still on: pause. Screen off keeps playing. */

    override fun onCleared() {
        player.release()
    }
}

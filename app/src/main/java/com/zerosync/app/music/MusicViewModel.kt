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

    /** Songs of the selected folder = the playlist. */
    val songs: List<Song> get() = selectedFolder?.songs.orEmpty()

    var loading by mutableStateOf(false)
        private set

    var currentIndex by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
        private set

    /** Song shown while the song wheel is being turned (plays on release). Null = not turning. */
    var previewIndex by mutableStateOf<Int?>(null)
        private set
    val shownIndex: Int get() = previewIndex ?: currentIndex

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
                refreshPosition()
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
                    if (keep?.id != selectedFolderId || player.mediaItemCount != keep?.songs?.size) {
                        selectFolder(keep?.id ?: return@launch, autoPlay = false)
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    /** Dropdown pick: load only that folder's songs, from its first song. */
    fun selectFolder(id: Long, autoPlay: Boolean = true) {
        val folder = folders.find { it.id == id } ?: return
        selectedFolderId = id
        previewIndex = null
        player.setMediaItems(folder.songs.map { MediaItem.fromUri(it.uri) }, 0, 0L)
        player.prepare()
        if (autoPlay) player.play()
    }

    fun playPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() = player.seekToNextMediaItem()
    fun previous() = player.seekToPreviousMediaItem()

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
        val target = previewIndex ?: return
        previewIndex = null
        if (target != player.currentMediaItemIndex) player.seekTo(target, 0L)
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
    fun onAppLeft() = player.pause()

    override fun onCleared() {
        player.release()
    }
}

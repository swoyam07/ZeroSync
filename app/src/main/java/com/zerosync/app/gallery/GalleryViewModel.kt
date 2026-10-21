package com.zerosync.app.gallery

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Shuffle = random feed of every video. Folders = browse phone folders, play in order. */
enum class GalleryMode { Shuffle, Folders }

/**
 * An open vertical video feed (Folders mode): the playlist, where to start,
 * and the folder's videos in their normal order (to rebuild when the order is toggled).
 */
data class Feed(val items: List<MediaItem>, val startIndex: Int, val source: List<MediaItem>)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app.contentResolver)

    var mode by mutableStateOf(GalleryMode.Shuffle)
        private set

    /**
     * Shuffle mode: random cycles of every video on the phone. Each cycle shows every video
     * exactly once (no repeats); when one cycle is nearly done, a new shuffle is appended.
     */
    var shuffleFeed by mutableStateOf(emptyList<MediaItem>())
        private set
    var folders by mutableStateOf(emptyList<VideoFolder>())
        private set
    var loading by mutableStateOf(false)
        private set
    var currentFolder by mutableStateOf<VideoFolder?>(null)
        private set
    var feed by mutableStateOf<Feed?>(null)
        private set

    private var allVideos = emptyList<MediaItem>()

    var autoScroll by mutableStateOf(false)

    /** Play order for both feeds. Off (default) = random, on = sequential (newest first / folder order). */
    var inOrder by mutableStateOf(false)
        private set

    /** Bumped when a feed's list is rebuilt, so the screen restarts the feed at its first page. */
    var feedVersion by mutableStateOf(0)
        private set

    // Video currently on screen in each feed (reported by the feed).
    private var shuffleIndex = 0
    private var feedIndex = 0

    /** True when Gallery covers the whole screen (nav bar hidden). */
    val isFullScreen: Boolean get() = mode == GalleryMode.Folders && feed != null

    fun selectMode(newMode: GalleryMode) {
        mode = newMode
    }

    /** Sideways swipe on the Gallery screen: left = Folders, right = Shuffle. */
    fun swipeMode(direction: Int) {
        mode = if (direction < 0) GalleryMode.Folders else GalleryMode.Shuffle
    }

    fun load() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                val videos = repo.loadVideos()
                // Reshuffle only when the videos actually changed, so returning to
                // the app doesn't jump the Shuffle feed to a different video.
                val changed = videos.map { it.uri }.toSet() != allVideos.map { it.uri }.toSet()
                if (changed) {
                    allVideos = videos
                    shuffleFeed = if (inOrder) videos else videos.shuffled()
                    shuffleIndex = 0
                    folders = repo.videoFolders(videos)
                    currentFolder = currentFolder?.let { open -> folders.find { it.bucketId == open.bucketId } }
                }
            } finally {
                loading = false
            }
        }
    }

    /** Append the next cycle (all videos: in order, or a new random order) to the Shuffle feed. */
    fun extendShuffleFeed() {
        if (allVideos.isEmpty()) return
        if (inOrder) {
            shuffleFeed = shuffleFeed + allVideos
            return
        }
        var next = allVideos.shuffled()
        // Avoid the same video twice in a row across the cycle boundary.
        if (next.size > 1 && next.first().uri == shuffleFeed.lastOrNull()?.uri) {
            next = next.drop(1) + next.first()
        }
        shuffleFeed = shuffleFeed + next
    }

    fun openFolder(folder: VideoFolder) {
        currentFolder = folder
    }

    fun closeFolder() {
        currentFolder = null
    }

    fun openVideo(item: MediaItem, source: List<MediaItem>) {
        feed = folderFeed(item, source)
        feedIndex = feed!!.startIndex
    }

    fun onShufflePage(index: Int) {
        shuffleIndex = index
    }

    fun onFeedPage(index: Int) {
        feedIndex = index
    }

    /** Switch random <-> sequential. The video on screen stays first; the rest follow the new order. */
    fun toggleOrder() {
        inOrder = !inOrder
        shuffleFeed.getOrNull(shuffleIndex)?.let { current ->
            shuffleFeed = startingWith(current, allVideos)
            shuffleIndex = 0
        }
        feed?.let { open ->
            open.items.getOrNull(feedIndex)?.let { current ->
                feed = folderFeed(current, open.source)
                feedIndex = feed!!.startIndex
            }
        }
        feedVersion++
    }

    /** Folder feed: in order = folder order starting at [item]; random = [item] then the rest shuffled. */
    private fun folderFeed(item: MediaItem, source: List<MediaItem>): Feed =
        if (inOrder) Feed(source, source.indexOf(item).coerceAtLeast(0), source)
        else Feed(listOf(item) + (source - item).shuffled(), 0, source)

    /** [current] first, then the rest of [all]: continuing in order, or shuffled. */
    private fun startingWith(current: MediaItem, all: List<MediaItem>): List<MediaItem> {
        if (!inOrder) return listOf(current) + (all - current).shuffled()
        val i = all.indexOf(current).coerceAtLeast(0)
        return all.drop(i) + all.take(i)
    }

    fun closeFeed() {
        feed = null
    }
}

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

/** What the feeds and folders show. Default each time the app starts: Videos. */
enum class MediaFilter(val label: String) { Videos("Videos only"), Photos("Photos only"), Both("Videos and photos") }

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

    private var allVideos = emptyList<MediaItem>() // everything that passes the filter
    private var loadedVideos = emptyList<MediaItem>()
    private var loadedPhotos = emptyList<MediaItem>()

    /** Shuffle only: Videos / Photos / Both. Not saved: every app start begins with Videos only. */
    var filter by mutableStateOf(MediaFilter.Videos)
        private set

    /** Cycle Videos → Photos → Both for Shuffle. Folders are not affected. */
    fun cycleFilter() {
        filter = next(filter)
        applyFilter(force = true)
    }

    /** Inside an open folder: its own filter. Every folder opens on Both (all its content). */
    var folderFilter by mutableStateOf(MediaFilter.Both)
        private set

    fun cycleFolderFilter() {
        folderFilter = next(folderFilter)
        val folder = currentFolder ?: return
        val items = filtered(folder.videos, folderFilter)
        val start = (if (inOrder) items.firstOrNull() else items.randomOrNull())
        feed = if (start == null) Feed(emptyList(), 0, emptyList()) else folderFeed(start, items)
        feedIndex = feed!!.startIndex
        feedVersion++
    }

    private fun next(f: MediaFilter) = MediaFilter.entries[(f.ordinal + 1) % MediaFilter.entries.size]

    private fun filtered(items: List<MediaItem>, f: MediaFilter) = when (f) {
        MediaFilter.Videos -> items.filter { it.isVideo }
        MediaFilter.Photos -> items.filter { !it.isVideo }
        MediaFilter.Both -> items
    }

    /**
     * False until the user plays a video or swipes in the feed. While false, the video on
     * screen stays paused. Reset every time Gallery is opened from another tab.
     */
    var playStarted by mutableStateOf(false)
        private set

    fun startPlaying() { playStarted = true }

    /** Entering or leaving Gallery via the nav bar: the next first video starts paused. */
    fun resetToPaused() { playStarted = false; rotated = false }

    /**
     * Rotate button: true = screen turned sideways (fixed landscape, no motion sensor), until the
     * user turns it off. Off by default; leaving Gallery turns it off.
     */
    var rotated by mutableStateOf(false)
        private set

    fun toggleRotate() { rotated = !rotated }

    // Feed settings survive closing the app (private prefs; backups are off, nothing leaves).
    private val settings = app.getSharedPreferences("gallery_settings", android.content.Context.MODE_PRIVATE)

    var autoScroll by mutableStateOf(settings.getBoolean("autoScroll", false))
        private set

    fun toggleAutoScroll() {
        autoScroll = !autoScroll
        settings.edit().putBoolean("autoScroll", autoScroll).apply()
    }

    /** Play order for both feeds. Off (default) = random, on = sequential (newest first / folder order). */
    var inOrder by mutableStateOf(settings.getBoolean("inOrder", false))
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
                loadedVideos = repo.loadVideos()
                loadedPhotos = repo.loadPhotos()
                applyFilter(force = false)
            } finally {
                loading = false
            }
        }
    }

    /**
     * Rebuild the feed + folders from what passes the filter. Without [force], only when the set
     * of items changed, so returning to the app doesn't jump the Shuffle feed to another item.
     */
    private fun applyFilter(force: Boolean) {
        val everything = (loadedVideos + loadedPhotos).sortedByDescending { it.dateAdded }
        // Folders always hold ALL content (videos + photos); the Shuffle filter never touches them.
        val newFolders = repo.videoFolders(everything)
        if (newFolders.map { it.bucketId to it.videos.size } != folders.map { it.bucketId to it.videos.size }) {
            folders = newFolders
            currentFolder = currentFolder?.let { open -> folders.find { it.bucketId == open.bucketId } }
        }
        val media = filtered(everything, filter)
        val changed = force || media.map { it.uri }.toSet() != allVideos.map { it.uri }.toSet()
        if (!changed) return
        allVideos = media
        shuffleFeed = if (inOrder) media else media.shuffled()
        shuffleIndex = 0
        if (force) feedVersion++
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

    /**
     * Tap a folder: start playing right away (no grid), using the feed settings:
     * in order = its first video, random = a random one. Auto scroll applies as usual.
     */
    fun openFolder(folder: VideoFolder) {
        currentFolder = folder
        folderFilter = MediaFilter.Both // a folder always opens showing everything
        val first = (if (inOrder) folder.videos.firstOrNull() else folder.videos.randomOrNull()) ?: return
        openVideo(first, folder.videos)
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
        settings.edit().putBoolean("inOrder", inOrder).apply()
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

    /** Back from a folder's feed goes straight to the folder list. */
    fun closeFeed() {
        feed = null
        currentFolder = null
    }
}

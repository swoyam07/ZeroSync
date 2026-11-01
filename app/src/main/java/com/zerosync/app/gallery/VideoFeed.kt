package com.zerosync.app.gallery

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zerosync.app.ui.ZsIcons
import kotlinx.coroutines.launch

/**
 * Distance from the very top of the screen to the Shuffle/Folders switch.
 * Shared by every Gallery view so the switch never jumps when changing mode.
 * Measured from the screen edge (not below the status bar), because the
 * status bar is hidden in the video feed but visible in the folder views.
 */
internal val ModeSwitchTop = 48.dp

/**
 * TikTok-style vertical feed of videos and/or photos.
 * Starts paused ([playStarted] false); after the first play or swipe every video plays by itself.
 * Smooth swipes: 3 ExoPlayers (previous / current / next page), each preloaded and paused on
 * its first frame, so the next video is already on screen while you swipe.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoFeed(
    items: List<MediaItem>,
    startIndex: Int,
    autoScroll: Boolean,
    onToggleAutoScroll: () -> Unit,
    /** Off (default) = random order, on = sequential order. */
    inOrder: Boolean,
    onToggleOrder: () -> Unit,
    /** Reports the video now on screen, so order changes can keep it first. */
    onPageSettled: (Int) -> Unit,
    onClose: (() -> Unit)?,
    /** Called when the viewer gets close to the last video (used to append the next random cycle). */
    onNearEnd: (() -> Unit)? = null,
    topCenter: @Composable () -> Unit = {},
    /** Videos / Photos / Both, and the tap to change it (third bead). */
    filter: MediaFilter = MediaFilter.Videos,
    onCycleFilter: () -> Unit = {},
    /** False = keep the video paused until the user plays it or swipes. */
    playStarted: Boolean = true,
    onStartPlaying: () -> Unit = {},
    /** Rotate button: true = fixed landscape (no motion sensor). */
    rotated: Boolean = false,
    onToggleRotate: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    val firstPage = remember { pagerState.settledPage }
    // 3 players (user's choice, for TikTok-smooth swipes): previous, current and next page each
    // have their own player, already loaded and showing their first frame. Page p uses players[p % 3].
    val players = remember {
        List(3) {
            ExoPlayer.Builder(context)
                // Pause for calls/other audio apps; pause if headphones are unplugged.
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .setHandleAudioBecomingNoisy(true)
                // Keeps the CPU awake only while playing, so sound continues with the screen off.
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        }
    }
    fun playerFor(page: Int) = players[page % 3]
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    // Page the user tapped to pause (-1 = none). Tied to the page, so swiping never flashes the icon.
    var pausedOn by remember { mutableStateOf(-1) }
    val currentStarted by rememberUpdatedState(playStarted)
    val currentOnStart by rememberUpdatedState(onStartPlaying)
    val currentItems by rememberUpdatedState(items)
    val currentAutoScroll by rememberUpdatedState(autoScroll)
    fun isPhoto(i: Int) = currentItems.getOrNull(i)?.isVideo == false
    fun uriOf(player: ExoPlayer) = player.currentMediaItem?.localConfiguration?.uri

    // Per player: which video has drawn its first frame (the poster stays on top until then,
    // so a fresh surface never flashes black).
    val frameOf = remember { androidx.compose.runtime.mutableStateListOf<android.net.Uri?>(null, null, null) }

    DisposableEffect(players) {
        val listeners = players.mapIndexed { i, pl ->
            object : Player.Listener {
                override fun onRenderedFirstFrame() { frameOf[i] = uriOf(pl) }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // Auto scroll: when the CURRENT page's video ends, go to the next page.
                    val page = pagerState.settledPage
                    if (playbackState != Player.STATE_ENDED || playerFor(page) !== pl) return
                    val next = page + 1
                    if (!currentAutoScroll || next >= currentItems.size) return
                    if (!powerManager.isInteractive && currentItems[next].isVideo) {
                        // Screen off: the UI isn't drawing, so start the (already loaded) next video directly.
                        playerFor(next).play()
                    }
                    scope.launch { pagerState.scrollToPage(next) }
                }
            }.also { pl.addListener(it) }
        }
        onDispose {
            players.forEachIndexed { i, pl -> pl.removeListener(listeners[i]); pl.release() }
        }
    }

    // Rotate button: fixed landscape while on (SCREEN_ORIENTATION_LANDSCAPE ignores the tilt
    // sensor); back to portrait when turned off or when the feed closes.
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(activity, rotated) {
        activity?.requestedOrientation =
            if (rotated) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    // True full screen: hide the status bar and system nav bar while the feed is shown.
    // A swipe from the screen edge shows them briefly.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Pause when the app goes to background, resume when it returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val current = playerFor(pagerState.settledPage)
            when (event) {
                // Screen turned off (power button): keep playing, sound only.
                // Left the app (home / app switch): pause.
                // Decided at ON_STOP, not ON_PAUSE: on power-button press ON_PAUSE can fire
                // before the screen counts as off, which wrongly paused the video.
                Lifecycle.Event.ON_STOP -> if (powerManager.isInteractive) current.pause()
                Lifecycle.Event.ON_RESUME -> if (currentStarted && pausedOn != pagerState.settledPage && !isPhoto(pagerState.settledPage)) current.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // On every settled page: load the 3 nearby videos (paused on their first frame), rewind and
    // pause the ones you left, and play the one on screen.
    LaunchedEffect(pagerState.settledPage, items) {
        val page = pagerState.settledPage
        onPageSettled(page)
        // Any swipe away from the first page counts as "started": from now on videos play by themselves.
        if (page != firstPage) currentOnStart()
        for (p in (page - 1)..(page + 1)) {
            val item = items.getOrNull(p) ?: continue
            if (!item.isVideo) continue
            val pl = playerFor(p)
            if (uriOf(pl) != item.uri) {
                frameOf[p % 3] = null
                pl.setMediaItem(PlayerItem.fromUri(item.uri))
                pl.prepare()
            }
            if (p != page) { pl.playWhenReady = false; pl.seekTo(0) } // neighbours wait at the start
        }
        // A photo on screen: silence its player slot (it may still hold an old video).
        if (isPhoto(page)) playerFor(page).let { it.playWhenReady = false; it.stop(); it.clearMediaItems() }
        else {
            val pl = playerFor(page)
            pl.repeatMode = if (autoScroll) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            pausedOn = -1
            // Not started yet (just opened Gallery): show it paused on its first frame.
            pl.playWhenReady = currentStarted || page != firstPage
        }
        if (page >= items.size - 2) onNearEnd?.invoke()
    }

    // Photo + auto scroll: move on after 5 s (once playing has started).
    LaunchedEffect(pagerState.settledPage, autoScroll, playStarted) {
        val page = pagerState.settledPage
        if (autoScroll && playStarted && isPhoto(page) && page + 1 < items.size) {
            kotlinx.coroutines.delay(5_000)
            pagerState.scrollToPage(page + 1)
        }
    }

    // Auto-scroll off: loop the current video. On: go to the next one when it ends.
    LaunchedEffect(autoScroll) {
        players.forEach { it.repeatMode = if (autoScroll) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            // Keep the pages next to the current one built, so their videos are ready on screen.
            beyondViewportPageCount = 1,
            // Key by position: the same video can appear again in a later random cycle.
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pl = playerFor(page)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (!items[page].isVideo) return@detectTapGestures // photos: nothing to play
                            if (pl.isPlaying) {
                                pl.pause()
                                pausedOn = page
                            } else {
                                pl.play()
                                pausedOn = -1
                                currentOnStart()
                            }
                        }
                    },
            ) {
                val settled = page == pagerState.settledPage
                val photo = !items[page].isVideo
                if (photo) {
                    // Photo: whole picture, no cropping (black bars if the shape differs).
                    Thumbnail(items[page].uri, Modifier.fillMaxSize(), sizePx = 1440, contentScale = ContentScale.Fit)
                } else {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                            }
                        },
                        update = {
                            it.player = pl
                            // Sideways: show the whole movie (fit); upright: fill the screen (crop).
                            it.resizeMode = if (rotated) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Poster on top until THIS page's video has drawn its first frame.
                if (!photo && frameOf[page % 3] != items[page].uri) {
                    Thumbnail(
                        items[page].uri,
                        Modifier.fillMaxSize(),
                        sizePx = 720,
                        contentScale = ContentScale.Crop,
                    )
                }
                // Play icon ONLY when paused on purpose: the first video before starting, or the
                // user tapped pause. Never while a swiped-to video is about to start.
                if (!photo && settled && (pausedOn == page || (!playStarted && page == firstPage))) {
                    Icon(
                        ZsIcons.Play,
                        contentDescription = "Play",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.Center).size(64.dp),
                    )
                }
            }
        }

        // Nothing passes the filter (e.g. Photos only in a folder with just videos).
        if (items.isEmpty()) {
            androidx.compose.material3.Text(
                "Nothing here (${filter.label.lowercase()})",
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Top overlay: back (optional) · center slot (e.g. Shuffle/Folders switch).
        // Status bar is hidden here, so push the overlay down to keep it clear of the camera cutout.
        Box(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = ModeSwitchTop)) {
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(ZsIcons.Back, contentDescription = "Back", tint = Color.White)
                }
            }
            Box(Modifier.align(Alignment.TopCenter)) { topCenter() }
        }

        // Auto-scroll + order: two tap beads on a rail near the right edge, ~65% down.
        FeedToggles(
            autoScroll = autoScroll,
            inOrder = inOrder,
            onToggleAutoScroll = onToggleAutoScroll,
            onToggleOrder = onToggleOrder,
            filter = filter,
            onCycleFilter = onCycleFilter,
            rotated = rotated,
            onToggleRotate = onToggleRotate,
            modifier = Modifier
                .align(BiasAlignment(horizontalBias = 1f, verticalBias = 0.3f))
                .padding(end = 16.dp),
        )
    }
}

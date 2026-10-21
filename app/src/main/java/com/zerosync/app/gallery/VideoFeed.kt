package com.zerosync.app.gallery

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.zerosync.app.ui.PillToggle
import com.zerosync.app.ui.VerticalPill
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
 * TikTok-style vertical video feed.
 * Low resource: ONE ExoPlayer for the whole feed, attached only to the settled page.
 * Pages being swiped show a thumbnail poster instead of a live video.
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = startIndex) { items.size }
    val exoPlayer = remember {
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
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    val userPaused = remember { booleanArrayOf(false) }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
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
            when (event) {
                // Screen turned off (power button): keep playing, sound only.
                // Left the app (home / app switch): pause.
                // Decided at ON_STOP, not ON_PAUSE: on power-button press ON_PAUSE can fire
                // before the screen counts as off, which wrongly paused the video.
                Lifecycle.Event.ON_STOP -> if (powerManager.isInteractive) exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (!userPaused[0]) exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep the poster on top until the video has drawn its first frame.
    // Without this, the brand-new video surface flashes black for a moment after each swipe.
    var firstFrameShown by remember { mutableStateOf(false) }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameShown = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Load the video only once a swipe has fully settled.
    LaunchedEffect(pagerState.settledPage) {
        firstFrameShown = false
        onPageSettled(pagerState.settledPage)
        val uri = items[pagerState.settledPage].uri
        // Skip if already playing this video (auto-scroll while the screen was off).
        if (exoPlayer.currentMediaItem?.localConfiguration?.uri != uri) {
            exoPlayer.setMediaItem(PlayerItem.fromUri(uri))
            exoPlayer.prepare()
            userPaused[0] = false
            exoPlayer.play()
        }
        if (pagerState.settledPage >= items.size - 2) onNearEnd?.invoke()
    }

    // Auto-scroll off: loop the current video. On: go to the next one when it ends.
    LaunchedEffect(autoScroll) {
        exoPlayer.repeatMode = if (autoScroll) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }
    val currentAutoScroll by rememberUpdatedState(autoScroll)
    val currentItems by rememberUpdatedState(items)
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val next = pagerState.settledPage + 1
                if (playbackState == Player.STATE_ENDED && currentAutoScroll && next < currentItems.size) {
                    if (!powerManager.isInteractive) {
                        // Screen off: the UI isn't drawing, so start the next video's sound directly.
                        // The pager catches up to it when the screen turns back on.
                        exoPlayer.setMediaItem(PlayerItem.fromUri(currentItems[next].uri))
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                    scope.launch { pagerState.scrollToPage(next) }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            // Key by position: the same video can appear again in a later random cycle.
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                                userPaused[0] = true
                            } else {
                                exoPlayer.play()
                                userPaused[0] = false
                            }
                        }
                    },
            ) {
                val settled = page == pagerState.settledPage
                if (settled) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                            }
                        },
                        update = { it.player = exoPlayer },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Poster drawn above the player; removed once the video's first frame is on screen.
                if (!settled || !firstFrameShown) {
                    Thumbnail(
                        items[page].uri,
                        Modifier.fillMaxSize(),
                        sizePx = 720,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
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

        // Auto-scroll + order toggles near the right edge, ~65% down the screen: easy thumb reach.
        VerticalPill(
            Modifier
                .align(BiasAlignment(horizontalBias = 1f, verticalBias = 0.3f))
                // Close to the right edge for easy thumb reach.
                .padding(end = 12.dp),
        ) {
            PillToggle(
                enabled = autoScroll,
                onToggle = onToggleAutoScroll,
                icon = ZsIcons.AutoScroll,
                description = if (autoScroll) "Auto scroll on" else "Auto scroll off",
            )
            PillToggle(
                enabled = inOrder,
                onToggle = onToggleOrder,
                icon = if (inOrder) ZsIcons.InOrder else ZsIcons.Shuffle,
                description = if (inOrder) "Playing in order" else "Playing in random order",
            )
        }
    }
}

package com.zerosync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import android.os.PowerManager
import com.zerosync.app.music.MusicViewModel
import com.zerosync.app.music.MusicScreen
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity
import android.content.IntentFilter
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.app.KeyguardManager
import com.zerosync.app.ui.LocalNavSideGap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerosync.app.gallery.GalleryScreen
import com.zerosync.app.gallery.GalleryViewModel
import com.zerosync.app.ui.AppTab
import com.zerosync.app.phone.PhoneScreen
import com.zerosync.app.files.FilesScreen
import com.zerosync.app.files.FilesViewModel
import com.zerosync.app.camera.CameraScreen
import com.zerosync.app.camera.CameraViewModel
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.DialNavBar
import com.zerosync.app.ui.DialPreview
import com.zerosync.app.ui.theme.ZeroSyncTheme
import com.zerosync.app.ui.theme.ZsColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZeroSyncTheme { ZeroSyncApp() }
        }
    }
}

@Composable
private fun ZeroSyncApp() {
    // Open on the section the app was left in; first install = Music.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_state", Context.MODE_PRIVATE) }
    var tab by rememberSaveable {
        mutableStateOf(AppTab.entries.find { it.name == prefs.getString("lastTab", null) } ?: AppTab.Music)
    }
    LaunchedEffect(tab) { prefs.edit().putString("lastTab", tab.name).apply() }
    val gallery: GalleryViewModel = viewModel()
    val music: MusicViewModel = viewModel()
    // Music paused by a Gallery video comes back when returning to the Music tab.
    var previousTab by remember { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (previousTab == AppTab.Music && tab != AppTab.Music) music.onLeaveMusicTab()
        if (previousTab != AppTab.Music && tab == AppTab.Music) music.onReturnToMusicTab()
        previousTab = tab
    }
    val files: FilesViewModel = viewModel()

    // Lock-screen player: while music plays on the Music tab, ZeroSync may show over the
    // lock screen (power button) without unlocking, so the wheels stay usable.
    // While locked, ONLY the music player is shown: no nav bar, no other tabs (privacy).
    val activity = LocalActivity.current
    val locked = rememberKeyguardLocked()
    // playRequested (not isPlaying): isPlaying blinks off while the next song loads, which
    // used to drop the app to the lock screen and back. Also, once shown over the lock
    // screen, stay there until unlocked (pausing on the lock screen won't kick you out).
    var shownOverLock by remember { mutableStateOf(false) }
    val allowOverLock = tab == AppTab.Music && (music.playRequested || (locked && shownOverLock))
    LaunchedEffect(allowOverLock) {
        activity?.setShowWhenLocked(allowOverLock)
        shownOverLock = allowOverLock
    }
    val lockedPlayer = locked && allowOverLock

    // Music keeps playing when leaving the app (home / app switch / screen off).
    // It stops when the app is cleared from recents (the player is released with the app).
    // Opening recents always stops the screen first, so save the song + time there; the
    // next launch reopens on that song, paused.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) music.saveLastSpot()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fullScreen = (tab == AppTab.Gallery && gallery.isFullScreen) || lockedPlayer ||
        (tab == AppTab.File && files.opened != null) // document viewer uses the whole screen

    // Measure the nav bar so floating controls can use the same side gap.
    // Kept after the bar hides (full-screen feed), so controls don't jump.
    val density = LocalDensity.current
    var screenWidthPx by remember { mutableIntStateOf(0) }
    var navWidthPx by remember { mutableIntStateOf(0) }
    var navPreview by remember { mutableStateOf<AppTab?>(null) }
    val navSideGap = if (navWidthPx > 0) with(density) { ((screenWidthPx - navWidthPx) / 2).toDp() } else 28.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(ZsColors.Background)
            .onSizeChanged { screenWidthPx = it.width },
    ) {
        when (tab) {
            AppTab.Gallery -> CompositionLocalProvider(LocalNavSideGap provides navSideGap) {
                GalleryScreen(gallery)
            }
            AppTab.Music -> MusicScreen(music)
            AppTab.Camera -> CameraScreen(viewModel<CameraViewModel>())
            AppTab.File -> FilesScreen(files)
            AppTab.Contact -> PhoneScreen() // phone setup + dial pad for now
            else -> Text(
                "${tab.label}: coming soon",
                color = ZsColors.OnSurface.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // On the lock screen: the under-display fingerprint sensor sits at the bottom centre.
        // Android does not read the sensor while an app covers the lock screen, so this
        // invisible zone over it asks for unlock the moment a finger lands. The system's
        // fingerprint check then reads the finger still resting on the sensor. After
        // unlocking, the music player stays open (not the home screen).
        if (lockedPlayer) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(160.dp)
                    .height(150.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            activity?.let {
                                it.getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(it, null)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Faint hint of where to put the finger.
                Icon(ZsIcons.Unlock, contentDescription = "Unlock: place finger here", tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(22.dp))
            }
        }

        // Nav: swipe-dial strip. While swiping, the tab name shows big over the screen.
        navPreview?.let { DialPreview(it) }
        if (!fullScreen) {
            DialNavBar(
                selected = tab,
                onSelect = { tab = it },
                onPreview = { navPreview = it },
                preview = navPreview,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 40.dp, end = 40.dp, bottom = 16.dp)
                    .onSizeChanged { navWidthPx = it.width },
            )
        }
    }
}

/** True while the phone is locked (keyguard showing). Re-checked on start/resume and on unlock. */
@Composable
private fun rememberKeyguardLocked(): Boolean {
    val context = LocalContext.current
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(keyguard.isKeyguardLocked) }
    LifecycleStartEffect(Unit) {
        locked = keyguard.isKeyguardLocked
        onStopOrDispose { locked = keyguard.isKeyguardLocked }
    }
    LifecycleResumeEffect(Unit) {
        locked = keyguard.isKeyguardLocked
        onPauseOrDispose { }
    }
    // The user unlocking while ZeroSync is already on screen.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                locked = keyguard.isKeyguardLocked
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return locked
}

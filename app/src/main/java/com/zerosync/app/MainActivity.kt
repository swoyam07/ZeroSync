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
import com.zerosync.app.ui.PillNavBar
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
    var tab by rememberSaveable { mutableStateOf(AppTab.Gallery) }
    val gallery: GalleryViewModel = viewModel()
    val music: MusicViewModel = viewModel()

    // Lock-screen player: while music plays on the Music tab, ZeroSync may show over the
    // lock screen (power button) without unlocking, so the wheels stay usable.
    // While locked, ONLY the music player is shown: no nav bar, no other tabs (privacy).
    val activity = LocalActivity.current
    val locked = rememberKeyguardLocked()
    val allowOverLock = tab == AppTab.Music && music.isPlaying
    LaunchedEffect(allowOverLock) { activity?.setShowWhenLocked(allowOverLock) }
    val lockedPlayer = locked && allowOverLock

    // Music keeps playing when leaving the app (home / app switch / screen off).
    // It stops when the app is cleared from recents (the player is released with the app).

    val fullScreen = (tab == AppTab.Gallery && gallery.isFullScreen) || lockedPlayer

    // Measure the nav bar so floating controls can use the same side gap.
    // Kept after the bar hides (full-screen feed), so controls don't jump.
    val density = LocalDensity.current
    var screenWidthPx by remember { mutableIntStateOf(0) }
    var navWidthPx by remember { mutableIntStateOf(0) }
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
            else -> Text(
                "${tab.label}: coming soon",
                color = ZsColors.OnSurface.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!fullScreen) {
            PillNavBar(
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { navWidthPx = it.width }
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
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

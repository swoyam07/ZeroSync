package com.zerosync.app.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zerosync.app.ui.PillSwitch
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import kotlin.math.abs

// Leaves room so the last grid row isn't hidden behind the floating nav bar.
private val NavBarClearance = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)

@Composable
fun GalleryScreen(vm: GalleryViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasVideoAccess(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = hasVideoAccess(context)
        if (granted) vm.load()
    }

    // Re-check on every return to the app: new videos, or access changed in Settings.
    LifecycleResumeEffect(Unit) {
        granted = hasVideoAccess(context)
        if (granted) vm.load()
        onPauseOrDispose { }
    }

    if (!granted) {
        PermissionPrompt { permissionLauncher.launch(videoPermissions()) }
        return
    }

    // Swipe sideways anywhere on Gallery: left = Folders, right = Shuffle.
    // Vertical swipes still go to the video feed / lists.
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            var dragX = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragX = 0f },
                onDragEnd = { if (abs(dragX) > swipeThreshold) vm.swipeMode(if (dragX > 0) 1 else -1) },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragX += amount
                },
            )
        },
    ) {
        when (vm.mode) {
            GalleryMode.Shuffle -> ShuffleMode(vm)
            GalleryMode.Folders -> FoldersMode(vm)
        }
    }
}

/** Shuffle: opens straight into a random video; swipe for more (random order). */
@Composable
private fun ShuffleMode(vm: GalleryViewModel) {
    if (vm.shuffleFeed.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            ModeSwitch(vm, Modifier.align(Alignment.CenterHorizontally).padding(top = ModeSwitchTop))
            CenterMessage(if (vm.loading) "Loading…" else "No videos found")
        }
        return
    }
    // key: rebuild the feed (back to its first page) when the play order is toggled.
    key(vm.feedVersion) {
        VideoFeed(
            items = vm.shuffleFeed,
            startIndex = 0,
            autoScroll = vm.autoScroll,
            onToggleAutoScroll = { vm.autoScroll = !vm.autoScroll },
            inOrder = vm.inOrder,
            onToggleOrder = vm::toggleOrder,
            onPageSettled = vm::onShufflePage,
            onClose = null,
            onNearEnd = vm::extendShuffleFeed,
            topCenter = { ModeSwitch(vm) },
        )
    }
}

/** Folders: folders → videos in that folder → vertical feed. */
@Composable
private fun FoldersMode(vm: GalleryViewModel) {
    vm.feed?.let { feed ->
        BackHandler { vm.closeFeed() }
        key(vm.feedVersion) {
            VideoFeed(
                items = feed.items,
                startIndex = feed.startIndex,
                autoScroll = vm.autoScroll,
                onToggleAutoScroll = { vm.autoScroll = !vm.autoScroll },
                inOrder = vm.inOrder,
                onToggleOrder = vm::toggleOrder,
                onPageSettled = vm::onFeedPage,
                onClose = vm::closeFeed,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ModeSwitch(vm, Modifier.align(Alignment.CenterHorizontally).padding(top = ModeSwitchTop, bottom = 8.dp))

        val folder = vm.currentFolder
        when {
            folder != null -> {
                BackHandler { vm.closeFolder() }
                FolderHeader(folder.name, onBack = vm::closeFolder)
                VideoGrid(folder.videos) { vm.openVideo(it, folder.videos) }
            }
            vm.loading && vm.folders.isEmpty() -> CenterMessage("Loading…")
            vm.folders.isEmpty() -> CenterMessage("No folders with videos found")
            else -> FolderList(vm.folders, onOpen = vm::openFolder)
        }
    }
}

@Composable
private fun ModeSwitch(vm: GalleryViewModel, modifier: Modifier = Modifier) {
    PillSwitch(
        options = GalleryMode.entries,
        selected = vm.mode,
        label = { it.name },
        onSelect = vm::selectMode,
        modifier = modifier,
    )
}

@Composable
private fun VideoGrid(items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = NavBarClearance,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.uri.toString() }) { item ->
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).clickable { onClick(item) }) {
                Thumbnail(item.uri, Modifier.fillMaxSize())
                Icon(
                    ZsIcons.Play,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            }
        }
    }
}

/** Two folders per row: big square cover, name below. */
@Composable
private fun FolderList(folders: List<VideoFolder>, onOpen: (VideoFolder) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        // Wide side margins keep the covers away from the screen edges and a bit smaller.
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(folders, key = { it.bucketId }) { folder ->
            Column(Modifier.clickable { onOpen(folder) }) {
                Thumbnail(
                    folder.videos.first().uri,
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                    sizePx = 512,
                )
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = ZsColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderHeader(name: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(ZsIcons.Back, contentDescription = "Back to folders", tint = ZsColors.Primary)
        }
        Icon(ZsIcons.Folder, contentDescription = null, tint = ZsColors.Secondary)
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = ZsColors.OnSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "ZeroSync needs access to your videos to show them.\n" +
                "Nothing leaves your phone: this app has no internet access.",
            color = ZsColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = ZsColors.PrimaryContainer),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text("Allow access", color = ZsColors.Tertiary)
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = ZsColors.OnSurface.copy(alpha = 0.6f))
    }
}

private fun videoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Full or partial ("selected videos only") access both count. */
private fun hasVideoAccess(context: Context): Boolean =
    videoPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

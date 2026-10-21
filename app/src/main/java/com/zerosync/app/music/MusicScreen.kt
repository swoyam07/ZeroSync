package com.zerosync.app.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.cos
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.zerosync.app.gallery.Thumbnail
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors

// Room at the bottom so the wheels sit above the floating nav bar.
private val NavBarClearance = 104.dp

@Composable
fun MusicScreen(vm: MusicViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAudioAccess(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = hasAudioAccess(context)
        if (granted) vm.load()
    }

    // Re-check on every return: new songs, access changed, volume changed with side keys.
    LifecycleResumeEffect(Unit) {
        granted = hasAudioAccess(context)
        if (granted) vm.load()
        vm.refreshVolume()
        onPauseOrDispose { }
    }

    when {
        !granted -> PermissionPrompt { permissionLauncher.launch(musicPermissions()) }
        vm.folders.isEmpty() -> CenterMessage(if (vm.loading) "Loading…" else "No music found")
        else -> Player(vm)
    }
}

@Composable
private fun Player(vm: MusicViewModel) {
    val songs = vm.songs
    val shown = vm.shownIndex.coerceIn(0, songs.lastIndex)
    val song = songs[shown]

    // Folder picker (hold, drag down). The list is turned so the current folder sits in the
    // middle (same number of folders above and below). picked = highlighted row, null = closed.
    var order by remember { mutableStateOf(emptyList<MusicFolder>()) }
    var picked by remember { mutableStateOf<Int?>(null) }
    // Sleep timer picker (volume button: hold, drag left). Index into SleepChoices, null = closed.
    var timerPick by remember { mutableStateOf<Int?>(null) }
    var timerStart by remember { mutableStateOf(0) }
    val folders = vm.folders

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 12.dp, bottom = NavBarClearance),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        // Big round cover with the time bar wrapped around it as a ring of bars (sound wave).
        // Tapping the cover of a picked (not yet playing) song starts it.
        Box(Modifier.fillMaxWidth(0.86f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            // Ring first, cover on top, so taps on the cover reach the cover.
            WaveformRing(vm, seed = song.id, Modifier.fillMaxSize())
            Cover(song, Modifier.fillMaxSize(0.74f).clickable(enabled = vm.startPending, onClick = vm::playPause))
        }

        // Numbers under the cover: elapsed | total.
        Text(
            "${formatTime(vm.scrubMs ?: vm.positionMs)}  |  ${formatTime(vm.durationMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = ZsColors.OnSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 10.dp),
        )

        Text(
            song.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp),
        )
        Text(
            song.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = ZsColors.OnSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        // Shuffle · Previous · Play/Pause · Next · Loop
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = vm::toggleShuffle, modifier = Modifier.size(48.dp)) {
                Icon(
                    ZsIcons.Shuffle,
                    contentDescription = if (vm.shuffle) "Shuffle on" else "Shuffle off",
                    // Lilac = on, dim = off.
                    tint = if (vm.shuffle) ZsColors.Primary else Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) {
                Icon(ZsIcons.SkipPrevious, contentDescription = "Previous song", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Box(
                Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(ZsColors.Primary)
                    .clickable(onClick = vm::playPause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (vm.isPlaying && !vm.startPending) ZsIcons.Pause else ZsIcons.Play,
                    contentDescription = if (vm.isPlaying && !vm.startPending) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = vm::next, modifier = Modifier.size(56.dp)) {
                Icon(ZsIcons.SkipNext, contentDescription = "Next song", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = vm::toggleRepeatOne, modifier = Modifier.size(48.dp)) {
                Icon(
                    ZsIcons.RepeatOne,
                    contentDescription = if (vm.repeatOne) "Loop song on" else "Loop song off",
                    // Lilac = on, dim = off.
                    tint = if (vm.repeatOne) ZsColors.Primary else Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Two dials: volume (left, raised) and song (right, lower) for thumb reach.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            val wheel = min(maxWidth * 0.42f, 170.dp)
            Box(Modifier.fillMaxWidth().height(wheel * 1.35f)) {
                Column(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Hold, drag right, then move up/down = volume. Left does nothing yet.
                    VolumeButton(
                        description = "Volume button: hold, drag right, then move up for louder or down for quieter",
                        volume = vm.volume,
                        maxVolume = vm.maxVolume,
                        onVolumeStep = vm::turnVolumeWheel,
                        // Left + move up/down: pick a sleep timer; applied on lift.
                        onLeftStart = {
                            timerPick = SleepChoices.indexOf(vm.sleepMinutes).coerceAtLeast(0)
                            timerStart = timerPick!!
                        },
                        onLeftMove = { steps -> timerPick = (timerStart + steps).coerceIn(0, SleepChoices.lastIndex) },
                        onLeftRelease = {
                            timerPick?.let { if (SleepChoices[it] != vm.sleepMinutes) vm.setSleepTimer(SleepChoices[it]) }
                            timerPick = null
                        },
                        size = min(wheel * 0.45f, 72.dp),
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Hold, then: up + circle = pick a song; down + move = pick a folder.
                    // Left / right do nothing yet.
                    JoystickButton(
                        description = "Song button: hold, drag up and circle to pick a song, or drag down to pick a folder",
                        onUpTick = vm::turnSongWheel,
                        onUpRelease = vm::releaseSongWheel,
                        onDownStart = {
                            val current = folders.indexOfFirst { it.id == vm.selectedFolderId }.coerceAtLeast(0)
                            val middle = folders.size / 2
                            order = List(folders.size) { i -> folders[Math.floorMod(current - middle + i, folders.size)] }
                            picked = middle
                        },
                        onDownMove = { steps ->
                            // The folder changes right away while moving, like the song dial.
                            val i = (folders.size / 2 + steps).coerceIn(0, order.lastIndex)
                            picked = i
                            order.getOrNull(i)?.let { if (it.id != vm.selectedFolderId) vm.selectFolder(it.id, autoPlay = false) }
                        },
                        // Right + circle: pick a song without playing it; the play button starts it.
                        onRightTick = vm::turnSongWheel,
                        // No autoplay after a folder change; the user presses play.
                        onDownRelease = { picked = null },
                        // Left + move up/down: scrub the time; music keeps playing, jumps on lift.
                        onLeftStart = vm::startScrub,
                        onLeftMove = vm::moveScrub,
                        onLeftRelease = vm::endScrub,
                        size = min(wheel * 0.45f, 72.dp),
                    )
                }
            }
        }
    }
    picked?.let { FolderPicker(order.map { it.name }, it) }
    timerPick?.let { FolderPicker(SleepChoices.map(::sleepLabel), it) }
    }
}

/** Folder boxes shown while holding the button and dragging down. Highlighted = picked on lift. */
/** Sleep timer choices in minutes; null = off. */
private val SleepChoices = listOf(null, 30, 60, 120, 300)

private fun sleepLabel(minutes: Int?) = when {
    minutes == null -> "Timer off"
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    else -> "${minutes / 60} hours"
}

@Composable
private fun FolderPicker(names: List<String>, highlighted: Int) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        names.forEachIndexed { i, name ->
            val active = i == highlighted
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) ZsColors.PrimaryContainer else Color.Black)
                    .border(1.dp, if (active) ZsColors.Primary else ZsColors.Secondary, RoundedCornerShape(12.dp))
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Seek bar with current time and total time. Drag to jump within the song. */
/**
 * Time bar drawn as bars pointing outward all around the cover (like a sound wave).
 * Starts at the top, goes clockwise. Played part = lilac, rest = dim.
 * Bar heights are made up from the song id, so each song has its own fixed pattern.
 * Tap or drag along the ring to jump; the song jumps when the finger lifts.
 */
@Composable
private fun WaveformRing(vm: MusicViewModel, seed: Long, modifier: Modifier) {
    // Move the bar about twice a second, only while playing and the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm.isPlaying) {
        if (!vm.isPlaying) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refreshPosition()
                delay(500)
            }
        }
    }
    var dragMs by remember { mutableStateOf<Float?>(null) }
    val duration = vm.durationMs.coerceAtLeast(1L).toFloat()
    val shownMs = dragMs ?: (vm.scrubMs ?: vm.positionMs).toFloat().coerceAtMost(duration)
    val heights = remember(seed) { val rnd = java.util.Random(seed); List(90) { 0.25f + rnd.nextFloat() * 0.75f } }
    // Latest values for the touch handlers (they are set up once).
    val currentDuration by rememberUpdatedState(duration)
    val currentEnabled by rememberUpdatedState(vm.durationMs > 0)
    val currentSeek by rememberUpdatedState<(Long) -> Unit>(vm::seekTo)

    // Only touches on the ring count (not on the cover in the middle).
    fun PointerInputScope.onRing(p: Offset) = (p - Offset(size.width / 2f, size.height / 2f)).getDistance() > size.width * 0.37f
    // Angle from the top, clockwise, as a share of the song.
    fun PointerInputScope.msAt(p: Offset): Float {
        val deg = Math.toDegrees(atan2((p.x - size.width / 2f).toDouble(), -(p.y - size.height / 2f).toDouble()))
        return (((deg + 360) % 360) / 360f).toFloat() * currentDuration
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { if (currentEnabled && onRing(it)) currentSeek(msAt(it).toLong()) }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!currentEnabled || !onRing(down.position)) return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (!change.pressed) break
                        if (change.position != down.position) {
                            change.consume()
                            dragMs = msAt(change.position)
                        }
                    }
                    dragMs?.let { currentSeek(it.toLong()) }
                    dragMs = null
                }
            },
    ) {
        val n = heights.size
        val inner = size.minDimension * 0.39f // just outside the cover
        val maxLen = size.minDimension * 0.10f
        val w = 2.5.dp.toPx()
        val played = shownMs / duration
        heights.forEachIndexed { i, h ->
            val a = Math.toRadians(i * 360.0 / n - 90.0)
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            val color = if ((i + 0.5f) / n <= played) ZsColors.Primary else Color.White.copy(alpha = 0.2f)
            drawLine(color, center + dir * inner, center + dir * (inner + maxLen * h), w, StrokeCap.Round)
        }
    }
}

/** 83000 ms -> "1:23". */
private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** Album cover, or a music-note placeholder when the song has none. */
@Composable
private fun Cover(song: Song, modifier: Modifier, dim: Boolean = false) {
    val shape = CircleShape
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(ZsColors.PrimaryContainer, Color(0xFF1A1A1A))))
            .border(1.dp, ZsColors.Primary.copy(alpha = if (dim) 0.3f else 0.7f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ZsIcons.Music, contentDescription = null, tint = ZsColors.Tertiary.copy(alpha = 0.6f), modifier = Modifier.fillMaxSize(0.35f))
        Thumbnail(song.uri, Modifier.fillMaxSize(), sizePx = if (dim) 256 else 640, placeholder = Color.Transparent)
        if (dim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
    }
}

@Composable
private fun WheelLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = ZsColors.OnSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "ZeroSync needs access to your music to play it.\n" +
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

private fun musicPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasAudioAccess(context: Context): Boolean {
    val read = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, read) == PackageManager.PERMISSION_GRANTED
}

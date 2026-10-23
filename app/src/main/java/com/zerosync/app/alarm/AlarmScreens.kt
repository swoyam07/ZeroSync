package com.zerosync.app.alarm

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zerosync.app.ui.theme.ZeroSyncTheme
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun send(context: Context, action: String) {
    context.startService(Intent(context, AlarmService::class.java).setAction(action))
}

private fun timeText(a: Alarm) = a.time12

/** Full-screen alarm (locked / screen off). Shows over the lock screen and turns it on. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZeroSyncTheme {
                val alarm = AlarmService.ringing
                LaunchedEffect(alarm) { if (alarm == null) finish() }
                Column(
                    Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(if ((alarm?.id ?: 0) < 0) "Event" else "Alarm", color = ZsColors.Secondary, style = MaterialTheme.typography.titleMedium) // events use negative ids
                    Text(alarm?.let(::timeText) ?: "", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light, maxLines = 1)
                    // Event title stays hidden while the phone is locked (privacy, like reminders).
                    val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                    val sub = if ((alarm?.id ?: 0) < 0 && locked) "Unlock to see the event" else alarm?.soundName ?: ""
                    Text(sub, color = ZsColors.Secondary, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.weight(1f))
                    SwipeBar(
                        onSnooze = { send(this@AlarmActivity, AlarmService.ACTION_SNOOZE) },
                        onStop = { send(this@AlarmActivity, AlarmService.ACTION_STOP) },
                        modifier = Modifier.padding(bottom = 72.dp),
                        // Calendar events: "Snooze" / "Complete" (alarms keep "Snooze 5" / "Stop").
                        snoozeLabel = if ((alarm?.id ?: 0) < 0) "Snooze" else "Snooze $SNOOZE_MINUTES",
                        stopLabel = if ((alarm?.id ?: 0) < 0) "Complete" else "Stop",
                    )
                }
            }
        }
    }
}

/**
 * Swipe bar like the call screen: left = Snooze 5 min, right = Stop.
 * Past the edge it stays there and acts; let go early = glides back (approved ≤180 ms glide).
 */
@Composable
fun SwipeBar(
    onSnooze: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    snoozeLabel: String = "Snooze $SNOOZE_MINUTES",
    stopLabel: String = "Stop",
) {
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    val barH = if (compact) 60.dp else 84.dp
    val handleW = if (compact) 76.dp else 96.dp
    Box(
        modifier.fillMaxWidth().height(barH).clip(RoundedCornerShape(50)).background(ZsColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(snoozeLabel, color = Color.White, modifier = Modifier.align(Alignment.CenterStart).padding(start = 22.dp))
        Text(stopLabel, color = Color.White, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 26.dp))
        Box(
            Modifier
                .offset { IntOffset(drag.value.roundToInt(), 0) }
                .size(width = handleW, height = barH - 16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .pointerInput(Unit) {
                    val limit = (if (compact) 60 else 80).dp.toPx()
                    val edge = limit * 1.3f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (done) return@detectHorizontalDragGestures
                            scope.launch {
                                when {
                                    drag.value >= limit -> { done = true; drag.animateTo(edge, tween(90)); onStop() }
                                    drag.value <= -limit -> { done = true; drag.animateTo(-edge, tween(90)); onSnooze() }
                                    else -> drag.animateTo(0f, tween(180))
                                }
                            }
                        },
                        onDragCancel = { if (!done) scope.launch { drag.animateTo(0f, tween(180)) } },
                    ) { change, dx ->
                        if (done) return@detectHorizontalDragGestures
                        change.consume()
                        scope.launch { drag.snapTo((drag.value + dx).coerceIn(-edge, edge)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("⇆", color = Color.Black, fontSize = 22.sp) }
    }
}

/** Small alarm card over other apps while the phone is unlocked ("Display over other apps"). */
object AlarmOverlay {
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context, alarm: Alarm) {
        if (view != null) return
        val wm = context.getSystemService(WindowManager::class.java)
        val o = OverlayOwner().also { owner = it }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                ZeroSyncTheme {
                    Column(
                        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF141414)).border(1.dp, ZsColors.Secondary.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                            .clickable { context.startActivity(Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (alarm.id < 0) alarm.soundName else "Alarm", color = ZsColors.Secondary, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(timeText(alarm), color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        }
                        SwipeBar(
                            onSnooze = { send(context, AlarmService.ACTION_SNOOZE) },
                            onStop = { send(context, AlarmService.ACTION_STOP) },
                            compact = true,
                            snoozeLabel = if (alarm.id < 0) "Snooze" else "Snooze $SNOOZE_MINUTES",
                            stopLabel = if (alarm.id < 0) "Complete" else "Stop",
                        )
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = (36 * context.resources.displayMetrics.density).toInt()
        }
        runCatching { wm.addView(v, params) }.onSuccess { view = v; o.start() }.onFailure { owner = null }
    }

    fun hide(context: Context) {
        val v = view ?: return
        owner?.stop()
        runCatching { context.getSystemService(WindowManager::class.java).removeView(v) }
        view = null
        owner = null
    }
}

/** Minimal lifecycle so Compose can run in a window that isn't an Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this).apply { performRestore(null as Bundle?) }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() { registry.currentState = Lifecycle.State.RESUMED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}

package com.zerosync.app.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Finger travel (up/down) from silent to max volume. One step = this / maxVolume. */
private val VOLUME_TRAVEL = 120.dp

/** Half of the arc's sweep, in degrees (the arc runs from -ARC_HALF to +ARC_HALF). */
private const val ARC_HALF = 60f

private enum class VolMode { Idle, Menu, Volume }

/**
 * Small round button with no icon (left side). A tap does nothing.
 * 1. Hold it: a menu opens with 2 halves, left and right.
 * 2. Still holding, drag into a half:
 *    - Right: a curved bar appears on the right (shows the volume). Move the finger up for
 *      louder, down for quieter: about [VOLUME_TRAVEL] covers the full range; each step = [onVolumeStep] (+1 / -1) + vibration.
 *    - Left: [onLeft] fires once (does nothing yet; for future features).
 * 3. Lift your finger: everything closes.
 */
@Composable
fun VolumeButton(
    description: String,
    volume: Int,
    maxVolume: Int,
    onVolumeStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    onLeft: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val currentOnVolumeStep by rememberUpdatedState(onVolumeStep)
    val currentOnLeft by rememberUpdatedState(onLeft)
    val currentMax by rememberUpdatedState(maxVolume)
    var mode by remember { mutableStateOf(VolMode.Idle) }

    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val innerRadius = this.size.width / 2f // finger must leave the button to pick a half

                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Returns null when the time runs out with the finger still down = a hold.
                    val notHeld = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.first()
                            if (!change.pressed) break
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        }
                        true
                    }
                    if (notHeld != null) return@awaitEachGesture

                    mode = VolMode.Menu
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var startY = 0f
                    var steps = 0

                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (!change.pressed) break
                        change.consume()
                        val p = change.position
                        val d = p - center
                        when (mode) {
                            VolMode.Menu -> {
                                if (abs(d.x) < innerRadius) continue
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (d.x > 0) {
                                    mode = VolMode.Volume
                                    startY = p.y
                                    steps = 0
                                } else {
                                    currentOnLeft()
                                    mode = VolMode.Idle // one action, then done until next hold
                                }
                            }
                            VolMode.Volume -> {
                                // Up = louder, so flip the sign of the downward travel.
                                val now = ((startY - p.y) / (VOLUME_TRAVEL.toPx() / currentMax.coerceAtLeast(1))).toInt()
                                while (steps < now) { steps++; currentOnVolumeStep(1); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                while (steps > now) { steps--; currentOnVolumeStep(-1); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                            VolMode.Idle -> Unit
                        }
                    }
                    mode = VolMode.Idle
                }
            },
    ) {
        val radius = this.size.minDimension / 2f
        val outline = Brush.verticalGradient(listOf(ZsColors.Primary, ZsColors.Secondary))
        val line = Color.White.copy(alpha = 0.35f)
        // Menu and arc are drawn bigger than the button (no clip, so they spill outside).
        val bigRadius = radius * 2.6f

        when (mode) {
            VolMode.Menu -> {
                // Outer circle split into left and right halves by a vertical line.
                drawCircle(Color.Black, bigRadius)
                drawCircle(outline, bigRadius, style = Stroke(1.5.dp.toPx()))
                drawLine(line, center + Offset(0f, -bigRadius), center + Offset(0f, -radius), strokeWidth = 1.5.dp.toPx())
                drawLine(line, center + Offset(0f, radius), center + Offset(0f, bigRadius), strokeWidth = 1.5.dp.toPx())
                drawCircle(outline, radius, style = Stroke(1.5.dp.toPx()))
            }
            VolMode.Volume -> {
                // Like the sketch: the button, plus a curved bar on its right.
                drawCircle(Color.Black, radius)
                drawCircle(outline, radius - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                val arcRadius = radius * 2.2f
                val box = Size(arcRadius * 2, arcRadius * 2)
                val topLeft = center - Offset(arcRadius, arcRadius)
                val band = 14.dp.toPx()
                // Empty track, bottom (+60°) up to top (-60°).
                drawArc(Color.White.copy(alpha = 0.15f), -ARC_HALF, ARC_HALF * 2, false, topLeft, box, style = Stroke(band, cap = StrokeCap.Round))
                // Filled part grows from the bottom with the volume.
                val level = if (maxVolume > 0) volume.toFloat() / maxVolume else 0f
                if (level > 0f) {
                    drawArc(ZsColors.Primary, ARC_HALF, -ARC_HALF * 2 * level, false, topLeft, box, style = Stroke(band, cap = StrokeCap.Round))
                }
            }
            VolMode.Idle -> {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF1A1420), Color.Black)), radius)
                drawCircle(outline, radius - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

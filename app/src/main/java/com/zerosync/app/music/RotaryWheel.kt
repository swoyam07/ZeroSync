package com.zerosync.app.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.theme.ZsColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Degrees of rotation per tick (one song / one volume step). */
private const val STEP_DEGREES = 30f

/**
 * A round dial you turn with one finger, like an iPod click wheel.
 * Every [STEP_DEGREES] of turning = one tick: [onTick] (+1 clockwise, -1 counter-clockwise)
 * plus a short vibration. [onRelease] fires when the finger lifts.
 * The marker dot follows the finger directly (no animation).
 */
@Composable
fun RotaryWheel(
    icon: ImageVector,
    description: String,
    onTick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    onRelease: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val currentOnTick by rememberUpdatedState(onTick)
    val currentOnRelease by rememberUpdatedState(onRelease)
    var markerDegrees by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF1A1420), Color.Black)))
            .border(1.5.dp, Brush.verticalGradient(listOf(ZsColors.Primary, ZsColors.Secondary)), CircleShape)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                fun angleOf(p: Offset) = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat()
                var lastAngle = 0f
                var accumulated = 0f
                detectDragGestures(
                    onDragStart = { start ->
                        lastAngle = angleOf(start)
                        accumulated = 0f
                    },
                    onDragEnd = { currentOnRelease() },
                    onDragCancel = { currentOnRelease() },
                    onDrag = { change, _ ->
                        change.consume()
                        val angle = angleOf(change.position)
                        // Shortest turn between last and current angle (handles the ±180° jump).
                        var delta = angle - lastAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        lastAngle = angle
                        markerDegrees += delta
                        accumulated += delta
                        while (accumulated >= STEP_DEGREES) {
                            accumulated -= STEP_DEGREES
                            currentOnTick(1)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        while (accumulated <= -STEP_DEGREES) {
                            accumulated += STEP_DEGREES
                            currentOnTick(-1)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            // 12 tick marks around the rim = one per step.
            for (i in 0 until 12) {
                val a = (i * STEP_DEGREES) * PI.toFloat() / 180f
                val outer = center + Offset(cos(a), sin(a)) * (radius - 8.dp.toPx())
                val inner = center + Offset(cos(a), sin(a)) * (radius - 16.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.25f), inner, outer, strokeWidth = 2.dp.toPx())
            }
            // Marker dot that turns with the finger.
            val m = (markerDegrees - 90f) * PI.toFloat() / 180f
            drawCircle(
                ZsColors.Primary,
                radius = 6.dp.toPx(),
                center = center + Offset(cos(m), sin(m)) * (radius - 24.dp.toPx()),
            )
        }
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

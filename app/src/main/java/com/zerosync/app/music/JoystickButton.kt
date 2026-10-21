package com.zerosync.app.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The four parts of the hold menu. */
enum class SwipeDirection { Up, Down, Left, Right }

/** Degrees of circling per tick (one song). */
private const val STEP_DEGREES = 30f

/** What the button is showing right now. */
private enum class Mode { Idle, Menu, Dial, Picker, Seek }

/** Finger travel (up/down) that moves the time through the whole song. */
private val SEEK_TRAVEL = 200.dp

/** Finger travel (up/down) that moves the folder cursor by one. */
private val PICKER_STEP = 48.dp

/**
 * One small round button with no icon. A tap does nothing.
 * 1. Hold it: a round menu opens (centre circle + 4 parts: up, down, left, right).
 * 2. Still holding, drag into a part:
 *    - Up: song mode turns on and a dial appears. Circle your finger: every [STEP_DEGREES]
 *      = [onUpTick] (+1 clockwise, -1 counter-clockwise) with a vibration. Lift = [onUpRelease].
 *    - Down: folder picker. [onDownStart] once, then [onDownMove] with how many steps the finger
 *      has moved since (each [PICKER_STEP]; up = negative). Lift = [onDownRelease].
 *    - Right: the same dial, but it only picks: [onRightTick] per step, [onRightRelease] on lift.
 *    - Left: time scrub. [onLeftStart] once, then [onLeftMove] with how far the finger moved
 *      (share of the whole song, up = forward, down = back). Lift = [onLeftRelease].
 * 3. Lift your finger: everything closes and the plain button is back.
 */
@Composable
fun JoystickButton(
    description: String,
    onUpTick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    onUpRelease: () -> Unit = {},
    onSwipe: (SwipeDirection) -> Unit = {},
    onDownStart: () -> Unit = {},
    onDownMove: (Int) -> Unit = {},
    onDownRelease: () -> Unit = {},
    onRightTick: (Int) -> Unit = {},
    onRightRelease: () -> Unit = {},
    onLeftStart: () -> Unit = {},
    onLeftMove: (Float) -> Unit = {},
    onLeftRelease: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val currentOnUpTick by rememberUpdatedState(onUpTick)
    val currentOnUpRelease by rememberUpdatedState(onUpRelease)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnDownStart by rememberUpdatedState(onDownStart)
    val currentOnDownMove by rememberUpdatedState(onDownMove)
    val currentOnDownRelease by rememberUpdatedState(onDownRelease)
    val currentOnRightTick by rememberUpdatedState(onRightTick)
    val currentOnRightRelease by rememberUpdatedState(onRightRelease)
    val currentOnLeftStart by rememberUpdatedState(onLeftStart)
    val currentOnLeftMove by rememberUpdatedState(onLeftMove)
    val currentOnLeftRelease by rememberUpdatedState(onLeftRelease)
    var mode by remember { mutableStateOf(Mode.Idle) }
    val pop = rememberMenuPop(mode == Mode.Menu)
    // Same order as FourParts: up = play a song, right = pick a song, down = folder, left = time.
    val menuIcons = listOf(
        rememberVectorPainter(ZsIcons.Play),
        rememberVectorPainter(ZsIcons.Music),
        rememberVectorPainter(ZsIcons.Folder),
        rememberVectorPainter(ZsIcons.Clock),
    )
    var markerDegrees by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val innerRadius = this.size.width / 2f // finger must leave the centre circle to pick a part
                fun angleOf(p: Offset) = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat()

                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Wait for a hold. Lifting or moving away first = just a tap/drag, ignore it.
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

                    mode = Mode.Menu
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var lastAngle = 0f
                    var accumulated = 0f
                    var pickerStartY = 0f
                    var pickerSteps = 0
                    var dialPart = SwipeDirection.Up // which part opened the dial: Up or Right

                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Main).changes.first()
                        if (!change.pressed) break
                        change.consume()
                        val p = change.position
                        val d = p - center
                        when (mode) {
                            Mode.Menu -> {
                                if (d.getDistance() < innerRadius) continue
                                val part = if (abs(d.x) > abs(d.y)) {
                                    if (d.x > 0) SwipeDirection.Right else SwipeDirection.Left
                                } else {
                                    if (d.y > 0) SwipeDirection.Down else SwipeDirection.Up
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (part == SwipeDirection.Up || part == SwipeDirection.Right) {
                                    dialPart = part
                                    mode = Mode.Dial
                                    lastAngle = angleOf(p)
                                    markerDegrees = lastAngle + 90f
                                } else if (part == SwipeDirection.Down) {
                                    mode = Mode.Picker
                                    pickerStartY = p.y
                                    pickerSteps = 0
                                    currentOnDownStart()
                                } else if (part == SwipeDirection.Left) {
                                    mode = Mode.Seek
                                    pickerStartY = p.y
                                    currentOnLeftStart()
                                } else {
                                    currentOnSwipe(part)
                                    mode = Mode.Idle // other parts: one action, then done until next hold
                                }
                            }
                            Mode.Dial -> {
                                // Too close to the centre the angle jumps around, so ignore it.
                                if (d.getDistance() < innerRadius) continue
                                val angle = angleOf(p)
                                // Shortest turn between last and current angle (handles the ±180° jump).
                                var delta = angle - lastAngle
                                if (delta > 180f) delta -= 360f
                                if (delta < -180f) delta += 360f
                                lastAngle = angle
                                markerDegrees += delta
                                accumulated += delta
                                while (accumulated >= STEP_DEGREES) {
                                    accumulated -= STEP_DEGREES
                                    if (dialPart == SwipeDirection.Up) currentOnUpTick(1) else currentOnRightTick(1)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                while (accumulated <= -STEP_DEGREES) {
                                    accumulated += STEP_DEGREES
                                    if (dialPart == SwipeDirection.Up) currentOnUpTick(-1) else currentOnRightTick(-1)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            Mode.Picker -> {
                                val steps = ((p.y - pickerStartY) / PICKER_STEP.toPx()).toInt()
                                if (steps != pickerSteps) {
                                    pickerSteps = steps
                                    currentOnDownMove(steps)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            Mode.Seek -> currentOnLeftMove((pickerStartY - p.y) / SEEK_TRAVEL.toPx())
                            Mode.Idle -> Unit
                        }
                    }
                    if (mode == Mode.Seek) currentOnLeftRelease()
                    if (mode == Mode.Dial) {
                        if (dialPart == SwipeDirection.Up) currentOnUpRelease() else currentOnRightRelease()
                    }
                    if (mode == Mode.Picker) currentOnDownRelease()
                    mode = Mode.Idle
                }
            },
    ) {
        val radius = this.size.minDimension / 2f
        val outline = Brush.verticalGradient(listOf(ZsColors.Primary, ZsColors.Secondary))
        val line = Color.White.copy(alpha = 0.35f)
        // Menu and dial are drawn bigger than the button (no clip, so they spill outside).
        val bigRadius = radius * 2.6f

        when (mode) {
            Mode.Menu -> {
                // Like the sketch: centre circle + 4 separate curved pads (up, right, down, left).
                drawHoldMenu(radius, FourParts, menuIcons, pop)
            }
            Mode.Dial -> {
                drawCircle(Color.Black, bigRadius)
                drawCircle(outline, bigRadius, style = Stroke(1.5.dp.toPx()))
                for (i in 0 until 12) {
                    val a = (i * STEP_DEGREES) * PI.toFloat() / 180f
                    val dir = Offset(cos(a), sin(a))
                    drawLine(line, center + dir * (bigRadius - 12.dp.toPx()), center + dir * (bigRadius - 4.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
                val m = (markerDegrees - 90f) * PI.toFloat() / 180f
                drawCircle(ZsColors.Primary, radius = 8.dp.toPx(), center = center + Offset(cos(m), sin(m)) * (bigRadius - 24.dp.toPx()))
            }
            // Picker: the folder boxes are drawn by the screen; the button stays plain.
            Mode.Idle, Mode.Picker, Mode.Seek -> {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF1A1A1A), Color.Black)), radius)
                drawCircle(outline, radius - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

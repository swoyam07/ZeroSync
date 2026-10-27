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
import androidx.compose.ui.graphics.drawscope.translate
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
private val PICKER_STEP = 22.dp // small finger moves change the folder (was 48)

/**
 * One small round button with no icon. A tap does nothing.
 * 1. Hold still (long-press): a round menu opens (centre circle + 4 parts: up, down, left, right).
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
    // Same order as FourParts (screen positions): up = time, right = pick a song,
    // down = folder, left = change song + play.
    val menuIcons = listOf(
        rememberVectorPainter(ZsIcons.Clock),
        rememberVectorPainter(ZsIcons.Music),
        rememberVectorPainter(ZsIcons.Folder),
        rememberVectorPainter(ZsIcons.Play),
    )
    var markerDegrees by remember { mutableFloatStateOf(0f) }
    var seekShift by remember { mutableFloatStateOf(0f) } // time scrub: finger travel in px (down = +)

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
                    // Hold: the menu opens only after the finger stays still for a long-press.
                    // A tap, or moving before that, does nothing.
                    val held = withTimeoutOrNull(HOLD_MILLIS) {
                        while (true) {
                            val change = awaitPointerEvent().changes.first()
                            if (!change.pressed) return@withTimeoutOrNull false
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } == null // timed out = finger held still long enough
                    if (!held) return@awaitEachGesture

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
                                val swiped = if (abs(d.x) > abs(d.y)) {
                                    if (d.x > 0) SwipeDirection.Right else SwipeDirection.Left
                                } else {
                                    if (d.y > 0) SwipeDirection.Down else SwipeDirection.Up
                                }
                                // User's layout: swipe UP = move the time, swipe LEFT = change song + play.
                                // (Inside, "Up" still means the play dial and "Left" the time scrub.)
                                val part = when (swiped) {
                                    SwipeDirection.Up -> SwipeDirection.Left
                                    SwipeDirection.Left -> SwipeDirection.Up
                                    else -> swiped
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
                                    seekShift = 0f
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
                            Mode.Seek -> {
                                seekShift = p.y - pickerStartY // ruler slides with the finger
                                currentOnLeftMove((pickerStartY - p.y) / SEEK_TRAVEL.toPx())
                            }
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
                // Knob look (like the reference): dark track ring cut into 4, a glowing arc that
                // follows the finger, and a raised knob in the middle.
                val trackR = bigRadius * 0.82f
                val trackW = bigRadius * 0.22f
                val ringBox = androidx.compose.ui.geometry.Size(trackR * 2, trackR * 2)
                val ringTopLeft = center - Offset(trackR, trackR)
                drawCircle(Color.Black, bigRadius)
                drawCircle(Color(0xFF1C1C1C), trackR, style = Stroke(trackW))
                for (k in 0 until 4) { // thin black cuts between the 4 track parts
                    val a = (45f + k * 90f) * PI.toFloat() / 180f
                    val dir = Offset(cos(a), sin(a))
                    drawLine(Color.Black, center + dir * (trackR - trackW / 2), center + dir * (trackR + trackW / 2), strokeWidth = 2.dp.toPx())
                }
                // Glow arc centred on the finger: soft wide arc under a bright thin one.
                val at = markerDegrees - 90f
                drawArc(ZsColors.Primary.copy(alpha = 0.25f), at - 35f, 70f, false, ringTopLeft, ringBox, style = Stroke(trackW))
                drawArc(ZsColors.Primary, at - 30f, 60f, false, ringTopLeft, ringBox, style = Stroke(trackW * 0.45f))
                // Raised knob: lighter top edge, dark body, small dark centre.
                val knobR = bigRadius * 0.55f
                drawCircle(Brush.verticalGradient(listOf(Color(0xFF4A4A4A), Color(0xFF111111)), center.y - knobR, center.y + knobR), knobR)
                drawCircle(Brush.verticalGradient(listOf(Color(0xFF262626), Color(0xFF0A0A0A)), center.y - knobR, center.y + knobR), knobR - 2.dp.toPx())
                drawCircle(Color(0xFF050505), knobR * 0.45f)
                drawCircle(line, knobR * 0.45f, style = Stroke(1.dp.toPx()))
            }
            // Time scrub (after swiping up): a tick ruler in place of the button that slides with the
            // finger (like a tape measure). The red line at the middle = "you are here".
            Mode.Seek -> {
                drawTickRuler(radius, seekShift)
            }
            // Picker: the folder boxes are drawn by the screen; the button stays plain.
            Mode.Idle, Mode.Picker -> {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF1A1A1A), Color.Black)), radius)
                drawCircle(outline, radius - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                // Hint: up = time, right = song, down = folder, left = play.
                if (mode == Mode.Idle) drawMenuHints(radius, FourParts, menuIcons)
            }
        }
    }
}

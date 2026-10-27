package com.zerosync.app.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.theme.ZsColors

/** Hold menu part centres, in degrees (0 = right, 90 = down). */
val FourParts = listOf(270f, 0f, 90f, 180f) // up, right, down, left
val TwoParts = listOf(270f, 90f)             // up, down

/**
 * Short pop-in (0.6 → 1 in 120 ms) each time the hold menu opens. Approved by the user.
 * Returns the current scale; 1 when the menu is closed.
 */
@Composable
fun rememberMenuPop(open: Boolean): Float {
    // Rests at the small size, so the very first frame of the menu is already small
    // (starting at full size and snapping down caused a flicker).
    val anim = remember { Animatable(0.6f) }
    LaunchedEffect(open) {
        if (open) {
            anim.animateTo(1f, tween(120, easing = FastOutSlowInEasing))
        } else {
            anim.snapTo(0.6f) // ready for next time (menu isn't drawn while closed)
        }
    }
    return anim.value
}

/**
 * The hold menu: a centre circle and, around it, a solid ring split into one slice per part.
 * [radius] = button radius. [pop] = pop-in scale.
 * [icons] = one per part (same order), drawn in the middle of its slice; null = empty slice.
 */
fun DrawScope.drawHoldMenu(radius: Float, parts: List<Float>, icons: List<Painter?>, pop: Float) = scale(pop, center) {
    val line = 1.5.dp.toPx()
    val inner = radius * 1.35f
    val outer = radius * 2.5f
    val band = outer - inner
    val mid = (inner + outer) / 2f
    // One solid ring split into equal slices (like a D-pad): hollow ring + a divider line
    // half-way between parts, so each slice is centred on its part.
    drawCircle(ZsColors.Secondary, mid, style = Stroke(band))
    drawCircle(Color.Black, mid, style = Stroke(band - line * 2))
    for (deg in parts) {
        val a = Math.toRadians((deg + 180f / parts.size).toDouble())
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
        drawLine(ZsColors.Secondary, center + dir * inner, center + dir * outer, strokeWidth = line)
    }
    // Icon in each pad = what dragging there will do.
    val iconSize = (band * 0.55f).coerceAtMost(28.dp.toPx())
    parts.forEachIndexed { i, deg ->
        val painter = icons.getOrNull(i) ?: return@forEachIndexed
        val a = Math.toRadians(deg.toDouble())
        val at = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * mid - Offset(iconSize / 2, iconSize / 2)
        translate(at.x, at.y) {
            with(painter) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(Color.White)) }
        }
    }
    drawCircle(Color.Black, radius)
    drawCircle(ZsColors.Primary, radius - line / 2, style = Stroke(line))
}

/** How long the finger must stay still before a hold menu pops up (Android default is ~400). */
const val HOLD_MILLIS = 50L

/**
 * Hint only (no function): the menu icons, dimmed, on the side you drag towards.
 * Drawn around the plain button, so it says "drag me" and what each direction does.
 */
fun DrawScope.drawMenuHints(radius: Float, parts: List<Float>, icons: List<Painter?>) {
    val iconSize = 20.dp.toPx()
    val gap = radius + 6.dp.toPx() + iconSize / 2f // icon centre, just outside the ring
    val hint = ColorFilter.tint(Color(0xFF595959))
    parts.forEachIndexed { i, deg ->
        val painter = icons.getOrNull(i) ?: return@forEachIndexed
        val a = Math.toRadians(deg.toDouble())
        val at = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * gap - Offset(iconSize / 2f, iconSize / 2f)
        translate(at.x, at.y) {
            with(painter) { draw(Size(iconSize, iconSize), colorFilter = hint) }
        }
    }
}

/**
 * Up/down drag look (time scrub, volume): a column of tick marks in place of the button that
 * slides with the finger (like a tape measure), and a red "you are here" line at the centre.
 * [shift] = finger travel in px since the drag started (down = +).
 */
fun DrawScope.drawTickRuler(radius: Float, shift: Float) {
    val half = radius * 3.2f // ruler height above and below the centre
    val spacing = 8.dp.toPx()
    val left = center.x - radius * 0.35f
    val short = radius * 0.35f
    val long = radius * 0.7f // every 5th tick
    val first = floor((-half - shift) / spacing).toInt()
    val last = ceil((half - shift) / spacing).toInt()
    for (k in first..last) {
        val y = k * spacing + shift
        if (abs(y) > half) continue
        val fade = 1f - abs(y) / half // ticks fade out towards the ends
        val len = if (k % 5 == 0) long else short
        drawLine(Color.White.copy(alpha = 0.6f * fade), Offset(left, center.y + y), Offset(left + len, center.y + y), strokeWidth = 1.5.dp.toPx())
    }
    drawLine(Color(0xFFFF3B30), Offset(left - 4.dp.toPx(), center.y), Offset(left + long + 4.dp.toPx(), center.y), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
}

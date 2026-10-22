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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.theme.ZsColors

/** Hold menu part centres, in degrees (0 = right, 90 = down). */
val FourParts = listOf(270f, 0f, 90f, 180f) // up, right, down, left
val TwoParts = listOf(0f, 180f)              // right, left

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
 * The hold menu, like the sketch: a centre circle and separate curved pads around it
 * (one per part, with gaps between). [radius] = button radius. [pop] = pop-in scale.
 * [icons] = one per part (same order), drawn in the middle of its pad; null = empty pad.
 */
fun DrawScope.drawHoldMenu(radius: Float, parts: List<Float>, icons: List<Painter?>, pop: Float) = scale(pop, center) {
    val line = 1.5.dp.toPx()
    val inner = radius * 1.35f
    val outer = radius * 2.5f
    val band = outer - inner
    val mid = (inner + outer) / 2f
    val sweep = if (parts.size == 4) 50f else 110f // round ends add a bit on each side
    val topLeft = center - Offset(mid, mid)
    val box = Size(mid * 2, mid * 2)
    for (deg in parts) {
        // Outline: a slightly thicker gray band, then black on top = hollow pad with round ends.
        drawArc(ZsColors.Secondary, deg - sweep / 2, sweep, false, topLeft, box, style = Stroke(band, cap = StrokeCap.Round))
        drawArc(Color.Black, deg - sweep / 2, sweep, false, topLeft, box, style = Stroke(band - line * 2, cap = StrokeCap.Round))
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

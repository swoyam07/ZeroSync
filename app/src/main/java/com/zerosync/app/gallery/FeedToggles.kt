package com.zerosync.app.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.theme.ZsColors

/**
 * Feed controls: two "beads" on a thin rail, tap to toggle (no drag).
 * Glyphs are drawn by hand, not stock icons:
 * - Auto scroll: a falling stream (two chevrons) = flows on; a single bar = stays (loops).
 * - Order: three dots in a straight line = in order; three scattered dots = random.
 * On = solid white bead with black glyph, off = black bead with gray ring and white glyph.
 */
@Composable
fun FeedToggles(
    autoScroll: Boolean,
    inOrder: Boolean,
    onToggleAutoScroll: () -> Unit,
    onToggleOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        // The rail behind the beads.
        Canvas(Modifier.width(2.dp).height(120.dp)) {
            drawLine(ZsColors.Secondary.copy(alpha = 0.5f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), size.width, StrokeCap.Round)
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Bead(autoScroll, onToggleAutoScroll, if (autoScroll) "Auto scroll on" else "Auto scroll off") { ink ->
                if (autoScroll) drawStream(ink) else drawStay(ink)
            }
            Bead(inOrder, onToggleOrder, if (inOrder) "Playing in order" else "Playing in random order") { ink ->
                drawDots(ink, aligned = inOrder)
            }
        }
    }
}

@Composable
private fun Bead(on: Boolean, onToggle: () -> Unit, description: String, glyph: DrawScope.(Color) -> Unit) {
    Canvas(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
    ) {
        val r = size.minDimension / 2f
        if (on) {
            drawCircle(Color.White, r)
        } else {
            drawCircle(Color.Black.copy(alpha = 0.85f), r)
            drawCircle(ZsColors.Secondary, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
        glyph(if (on) Color.Black else Color.White)
    }
}

/** Two chevrons pointing down, like a stream: moves on to the next video. */
private fun DrawScope.drawStream(ink: Color) {
    val w = 7.dp.toPx()
    val h = 4.dp.toPx()
    val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (dy in listOf(-4.dp.toPx(), 4.dp.toPx())) {
        val y = center.y + dy - h / 2
        drawPath(
            Path().apply {
                moveTo(center.x - w, y)
                lineTo(center.x, y + h)
                lineTo(center.x + w, y)
            },
            ink,
            style = stroke,
        )
    }
}

/** One short bar: stays on this video (it loops). */
private fun DrawScope.drawStay(ink: Color) {
    val w = 7.dp.toPx()
    drawLine(ink, Offset(center.x - w, center.y), Offset(center.x + w, center.y), 2.dp.toPx(), StrokeCap.Round)
}

/** Three dots: in a straight line (in order) or scattered (random). */
private fun DrawScope.drawDots(ink: Color, aligned: Boolean) {
    val s = 6.dp.toPx()
    val dot = 2.2.dp.toPx()
    val points = if (aligned) {
        listOf(Offset(-s, 0f), Offset(0f, 0f), Offset(s, 0f))
    } else {
        listOf(Offset(-s, -s * 0.7f), Offset(s * 0.8f, -s * 0.2f), Offset(-s * 0.2f, s * 0.9f))
    }
    points.forEach { drawCircle(ink, dot, center + it) }
}

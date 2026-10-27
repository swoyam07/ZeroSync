package com.zerosync.app.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.ZsIcons
import com.zerosync.app.ui.theme.ZsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keypad call button.
 * - Tap: [onTap] (call the typed number).
 * - Drag (no hold needed): a 4-way menu opens around it (↑ → ↓ ←), each pad showing that favorite's first
 *   letter (or number end). Drag onto a pad and let go = [onFavorite]. Let go in the middle = cancel.
 * - Hold still (long-press): [onHold] (open favorites setup).
 * At rest, the 4 labels sit dimmed around the button as a hint that the drag exists.
 */
@Composable
fun FavCallButton(color: Color, size: Dp, onTap: () -> Unit, onFavorite: (FavSlot) -> Unit, onHold: () -> Unit = {}) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val currentTap by rememberUpdatedState(onTap)
    val currentFav by rememberUpdatedState(onFavorite)
    val currentHold by rememberUpdatedState(onHold)
    var open by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<FavSlot?>(null) }
    // Pad labels: first letter of the saved name, else last 2 digits; "+" when empty.
    val labels by produceState(emptyMap<FavSlot, String>(), Favorites.version, ContactsStore.version) {
        value = withContext(Dispatchers.IO) {
            FavSlot.entries.associateWith { s ->
                Favorites.get(context, s)?.let { n -> ContactsStore.find(context, n)?.name?.take(1)?.uppercase() ?: n.takeLast(2) } ?: "+"
            }
        }
    }
    val menu = pop(open)

    Box(contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(size)
                .pointerInput(Unit) {
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val inner = this.size.width / 2f
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Lift without moving = tap (call typed number); start dragging = the
                        // favorites menu opens right away; hold still = favorites setup.
                        val dragged = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val c = awaitPointerEvent().changes.first()
                                if (!c.pressed) return@withTimeoutOrNull false
                                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                        when (dragged) {
                            false -> { currentTap(); return@awaitEachGesture }
                            null -> { // held still
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentHold()
                                while (awaitPointerEvent().changes.first().pressed) Unit // wait for lift
                                return@awaitEachGesture
                            }
                            true -> Unit
                        }
                        open = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        var part: FavSlot? = null
                        while (true) {
                            val c = awaitPointerEvent().changes.first()
                            if (!c.pressed) break
                            c.consume()
                            val d = c.position - center
                            val now = if (d.getDistance() < inner) null
                            else if (abs(d.x) > abs(d.y)) (if (d.x > 0) FavSlot.Right else FavSlot.Left)
                            else (if (d.y > 0) FavSlot.Down else FavSlot.Up)
                            if (now != part && now != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            part = now
                            hovered = now
                        }
                        open = false
                        hovered = null
                        part?.let { currentFav(it) }
                    }
                },
        ) {
            val r = this.size.minDimension / 2f
            if (open) {
                // Menu drawn around the button (no clip): 4 pads, hovered one filled white.
                scale(menu, center) {
                    val inner = r * 1.25f
                    val outer = r * 2.35f
                    val band = outer - inner
                    val mid = (inner + outer) / 2f
                    val box = Size(mid * 2, mid * 2)
                    val topLeft = center - Offset(mid, mid)
                    FavSlot.entries.forEach { s ->
                        val on = s == hovered
                        drawArc(ZsColors.Secondary, s.angle - 25f, 50f, false, topLeft, box, style = Stroke(band, cap = StrokeCap.Round))
                        drawArc(if (on) Color.White else Color.Black, s.angle - 25f, 50f, false, topLeft, box, style = Stroke(band - 3.dp.toPx(), cap = StrokeCap.Round))
                        val a = Math.toRadians(s.angle.toDouble())
                        val p = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * mid
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = (if (on) Color.Black else Color.White).toArgb()
                            textSize = 16.dp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                        drawContext.canvas.nativeCanvas.drawText(labels[s] ?: "+", p.x, p.y - (paint.descent() + paint.ascent()) / 2, paint)
                    }
                }
            }
            else {
                // Hint at rest: the 4 labels, dimmed, just outside the button (↑ → ↓ ←).
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color(0xFF595959).toArgb()
                    textSize = 13.dp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val gap = r + 14.dp.toPx()
                FavSlot.entries.forEach { s ->
                    val a = Math.toRadians(s.angle.toDouble())
                    val p = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * gap
                    drawContext.canvas.nativeCanvas.drawText(labels[s] ?: "+", p.x, p.y - (paint.descent() + paint.ascent()) / 2, paint)
                }
            }
            drawCircle(color, r)
        }
        Icon(ZsIcons.Phone, contentDescription = "Call (drag for favorites, hold to edit them)", tint = Color.White, modifier = Modifier.size(size * 0.47f))
    }
}

/** Same quick pop-in as the music hold menu (approved animation). */
@Composable
private fun pop(open: Boolean): Float = com.zerosync.app.music.rememberMenuPop(open)

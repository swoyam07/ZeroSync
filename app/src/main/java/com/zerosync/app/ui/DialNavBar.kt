package com.zerosync.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerosync.app.ui.theme.ZsColors

/** Finger travel (sideways) that turns the dial by one tab. */
private val DIAL_STEP = 48.dp

/**
 * Nav "dial strip": a thin line with one dot per tab; the current tab's dot is big and white.
 * - Swipe sideways along it: the dial turns one tab per [DIAL_STEP] (with a vibration) and
 *   [onPreview] reports the tab to show big on screen. Lift = [onSelect] that tab.
 * - Tap a dot: go straight to that tab.
 */
@Composable
fun DialNavBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    onPreview: (AppTab?) -> Unit,
    modifier: Modifier = Modifier,
    preview: AppTab? = null,
) {
    val haptics = LocalHapticFeedback.current
    val tabs = AppTab.entries
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnPreview by rememberUpdatedState(onPreview)
    val shown = preview ?: selected

    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = "Tabs: swipe sideways to switch. Now: ${selected.label}" }
            .pointerInput(Unit) {
                fun dotX(i: Int) = size.width * (i + 0.5f) / tabs.size
                detectTapGestures { p ->
                    val i = tabs.indices.minBy { kotlin.math.abs(dotX(it) - p.x) }
                    currentOnSelect(tabs[i])
                }
            }
            .pointerInput(Unit) {
                var start = 0
                var travel = 0f
                var index = 0
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = currentSelected.ordinal
                        index = start
                        travel = 0f
                        currentOnPreview(tabs[start])
                    },
                    onDragEnd = {
                        currentOnPreview(null)
                        currentOnSelect(tabs[index])
                    },
                    onDragCancel = { currentOnPreview(null) },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        travel += dx
                        val now = (start + (travel / DIAL_STEP.toPx()).toInt()).coerceIn(0, tabs.lastIndex)
                        if (now != index) {
                            index = now
                            currentOnPreview(tabs[now])
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                )
            },
    ) {
        val y = size.height / 2f
        drawLine(ZsColors.Secondary.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        tabs.forEachIndexed { i, tab ->
            val x = size.width * (i + 0.5f) / tabs.size
            if (tab == shown) {
                drawCircle(Color.Black, 9.dp.toPx(), Offset(x, y))
                drawCircle(Color.White, 6.dp.toPx(), Offset(x, y))
            } else {
                drawCircle(Color.Black, 5.dp.toPx(), Offset(x, y))
                drawCircle(ZsColors.Secondary, 3.dp.toPx(), Offset(x, y))
            }
        }
    }
}

/** Full-screen name of the tab the dial is on, shown only while swiping the strip. */
@Composable
fun DialPreview(tab: AppTab) {
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text(
            tab.label.uppercase(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Light,
            letterSpacing = 12.sp,
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

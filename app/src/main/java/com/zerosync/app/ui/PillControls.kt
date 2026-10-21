package com.zerosync.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zerosync.app.ui.theme.ZsColors

enum class AppTab(val label: String, val icon: ImageVector) {
    Gallery("Gallery", ZsIcons.Gallery),
    Contact("Contact", ZsIcons.Contact),
    Music("Music", ZsIcons.Music),
    File("File", ZsIcons.File),
    Camera("Camera", ZsIcons.Camera),
}

private val Pill = RoundedCornerShape(50)

/**
 * Space between the floating nav bar and the screen's left/right edges, measured at runtime.
 * Other floating controls use it so their edges line up with the nav bar.
 */
val LocalNavSideGap = compositionLocalOf { 28.dp }

/**
 * Floating "spotlight" navigation bar.
 * The active tab becomes a solid capsule with icon + name; the others are icon-only,
 * so the bar stays compact but you always see where you are. Tap = open tab.
 * Instant state change, no animation (project rule).
 */
@Composable
fun PillNavBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .selectableGroup()
            .clip(Pill)
            // Slightly see-through black: readable over bright videos, still AMOLED-dark.
            .background(Color.Black.copy(alpha = 0.92f))
            .border(1.5.dp, Brush.horizontalGradient(listOf(ZsColors.Primary, ZsColors.Secondary)), Pill)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTab.entries.forEach { tab ->
            val active = tab == selected
            Row(
                Modifier
                    .height(44.dp)
                    .clip(Pill)
                    .background(if (active) ZsColors.Primary else Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(tab) })
                    .padding(horizontal = if (active) 16.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    tab.icon,
                    // The visible label already names the active tab.
                    contentDescription = if (active) null else tab.label,
                    tint = if (active) Color.Black else Color.White,
                    modifier = Modifier.size(22.dp),
                )
                if (active) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Vertical capsule holding on/off icon buttons, styled like [PillNavBar]
 * (near-black, gradient outline). Used for the video feed controls.
 */
@Composable
fun VerticalPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(Pill)
            .background(Color.Black.copy(alpha = 0.92f))
            .border(1.5.dp, Brush.verticalGradient(listOf(ZsColors.Primary, ZsColors.Secondary)), Pill)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** One on/off button inside [VerticalPill]. On = lilac fill + black icon, off = white icon. */
@Composable
fun PillToggle(
    enabled: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector,
    description: String,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (enabled) ZsColors.Primary else Color.Transparent)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = { onToggle() }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) Color.Black else Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Top capsule switch, e.g. Shuffle / Folders. */
@Composable
fun <T> PillSwitch(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.clip(Pill).background(ZsColors.Surface).padding(4.dp)) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .clip(Pill)
                    .background(if (active) ZsColors.PrimaryContainer else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) ZsColors.Tertiary else ZsColors.OnSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

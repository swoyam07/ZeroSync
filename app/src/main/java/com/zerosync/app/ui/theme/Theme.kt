package com.zerosync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ZsColors {
    val Primary = Color(0xFFC19EE0)          // links, active nav icons, brand
    val PrimaryContainer = Color(0xFF6247AA) // active tabs, toggles, selected cards
    val Secondary = Color(0xFF9163CB)        // secondary buttons, chips, outlines
    val Tertiary = Color(0xFFDEC9E9)         // FABs, badges, progress only
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141018)          // raised pills/bars on pure black
    val OnSurface = Color(0xFFE6E0EA)
}

private val ZeroSyncScheme = darkColorScheme(
    primary = ZsColors.Primary,
    onPrimary = Color.Black,
    primaryContainer = ZsColors.PrimaryContainer,
    onPrimaryContainer = ZsColors.Tertiary,
    secondary = ZsColors.Secondary,
    onSecondary = Color.Black,
    tertiary = ZsColors.Tertiary,
    onTertiary = Color.Black,
    background = ZsColors.Background,
    onBackground = ZsColors.OnSurface,
    surface = ZsColors.Background,
    onSurface = ZsColors.OnSurface,
    surfaceVariant = ZsColors.Surface,
    onSurfaceVariant = ZsColors.OnSurface,
)

@Composable
fun ZeroSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZeroSyncScheme, content = content)
}

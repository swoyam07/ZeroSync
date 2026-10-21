package com.zerosync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ZsColors {
    // Black, white and grays only. No other colors anywhere in the UI.
    val Primary = Color(0xFFFFFFFF)          // links, active nav icons, brand, "on" states
    val PrimaryContainer = Color(0xFF3A3A3A) // active tabs, toggles, selected cards
    val Secondary = Color(0xFF8A8A8A)        // secondary buttons, chips, outlines
    val Tertiary = Color(0xFFD0D0D0)         // FABs, badges, progress only
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF141414)          // raised pills/bars on pure black
    val OnSurface = Color(0xFFE6E6E6)
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

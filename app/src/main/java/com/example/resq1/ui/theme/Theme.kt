package com.example.resq1.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val GlassColorScheme = darkColorScheme(
    primary = ElectricCyan,
    secondary = WarningOrange,
    tertiary = SignalGreen,
    background = ObsidianMidnight,
    surface = ObsidianSurface,
    onPrimary = ObsidianMidnight,
    onSecondary = ObsidianMidnight,
    onTertiary = ObsidianMidnight,
    onBackground = TextHigh,
    onSurface = TextHigh,
    error = DangerGlow,
    onError = TextHigh,
    outline = ObsidianBorder
)

@Composable
fun Resq1Theme(
    content: @Composable () -> Unit
) {
    val colorScheme = GlassColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            // Keep status bar icons light for dark background
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

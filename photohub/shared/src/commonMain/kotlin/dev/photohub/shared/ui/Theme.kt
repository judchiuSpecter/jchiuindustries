package dev.photohub.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.photohub.shared.data.ThemeMode

/**
 * Photo-first colour schemes.
 *
 * Both are near-neutral on purpose: a photo grid is the content, and a strongly
 * tinted surface shifts the apparent colour of every thumbnail next to it. The
 * dark scheme is a true dark grey rather than black so thumbnail edges stay
 * visible on OLED, and the light scheme avoids pure white for the same reason.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFEEEEEF),
    onSurfaceVariant = Color(0xFF49454E),
    background = Color(0xFFFCFCFC),
    onBackground = Color(0xFF1B1B1B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A2A52),
    surface = Color(0xFF1B1B1B),
    onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF2A2A2D),
    onSurfaceVariant = Color(0xFFC9C5CF),
    background = Color(0xFF131314),
    onBackground = Color(0xFFE4E2E6),
)

/**
 * Applies the scheme chosen by [mode].
 *
 * [ThemeMode.SYSTEM] defers to the platform, so the app follows a scheduled
 * night mode without the user having to change anything here.
 */
@Composable
fun PhotoHubTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}

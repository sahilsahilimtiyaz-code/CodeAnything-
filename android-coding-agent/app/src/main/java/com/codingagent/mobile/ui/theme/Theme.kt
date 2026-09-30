package com.codingagent.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EE7FF),
    onPrimary = Color(0xFF002733),
    primaryContainer = Color(0xFF0B3B4D),
    onPrimaryContainer = Color(0xFFD2F4FF),
    secondary = Color(0xFF8B9DFF),
    onSecondary = Color(0xFF0A1030),
    tertiary = Color(0xFF7DF0C8),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFE8EDF7),
    surface = Color(0xFF0C1220),
    onSurface = Color(0xFFE8EDF7),
    surfaceVariant = Color(0xFF141C30),
    onSurfaceVariant = Color(0xFFA9B4CC),
    surfaceContainerLowest = Color(0xFF05080F),
    surfaceContainerLow = Color(0xFF0C1220),
    surfaceContainer = Color(0xFF141C30),
    surfaceContainerHigh = Color(0xFF1A2340),
    outline = Color(0xFF2A3558),
    outlineVariant = Color(0xFF232D4D),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF4D1A12),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E0FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF5C6BC0),
    background = Color(0xFFF8F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EEF9),
    onBackground = Color(0xFF1A1C1E),
    onSurface = Color(0xFF1A1C1E),
)

@Composable
fun CodingAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

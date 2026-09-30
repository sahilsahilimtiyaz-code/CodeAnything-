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
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF002B75),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFD6E0FF),
    secondary = Color(0xFF9BB0FF),
    background = Color(0xFF0B0F19),
    surface = Color(0xFF121826),
    surfaceVariant = Color(0xFF1C2438),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    error = Color(0xFFFFB4AB),
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

package com.lifelink.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LifeLinkColors = lightColorScheme(
    primary = Color(0xFFB91C3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBEAEC),
    onPrimaryContainer = Color(0xFF861C32),
    secondary = Color(0xFF168A78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5F5F1),
    onSecondaryContainer = Color(0xFF0B554B),
    background = Color(0xFFF8F7F4),
    onBackground = Color(0xFF17202A),
    surface = Color.White,
    onSurface = Color(0xFF17202A),
    outline = Color(0xFFE6E4DF),
    error = Color(0xFFB3261E)
)

@Composable
fun LifeLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LifeLinkColors,
        content = content
    )
}

package com.lifelink.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LifeLinkLightColors = lightColorScheme(
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
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val LifeLinkDarkColors = darkColorScheme(
    primary = Color(0xFFFFB2BE),
    onPrimary = Color(0xFF65001A),
    primaryContainer = Color(0xFF8F1633),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFF6DD8C3),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF075247),
    onSecondaryContainer = Color(0xFF8DF5DF),
    background = Color(0xFF171314),
    onBackground = Color(0xFFF0DFE0),
    surface = Color(0xFF21191A),
    onSurface = Color(0xFFF0DFE0),
    surfaceVariant = Color(0xFF514346),
    onSurfaceVariant = Color(0xFFD8C2C5),
    outline = Color(0xFF9E8C8F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LifeLinkTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
)

private val LifeLinkShapes = Shapes()

@Composable
fun LifeLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) LifeLinkDarkColors else LifeLinkLightColors,
        typography = LifeLinkTypography,
        shapes = LifeLinkShapes,
        content = content
    )
}

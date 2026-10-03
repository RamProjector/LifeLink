package com.lifelink.app.core.ui.theme

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LifeLinkLightColors = lightColorScheme(
    primary = Color(0xFFB91C3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBEAEC),
    onPrimaryContainer = Color(0xFF861C32),
    secondary = Color(0xFF006B5D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5F5F1),
    onSecondaryContainer = Color(0xFF0B554B),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF202124),
    surface = Color.White,
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFF80868B),
    outlineVariant = Color(0xFFDADCE0),
    surfaceDim = Color(0xFFDADCE0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFF1F3F4),
    surfaceContainerHigh = Color(0xFFECEEF0),
    surfaceContainerHighest = Color(0xFFE3E5E7),
    tertiary = Color(0xFF785900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2A0),
    onTertiaryContainer = Color(0xFF251A00),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
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
    background = Color(0xFF121212),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF444746),
    onSurfaceVariant = Color(0xFFBDC1C6),
    outline = Color(0xFF9AA0A6),
    outlineVariant = Color(0xFF444746),
    surfaceDim = Color(0xFF121212),
    surfaceBright = Color(0xFF3C4043),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1C1C1C),
    surfaceContainer = Color(0xFF232323),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    surfaceContainerHighest = Color(0xFF363636),
    tertiary = Color(0xFFEEC15C),
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF594300),
    onTertiaryContainer = Color(0xFFFFE2A0),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LifeLinkTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp),
)

private val LifeLinkShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK, DYNAMIC }

class ThemeStore(context: Context) {
    private val preferences = context.getSharedPreferences("lifelink_theme", Context.MODE_PRIVATE)

    fun get(): ThemeMode = preferences.getString("mode", ThemeMode.SYSTEM.name)?.let { value ->
        runCatching { ThemeMode.valueOf(value) }.getOrDefault(ThemeMode.SYSTEM)
    } ?: ThemeMode.SYSTEM

    fun save(mode: ThemeMode) {
        preferences.edit().putString("mode", mode.name).apply()
    }
}

@Composable
fun LifeLinkTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM, ThemeMode.DYNAMIC -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (themeMode == ThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (darkTheme) {
        LifeLinkDarkColors
    } else {
        LifeLinkLightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme },
                navigationBarStyle = SystemBarStyle.auto(colors.background.toArgb(), colors.background.toArgb()) { darkTheme },
            )
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = LifeLinkTypography,
        shapes = LifeLinkShapes,
        content = content,
    )
}

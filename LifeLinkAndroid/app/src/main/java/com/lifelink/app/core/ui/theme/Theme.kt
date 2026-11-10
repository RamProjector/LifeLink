package com.lifelink.app.core.ui.theme

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LifeLinkLightColors = lightColorScheme(
    primary = Color(0xFFAA1F3D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E8EB),
    onPrimaryContainer = Color(0xFF65132A),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EDF2),
    onSecondaryContainer = Color(0xFF263445),
    background = Color(0xFFF7F5F3),
    onBackground = Color(0xFF211F20),
    surface = Color(0xFFFFFEFC),
    onSurface = Color(0xFF211F20),
    surfaceVariant = Color(0xFFEDE9E6),
    onSurfaceVariant = Color(0xFF5D5756),
    outline = Color(0xFF827B79),
    outlineVariant = Color(0xFFDED8D5),
    surfaceDim = Color(0xFFE4DFDC),
    surfaceBright = Color(0xFFFFFEFC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F0ED),
    surfaceContainer = Color(0xFFEDE9E6),
    surfaceContainerHigh = Color(0xFFE7E2DF),
    surfaceContainerHighest = Color(0xFFDED8D5),
    tertiary = Color(0xFF795A18),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2A0),
    onTertiaryContainer = Color(0xFF251A00),
    error = Color(0xFFB3263A),
    errorContainer = Color(0xFFF8E2E4),
    onErrorContainer = Color(0xFF5F1420),
)

private val LifeLinkDarkColors = darkColorScheme(
    primary = Color(0xFFFFB3C0),
    onPrimary = Color(0xFF64152A),
    primaryContainer = Color(0xFF76263B),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFB9C5D5),
    onSecondary = Color(0xFF263445),
    secondaryContainer = Color(0xFF303B4A),
    onSecondaryContainer = Color(0xFFD9E2EF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE9E6E4),
    surface = Color(0xFF191B22),
    onSurface = Color(0xFFE9E6E4),
    surfaceVariant = Color(0xFF373941),
    onSurfaceVariant = Color(0xFFC3BEC0),
    outline = Color(0xFF8E8990),
    outlineVariant = Color(0xFF3D3F47),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF393B44),
    surfaceContainerLowest = Color(0xFF0D0F13),
    surfaceContainerLow = Color(0xFF191B22),
    surfaceContainer = Color(0xFF20222A),
    surfaceContainerHigh = Color(0xFF292B34),
    surfaceContainerHighest = Color(0xFF33353E),
    tertiary = Color(0xFFEEC15C),
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF594300),
    onTertiaryContainer = Color(0xFFFFE2A0),
    error = Color(0xFFFFB4BE),
    errorContainer = Color(0xFF5D1E2A),
    onErrorContainer = Color(0xFFFFD9DE),
)

private val LifeLinkTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
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
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class ThemeStore(context: Context) {
    private val preferences = context.getSharedPreferences("lifelink_theme", Context.MODE_PRIVATE)

    fun get(): ThemeMode = preferences.getString("mode", ThemeMode.SYSTEM.name)?.let { value ->
        runCatching { ThemeMode.valueOf(value) }.getOrDefault(ThemeMode.SYSTEM)
    } ?: ThemeMode.SYSTEM

    fun save(mode: ThemeMode) {
        preferences.edit().putString("mode", mode.name).apply()
    }
}

/**
 * Applies the selected color mode, LifeLink typography and shapes, and matching system bar styles.
 * System follows Android appearance; explicit Light and Dark selections always win over it.
 */
@Composable
fun LifeLinkTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) {
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

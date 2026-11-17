package dev.localmusic.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF1A1613)
private val Paper = Color(0xFFF4F0E8)
private val Persimmon = Color(0xFFC24E22)
private val PersimmonDark = Color(0xFFFFB08A)
private val Clay = Color(0xFF6F655C)

private val LightColors = lightColorScheme(
    primary = Persimmon,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8C8),
    onPrimaryContainer = Color(0xFF3B1204),
    secondary = Color(0xFF6B5344),
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E0D6),
    onSurfaceVariant = Clay,
    outline = Color(0xFFD0C6BA),
)

private val DarkColors = darkColorScheme(
    primary = PersimmonDark,
    onPrimary = Color(0xFF3B1608),
    primaryContainer = Color(0xFF7A3014),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE4C4A8),
    onSecondary = Color(0xFF2C2118),
    background = Color(0xFF12100E),
    onBackground = Color(0xFFF6F1E8),
    surface = Color(0xFF12100E),
    onSurface = Color(0xFFF6F1E8),
    surfaceVariant = Color(0xFF2A2521),
    onSurfaceVariant = Color(0xFFC9C0B4),
    outline = Color(0xFF4A433C),
)

private val Type = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun LocalMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Type,
        content = content,
    )
}

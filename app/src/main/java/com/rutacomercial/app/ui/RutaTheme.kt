package com.rutacomercial.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DayColors = lightColorScheme(
    primary = Color(0xFF175A4B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EBDF), onPrimaryContainer = Color(0xFF103D32),
    secondary = Color(0xFF985C10), secondaryContainer = Color(0xFFFFE2B6),
    background = Color(0xFFF7F8F2), surface = Color(0xFFF7F8F2),
    surfaceVariant = Color(0xFFE5EAE3), onSurface = Color(0xFF192D27),
    outline = Color(0xFF697A72), error = Color(0xFFBA1A1A)
)
private val NightColors = darkColorScheme(
    primary = Color(0xFF8DD5B9), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF175A4B), onPrimaryContainer = Color(0xFFD5EBDF),
    secondary = Color(0xFFF4BF78), background = Color(0xFF111D18),
    secondaryContainer = Color(0xFF314955), onSecondaryContainer = Color(0xFFDCF1FA),
    surface = Color(0xFF111D18), surfaceVariant = Color(0xFF32443A),
    onSurface = Color(0xFFE2EAE0)
)

@Composable
fun RutaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) NightColors else DayColors,
        typography = Typography(), content = content)
}

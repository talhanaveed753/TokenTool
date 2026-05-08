package com.example.tokentool.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Cedar200,
    onPrimary = Cedar900,
    primaryContainer = Cedar700,
    onPrimaryContainer = Ink100,
    secondary = Amber200,
    onSecondary = Sand900,
    secondaryContainer = Amber700,
    onSecondaryContainer = Ink100,
    tertiary = Moss200,
    onTertiary = Cedar900,
    tertiaryContainer = Moss700,
    onTertiaryContainer = Ink100,
    background = ColorTokens.DarkBackground,
    onBackground = Ink200,
    surface = ColorTokens.DarkSurface,
    onSurface = Ink200,
    surfaceVariant = ColorTokens.DarkSurfaceVariant,
    onSurfaceVariant = Ink200,
    error = Clay200,
    onError = Sand900,
    errorContainer = Clay700,
    onErrorContainer = Ink100,
    outline = ColorTokens.DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = Cedar700,
    onPrimary = Ink100,
    primaryContainer = Cedar200,
    onPrimaryContainer = Cedar900,
    secondary = Amber700,
    onSecondary = Ink100,
    secondaryContainer = Amber200,
    onSecondaryContainer = Sand900,
    tertiary = Moss700,
    onTertiary = Ink100,
    tertiaryContainer = Moss200,
    onTertiaryContainer = Cedar900,
    background = Sand050,
    onBackground = Sand900,
    surface = Ink100,
    onSurface = Sand900,
    surfaceVariant = Sand100,
    onSurfaceVariant = Ink700,
    error = Clay700,
    onError = Ink100,
    errorContainer = Clay200,
    onErrorContainer = Sand900,
    outline = ColorTokens.LightOutline
)

@Composable
fun TokenToolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private object ColorTokens {
    val DarkBackground = Color(0xFF101512)
    val DarkSurface = Color(0xFF171D1A)
    val DarkSurfaceVariant = Color(0xFF24302B)
    val DarkOutline = Color(0xFF67716A)
    val LightOutline = Color(0xFF8C8A82)
}

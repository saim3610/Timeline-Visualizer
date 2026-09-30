package com.journeyvisualizer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = BrandGreenLight,
    onPrimary = DarkBackground,
    primaryContainer = GreenTintDark,
    onPrimaryContainer = BrandGreenLight,
    secondary = DestinationPink,
    tertiary = SuccessGreen,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    outlineVariant = CardBorderDark,
)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = GreenTint,
    onPrimaryContainer = BrandGreenDark,
    secondary = DestinationPink,
    tertiary = SuccessGreen,
    outlineVariant = CardBorder,
)

@Composable
fun JourneyVisualizerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

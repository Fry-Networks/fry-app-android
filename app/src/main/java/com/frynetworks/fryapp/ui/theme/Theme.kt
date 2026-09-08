package com.frynetworks.fryapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FryBackground = Color(0xFF09090B)
val FryPrimary = Color(0xFFE5271C)
val FrySecondary = Color(0xFF00C49A)
val FryTextPrimary = Color(0xFFEFECEA)
val FryTextSecondary = Color(0xFF9B9793)

private val FryColorScheme = darkColorScheme(
    primary = FryPrimary,
    onPrimary = FryTextPrimary,
    secondary = FrySecondary,
    onSecondary = FryBackground,
    tertiary = FrySecondary,
    background = FryBackground,
    onBackground = FryTextPrimary,
    surface = FryBackground,
    onSurface = FryTextPrimary,
    surfaceVariant = FryBackground,
    onSurfaceVariant = FryTextSecondary,
    error = FryPrimary,
)

/** Dark-only Material3 theme — no dynamic colour, regardless of Android version. */
@Composable
fun FryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FryColorScheme,
        content = content,
    )
}

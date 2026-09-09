package com.frynetworks.fryapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FryBackground = Color(0xFF09090B)
val FrySurface = Color(0xFF1A1A1E)
val FryCard = Color(0xFF242428)
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
    surface = FrySurface,
    onSurface = FryTextPrimary,
    surfaceVariant = FryCard,
    onSurfaceVariant = FryTextSecondary,
    error = FryPrimary,
    onError = FryTextPrimary,
    // The *Container roles are not decorative extras: Material3 draws the FAB from
    // primaryContainer and the NavigationBar's selected-item pill from secondaryContainer.
    // Leaving them unset let the library's default purple through on both, which is very
    // visible on an otherwise Fry-branded screen (observed on the S22 home screen).
    primaryContainer = FryPrimary,
    onPrimaryContainer = FryTextPrimary,
    secondaryContainer = FryCard,
    onSecondaryContainer = FrySecondary,
    tertiaryContainer = FryCard,
    onTertiaryContainer = FrySecondary,
    errorContainer = FryCard,
    onErrorContainer = FryPrimary,
    surfaceContainer = FryCard,
    surfaceContainerHigh = FryCard,
    surfaceContainerHighest = FryCard,
    surfaceContainerLow = FrySurface,
    surfaceContainerLowest = FryBackground,
    outline = FryTextSecondary,
    outlineVariant = FryCard,
)

/** Dark-only Material3 theme — no dynamic colour, regardless of Android version. */
@Composable
fun FryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FryColorScheme,
        content = content,
    )
}

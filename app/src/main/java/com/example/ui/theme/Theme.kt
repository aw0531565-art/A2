package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JumpAssistColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = CyanContainer,
    onPrimaryContainer = OnCyanContainer,
    secondary = KineticAmber,
    onSecondary = OnKineticAmber,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = OnAmberContainer,
    tertiary = ReadyEmerald,
    onTertiary = OnReadyEmerald,
    tertiaryContainer = EmeraldContainer,
    onTertiaryContainer = OnEmeraldContainer,
    error = AlertCrimson,
    onError = OnAlertCrimson,
    errorContainer = CrimsonContainer,
    onErrorContainer = OnCrimsonContainer,
    background = CyberSlateBg,
    onBackground = TextPrimary,
    surface = CyberSurface,
    onSurface = TextPrimary,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = CyberSurfaceHigh,
    outline = CyberOutline,
    outlineVariant = CyberOutlineVariant
)

@Composable
fun JumpAssistTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = JumpAssistColorScheme,
        typography = AppTypography,
        content = content
    )
}

package com.shirou.shibagram.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Material Design 3 Color Scheme for ShibaGram
// Refined palette with warmer tones and better contrast

// Primary Colors — ShibaGram blue (distinctive, refined)
val md_theme_light_primary = Color(0xFF0078D4)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFD4E8FF)
val md_theme_light_onPrimaryContainer = Color(0xFF001D36)

// Secondary Colors
val md_theme_light_secondary = Color(0xFF535F70)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFD7E3F8)
val md_theme_light_onSecondaryContainer = Color(0xFF101C2B)

// Tertiary Colors
val md_theme_light_tertiary = Color(0xFF6B5778)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFF3DAFF)
val md_theme_light_onTertiaryContainer = Color(0xFF251431)

// Error Colors
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)

// Background and Surface — slightly warm white for less eye strain
val md_theme_light_background = Color(0xFFF8F9FC)
val md_theme_light_onBackground = Color(0xFF191C20)
val md_theme_light_surface = Color(0xFFF8F9FC)
val md_theme_light_onSurface = Color(0xFF191C20)
val md_theme_light_surfaceVariant = Color(0xFFDFE2EB)
val md_theme_light_onSurfaceVariant = Color(0xFF43474E)

// Outline
val md_theme_light_outline = Color(0xFF73777F)
val md_theme_light_outlineVariant = Color(0xFFC3C6CF)

// Inverse
val md_theme_light_inverseSurface = Color(0xFF2F3033)
val md_theme_light_inverseOnSurface = Color(0xFFF1F0F4)
val md_theme_light_inversePrimary = Color(0xFFA3C9FF)

// Scrim
val md_theme_light_scrim = Color(0xFF000000)
val md_theme_light_surfaceTint = Color(0xFF0088CC)

// Dark Theme Colors
val md_theme_dark_primary = Color(0xFFA3C9FF)
val md_theme_dark_onPrimary = Color(0xFF00315B)
val md_theme_dark_primaryContainer = Color(0xFF004881)
val md_theme_dark_onPrimaryContainer = Color(0xFFD3E4FF)

val md_theme_dark_secondary = Color(0xFFBBC7DB)
val md_theme_dark_onSecondary = Color(0xFF253140)
val md_theme_dark_secondaryContainer = Color(0xFF3C4858)
val md_theme_dark_onSecondaryContainer = Color(0xFFD7E3F8)

val md_theme_dark_tertiary = Color(0xFFD7BEE4)
val md_theme_dark_onTertiary = Color(0xFF3B2948)
val md_theme_dark_tertiaryContainer = Color(0xFF533F5F)
val md_theme_dark_onTertiaryContainer = Color(0xFFF3DAFF)

val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

val md_theme_dark_background = Color(0xFF111318)
val md_theme_dark_onBackground = Color(0xFFE2E2E6)
val md_theme_dark_surface = Color(0xFF111318)
val md_theme_dark_onSurface = Color(0xFFE2E2E6)
val md_theme_dark_surfaceVariant = Color(0xFF43474E)
val md_theme_dark_onSurfaceVariant = Color(0xFFC3C6CF)

val md_theme_dark_outline = Color(0xFF8D9199)
val md_theme_dark_outlineVariant = Color(0xFF43474E)

val md_theme_dark_inverseSurface = Color(0xFFE3E2E6)
val md_theme_dark_inverseOnSurface = Color(0xFF2F3033)
val md_theme_dark_inversePrimary = Color(0xFF0061A4)

val md_theme_dark_scrim = Color(0xFF000000)
val md_theme_dark_surfaceTint = Color(0xFFA3C9FF)

// Light Color Scheme
val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outlineVariant,
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inversePrimary = md_theme_light_inversePrimary,
    scrim = md_theme_light_scrim,
    surfaceTint = md_theme_light_surfaceTint
)

// Dark Color Scheme
val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outlineVariant,
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    scrim = md_theme_dark_scrim,
    surfaceTint = md_theme_dark_surfaceTint
)

package com.wildodds.gymtracker.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Current accent colour — the single red used for active states across the app. */
val LocalAccentColor = compositionLocalOf { MonoLightAccent }

// Map the monochrome token set onto Material 3 roles so every stock component
// (TopAppBar, Card, Menu, Switch, Button, …) inherits the redesign automatically.
// `accent` is the one red; it lands on `primary` and `error` only — both are
// genuinely-active signals — while everything structural stays greyscale.
private fun monoColorScheme(c: AppColors, accent: Color): ColorScheme =
  if (c.isDark) {
    darkColorScheme(
      primary                = accent,
      onPrimary              = Color.White,
      primaryContainer       = c.surface2,
      onPrimaryContainer     = c.text,
      secondary              = c.text,
      onSecondary            = c.bg,
      secondaryContainer     = c.surface2,
      onSecondaryContainer   = c.text,
      tertiary               = c.text,
      onTertiary             = c.bg,
      background             = c.bg,
      onBackground           = c.text,
      surface                = c.surface,
      onSurface              = c.text,
      surfaceVariant         = c.surface2,
      onSurfaceVariant       = c.textSecondary,
      surfaceTint            = Color.Transparent,
      surfaceContainerLowest = c.bg,
      surfaceContainerLow    = c.surface,
      surfaceContainer       = c.surface,
      surfaceContainerHigh   = c.surface2,
      surfaceContainerHighest= c.surface2,
      surfaceBright          = c.surface2,
      surfaceDim             = c.bg,
      inverseSurface         = c.text,
      inverseOnSurface       = c.bg,
      outline                = c.border,
      outlineVariant         = c.border,
      error                  = accent,
      onError                = Color.White,
      errorContainer         = c.surface2,
      onErrorContainer       = accent,
      scrim                  = Color.Black
    )
  } else {
    lightColorScheme(
      primary                = accent,
      onPrimary              = Color.White,
      primaryContainer       = c.surface2,
      onPrimaryContainer     = c.text,
      secondary              = c.text,
      onSecondary            = c.bg,
      secondaryContainer     = c.surface2,
      onSecondaryContainer   = c.text,
      tertiary               = c.text,
      onTertiary             = c.bg,
      background             = c.bg,
      onBackground           = c.text,
      surface                = c.surface,
      onSurface              = c.text,
      surfaceVariant         = c.surface2,
      onSurfaceVariant       = c.textSecondary,
      surfaceTint            = Color.Transparent,
      surfaceContainerLowest = c.bg,
      surfaceContainerLow    = c.surface,
      surfaceContainer       = c.surface,
      surfaceContainerHigh   = c.surface2,
      surfaceContainerHighest= c.surface2,
      surfaceBright          = c.bg,
      surfaceDim             = c.surface2,
      inverseSurface         = c.text,
      inverseOnSurface       = c.bg,
      outline                = c.border,
      outlineVariant         = c.border,
      error                  = accent,
      onError                = Color.White,
      errorContainer         = c.surface2,
      onErrorContainer       = accent,
      scrim                  = Color.Black
    )
  }

val LocalDarkMode       = staticCompositionLocalOf { false }
val LocalToggleDarkMode = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun AppTheme(
  isDarkMode:       Boolean,
  accentColor:      Color = MonoLightAccent,
  accentLight:      Color = MonoLightAccent,
  accentDark:       Color = MonoLightAccent,
  onToggleDarkMode: () -> Unit,
  content: @Composable () -> Unit
) {
  // Redesign is strictly monochrome: ignore any stored hue/saturation and use the
  // mode-appropriate red so the accent reads correctly on both backgrounds.
  val appColors = if (isDarkMode) DarkAppColors else LightAppColors
  val accent    = appColors.accent
  val colorScheme = monoColorScheme(appColors, accent)

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars     = !isDarkMode
      WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars  = !isDarkMode
    }
  }

  CompositionLocalProvider(
    LocalDarkMode       provides isDarkMode,
    LocalToggleDarkMode provides onToggleDarkMode,
    LocalAccentColor    provides accent,
    LocalAppColors      provides appColors
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography  = AppTypography,
      content     = content
    )
  }
}

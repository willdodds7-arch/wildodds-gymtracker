package com.wildodds.gymtracker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// MONOCHROME DESIGN TOKENS  (single source of truth)
// Editorial / Swiss / brutalist-minimal: black, white, greys + ONE red accent.
// The accent appears ONLY on genuinely active states (a checked item, a selected
// tab). Everything else is greyscale. No gradients, no shadows.
// ─────────────────────────────────────────────────────────────────────────────

// LIGHT
val MonoLightBg            = Color(0xFFFFFFFF)
val MonoLightSurface       = Color(0xFFF4F4F4) // cards, tiles
val MonoLightSurface2      = Color(0xFFEDEDED) // nested / pressed
val MonoLightBorder        = Color(0xFFE4E4E4) // 1px hairlines, dividers
val MonoLightText          = Color(0xFF0B0B0B) // primary
val MonoLightTextSecondary = Color(0xFF6B6B6B) // labels, subtitles
val MonoLightTextTertiary  = Color(0xFFA0A0A0) // meta, captions
val MonoLightAccent        = Color(0xFFE5342B) // active / checked / selected ONLY

// DARK
val MonoDarkBg             = Color(0xFF0C0C0C)
val MonoDarkSurface        = Color(0xFF1A1A1A)
val MonoDarkSurface2       = Color(0xFF242424)
val MonoDarkBorder         = Color(0xFF2A2A2A)
val MonoDarkText           = Color(0xFFFAFAFA)
val MonoDarkTextSecondary  = Color(0xFF9A9A9A)
val MonoDarkTextTertiary   = Color(0xFF6A6A6A)
val MonoDarkAccent         = Color(0xFFFF4D43)

/**
 * Semantic colour set provided down the tree via [LocalAppColors]. Prefer these
 * tokens over raw hex or [androidx.compose.material3.MaterialTheme.colorScheme]
 * roles when building the redesign primitives — they read intent, not value.
 */
@Immutable
data class AppColors(
  val bg:            Color,
  val surface:       Color,
  val surface2:      Color,
  val border:        Color,
  val text:          Color,
  val textSecondary: Color,
  val textTertiary:  Color,
  val accent:        Color,
  val isDark:        Boolean
)

val LightAppColors = AppColors(
  bg            = MonoLightBg,
  surface       = MonoLightSurface,
  surface2      = MonoLightSurface2,
  border        = MonoLightBorder,
  text          = MonoLightText,
  textSecondary = MonoLightTextSecondary,
  textTertiary  = MonoLightTextTertiary,
  accent        = MonoLightAccent,
  isDark        = false
)

val DarkAppColors = AppColors(
  bg            = MonoDarkBg,
  surface       = MonoDarkSurface,
  surface2      = MonoDarkSurface2,
  border        = MonoDarkBorder,
  text          = MonoDarkText,
  textSecondary = MonoDarkTextSecondary,
  textTertiary  = MonoDarkTextTertiary,
  accent        = MonoDarkAccent,
  isDark        = true
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

// ─────────────────────────────────────────────────────────────────────────────
// Back-compat aliases.
// Older screens reference these flat vals directly. They are repointed onto the
// monochrome palette so any lingering usage degrades to greyscale (or the single
// red accent) instead of the old blue. New code should use [LocalAppColors].
// ─────────────────────────────────────────────────────────────────────────────

val AccentPrimary    = MonoLightAccent
val AccentLight      = MonoLightAccent
val AccentDark       = MonoLightAccent
val BgPrimary        = MonoLightBg
val BgSecondary      = MonoLightSurface
val TextPrimary      = MonoLightText
val TextSecondary    = MonoLightTextSecondary
val TextMuted        = MonoLightTextTertiary
val BorderColor      = MonoLightBorder
val GlassBg          = MonoLightSurface
val SurfaceGlass     = MonoLightBg

// Dark mode
val DarkBgPrimary    = MonoDarkBg
val DarkBgSecondary  = MonoDarkSurface
val DarkGlassBg      = MonoDarkSurface
val DarkTextPrimary  = MonoDarkText
val DarkTextSecondary= MonoDarkTextSecondary
val DarkBorderColor  = MonoDarkBorder

// Progression badges — collapsed to greyscale; "more weight" carries the accent.
val BadgeMoreWeight  = MonoLightAccent
val BadgeMoreReps    = MonoLightTextSecondary
val BadgeMoreSets    = MonoLightTextSecondary
val BadgeBetterForm  = MonoLightTextSecondary
val BadgeNoChange    = MonoLightTextTertiary

val SetRowFilled     = MonoLightSurface2
val SetRowFilledDark = MonoDarkSurface2

// ── HSL → RGB helper (retained; used by legacy accent maths) ─────────────────

/**
 * Build a Color from HSL values.
 * hue  0..360, saturation 0..1, lightness 0..1
 */
fun colorFromHsl(hue: Float, saturation: Float, lightness: Float): Color {
  val h = hue / 360f
  val s = saturation.coerceIn(0f, 1f)
  val l = lightness.coerceIn(0f, 1f)

  if (s == 0f) {
    val v = (l * 255).toInt()
    return Color(v, v, v)
  }

  val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
  val p = 2f * l - q

  fun hue2rgb(t: Float): Float {
    val t2 = when {
      t < 0f -> t + 1f
      t > 1f -> t - 1f
      else  -> t
    }
    return when {
      t2 < 1f / 6f -> p + (q - p) * 6f * t2
      t2 < 1f / 2f -> q
      t2 < 2f / 3f -> p + (q - p) * (2f / 3f - t2) * 6f
      else  -> p
    }
  }

  val r = (hue2rgb(h + 1f / 3f) * 255).toInt()
  val g = (hue2rgb(h) * 255).toInt()
  val b = (hue2rgb(h - 1f / 3f) * 255).toInt()
  return Color(r, g, b)
}

/**
 * Derive an accent "palette" — retained for API compatibility with code that
 * still calls it, but the redesign is monochrome so every slot resolves to the
 * single red accent regardless of the requested hue/saturation.
 */
fun accentPalette(hue: Float, sat: Float): Triple<Color, Color, Color> =
  Triple(MonoLightAccent, MonoLightAccent, MonoLightAccent)

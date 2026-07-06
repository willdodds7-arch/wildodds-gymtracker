package com.wildodds.gymtracker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.R

// ─────────────────────────────────────────────────────────────────────────────
// TYPOGRAPHY — Space Grotesk (geometric grotesque, bundled offline).
// One family only. Ships weights 300–700, so 700 is the heaviest we use.
// Fallback chain is the platform sans-serif if a glyph is missing.
// ─────────────────────────────────────────────────────────────────────────────

val SpaceGrotesk = FontFamily(
  Font(R.font.space_grotesk_light,    FontWeight.Light),      // 300
  Font(R.font.space_grotesk_regular,  FontWeight.Normal),     // 400
  Font(R.font.space_grotesk_medium,   FontWeight.Medium),     // 500
  Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),   // 600
  Font(R.font.space_grotesk_bold,     FontWeight.Bold)        // 700
)

// ── Semantic type scale ──────────────────────────────────────────────────────
// Use these named styles for the redesign. They map onto the brief's scale:
//   displayXl  hero numbers / huge focal text
//   display    UPPERCASE day / section titles (apply .uppercase() at the call site)
//   title      row / card titles
//   section    UPPERCASE tracked label, text-secondary
//   body / label / meta

object AppType {
  val displayXl = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold,
    fontSize = 88.sp, lineHeight = 88.sp, letterSpacing = (-0.03).em
  )
  val display = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.Bold,
    fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.01).em
  )
  val title = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
    fontSize = 21.sp, lineHeight = 26.sp
  )
  val section = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.08.em
  )
  val body = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal,
    fontSize = 15.sp, lineHeight = 21.sp
  )
  val label = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
    fontSize = 13.sp, lineHeight = 16.sp
  )
  val meta = TextStyle(
    fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal,
    fontSize = 11.sp, lineHeight = 14.sp
  )
}

// ── Material 3 Typography ─────────────────────────────────────────────────────
// Mapped onto the same scale so every stock Material component (TopAppBar, Text
// without an explicit style, buttons, etc.) inherits Space Grotesk + the new
// rhythm automatically.

val AppTypography = Typography(
  displayLarge   = AppType.displayXl,
  displayMedium  = AppType.display.copy(fontSize = 45.sp, lineHeight = 50.sp),
  displaySmall   = AppType.display,
  headlineLarge  = AppType.display.copy(fontSize = 30.sp, lineHeight = 36.sp),
  headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
  headlineSmall  = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
  titleLarge     = AppType.title,
  titleMedium    = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
  titleSmall     = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
  bodyLarge      = AppType.body,
  bodyMedium     = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
  bodySmall      = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
  labelLarge     = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
  labelMedium    = AppType.label.copy(fontSize = 12.sp),
  labelSmall     = AppType.meta.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.05.em)
)

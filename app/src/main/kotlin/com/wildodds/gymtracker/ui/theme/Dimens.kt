package com.wildodds.gymtracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// SPACING / RADIUS / BORDER TOKENS
// Spacing scale: 4, 8, 12, 16, 20, 24, 32, 40, 48, 64.  Screen edge: 20–24.
// Flat elevation — layers are differentiated by surface colour, never shadow.
// ─────────────────────────────────────────────────────────────────────────────

object Space {
  val xs   = 4.dp
  val sm   = 8.dp
  val md   = 12.dp
  val lg   = 16.dp
  val xl   = 20.dp
  val xxl  = 24.dp
  val x3   = 32.dp
  val x4   = 40.dp
  val x5   = 48.dp
  val x6   = 64.dp

  /** Default horizontal screen-edge padding. */
  val screenEdge = 20.dp
}

object Radii {
  val card = 12.dp
  val sm   = 8.dp
  val pill = 999.dp

  val cardShape = RoundedCornerShape(card)
  val smShape   = RoundedCornerShape(sm)
  val pillShape = RoundedCornerShape(pill)
}

object Stroke {
  /** 1px hairline used for dividers, card borders, checkbox outlines. */
  val hairline = 1.dp
}

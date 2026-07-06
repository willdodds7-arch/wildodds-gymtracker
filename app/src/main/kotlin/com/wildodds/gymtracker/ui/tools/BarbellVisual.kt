package com.wildodds.gymtracker.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A simple, gym-accurate visual of a loaded barbell. Plates are drawn from the collar outward,
 * largest-first (matching [PlateMath.computePerSide]'s ordering), each sized and coloured by weight:
 *
 *  25 kg red · 20 kg blue · 15 kg yellow · 10 kg green · 5 kg black ·
 *  2.5 kg little red · 1.25 kg little orange
 *
 * Only one side's plates are passed in ([perSide]); they're mirrored to both ends of the bar.
 */
data class PlateStyle(val color: Color, val heightFraction: Float, val thickness: Dp)

/** Visual style per plate weight. Heavier = taller and thicker; the two micro-plates are clearly smaller. */
fun plateStyle(weightKg: Float): PlateStyle = when {
  weightKg >= 25f   -> PlateStyle(Color(0xFFD32F2F), 1.00f, 15.dp) // red
  weightKg >= 20f   -> PlateStyle(Color(0xFF1565C0), 0.92f, 14.dp) // blue
  weightKg >= 15f   -> PlateStyle(Color(0xFFF9A825), 0.84f, 13.dp) // yellow
  weightKg >= 10f   -> PlateStyle(Color(0xFF2E7D32), 0.74f, 12.dp) // green
  weightKg >= 5f    -> PlateStyle(Color(0xFF1A1A1A), 0.60f, 11.dp) // black
  weightKg >= 2.5f  -> PlateStyle(Color(0xFFE53935), 0.42f,  8.dp) // little red
  else              -> PlateStyle(Color(0xFFFB8C00), 0.32f,  7.dp) // little orange (1.25)
}

@Composable
fun BarbellVisual(
  perSide: List<Float>,
  modifier: Modifier = Modifier,
  height: Dp = 110.dp,
  barColor: Color = Color(0xFF9E9E9E)
) {
  Box(modifier.fillMaxWidth().height(height)) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
      val w = size.width
      val h = size.height
      val midY = h / 2f
      val plateGapPx = 1.5.dp.toPx()

      // Shaft + collars.
      val shaftHeight = 7.dp.toPx()
      val sleeveHeight = 22.dp.toPx()
      val sleeveLen = 16.dp.toPx()
      val centerGap = 10.dp.toPx() // half-width of the knurled centre with no plates

      // Sorted largest-first so the heaviest plate sits against the collar.
      val plates = perSide.sortedDescending()
      val maxPlateHeight = h * 0.92f

      // Full shaft.
      drawRoundRect(
        color = barColor,
        topLeft = Offset(0f, midY - shaftHeight / 2f),
        size = Size(w, shaftHeight),
        cornerRadius = CornerRadius(shaftHeight / 2f, shaftHeight / 2f)
      )

      // Collars (sleeves) sit just outside the centre gap on each side.
      val leftSleeveX = w / 2f - centerGap - sleeveLen
      val rightSleeveX = w / 2f + centerGap
      drawRoundRect(
        color = barColor,
        topLeft = Offset(leftSleeveX, midY - sleeveHeight / 2f),
        size = Size(sleeveLen, sleeveHeight),
        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
      )
      drawRoundRect(
        color = barColor,
        topLeft = Offset(rightSleeveX, midY - sleeveHeight / 2f),
        size = Size(sleeveLen, sleeveHeight),
        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
      )

      // Draw plates outward from each collar.
      var rightX = rightSleeveX + sleeveLen
      var leftX = leftSleeveX
      for (plate in plates) {
        val style = plateStyle(plate)
        val thick = style.thickness.toPx()
        val ph = maxPlateHeight * style.heightFraction
        val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

        // Right side grows to the right.
        drawRoundRect(
          color = style.color,
          topLeft = Offset(rightX, midY - ph / 2f),
          size = Size(thick, ph),
          cornerRadius = radius
        )
        // A faint outline keeps the black plate readable on any background.
        drawRoundRect(
          color = Color.White.copy(alpha = 0.18f),
          topLeft = Offset(rightX, midY - ph / 2f),
          size = Size(thick, ph),
          cornerRadius = radius,
          style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
        )
        rightX += thick + plateGapPx

        // Left side grows to the left (mirror).
        leftX -= thick
        drawRoundRect(
          color = style.color,
          topLeft = Offset(leftX, midY - ph / 2f),
          size = Size(thick, ph),
          cornerRadius = radius
        )
        drawRoundRect(
          color = Color.White.copy(alpha = 0.18f),
          topLeft = Offset(leftX, midY - ph / 2f),
          size = Size(thick, ph),
          cornerRadius = radius,
          style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
        )
        leftX -= plateGapPx
      }
    }
  }
}

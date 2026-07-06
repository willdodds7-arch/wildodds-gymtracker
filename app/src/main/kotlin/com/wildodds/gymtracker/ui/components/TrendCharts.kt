package com.wildodds.gymtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Minimalist theme-aware trend charts on a Compose Canvas — no heavy charting dependency. Each
 * scales its own data to the available height; callers pass theme colours.
 */

/** A simple line chart. [prMarkers] highlights indices (e.g. PR weeks) with a filled dot. */
@Composable
fun MiniLineChart(
  values: List<Float>,
  color: Color,
  modifier: Modifier = Modifier.fillMaxWidth().height(64.dp),
  prMarkers: Set<Int> = emptySet()
) {
  if (values.size < 2) return
  val min = values.min()
  val max = values.max()
  val range = (max - min).coerceAtLeast(0.0001f)
  Canvas(modifier) {
  val stepX = size.width / (values.size - 1)
  val pad = 6f
  val h = size.height - pad * 2
  fun x(i: Int) = stepX * i
  fun y(v: Float) = pad + h - ((v - min) / range) * h
  val path = Path()
  values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
  drawPath(path, color, style = Stroke(width = 3f))
  values.forEachIndexed { i, v ->
  if (i in prMarkers) drawCircle(color, radius = 5f, center = Offset(x(i), y(v)))
  }
  }
}

/** Grouped bars: solid [values] over faint [planned] (e.g. sessions completed vs planned). */
@Composable
fun MiniBarChart(
  values: List<Float>,
  color: Color,
  planned: List<Float>? = null,
  modifier: Modifier = Modifier.fillMaxWidth().height(64.dp)
) {
  if (values.isEmpty()) return
  val max = (planned?.let { (values + it).max() } ?: values.max()).coerceAtLeast(0.0001f)
  Canvas(modifier) {
  val n = values.size
  val slot = size.width / n
  val barW = slot * 0.55f
  values.forEachIndexed { i, v ->
  val cx = slot * i + slot / 2f
  planned?.getOrNull(i)?.let { p ->
  val ph = (p / max) * size.height
  drawRect(color.copy(alpha = 0.18f),
  topLeft = Offset(cx - barW / 2f, size.height - ph), size = androidx.compose.ui.geometry.Size(barW, ph))
  }
  val vh = (v / max) * size.height
  drawRect(color,
  topLeft = Offset(cx - barW / 2f, size.height - vh), size = androidx.compose.ui.geometry.Size(barW, vh))
  }
  }
}

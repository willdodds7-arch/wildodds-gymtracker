package com.wildodds.gymtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Minimalist theme-aware trend charts on a Compose Canvas — no heavy charting dependency. Each
 * scales its own data to the available height; callers pass theme colours.
 */

// ── Axis charts ───────────────────────────────────────────────────────────────
//
// The Mini* charts above are sparklines: a shape with no scale, so "up a bit" could be 2 kg or
// 40 kg. The charts below carry a labelled Y axis with round-number gridlines, X labels, and
// tap/drag-to-read so the actual number for any week is one touch away.

/** One plotted value and the label shown under it on the X axis (e.g. "W3"). */
@Immutable
data class ChartPoint(val label: String, val value: Float)

/** Compact axis label: 1250 → "1.3k", 102.5 → "102.5", 80 → "80". */
fun formatAxisValue(v: Float): String = when {
  abs(v) >= 10_000f -> "%.0fk".format(v / 1000f)
  abs(v) >= 1_000f  -> "%.1fk".format(v / 1000f).replace(".0k", "k")
  v % 1f == 0f      -> v.toInt().toString()
  abs(v) < 10f      -> "%.2f".format(v).trimEnd('0').trimEnd('.')
  else              -> "%.1f".format(v).trimEnd('0').trimEnd('.')
}

/**
 * Round-number tick values covering [lo]..[hi] in roughly [target] steps (1/2/2.5/5 × 10ⁿ), so
 * the axis reads 60 · 70 · 80 · 90 rather than 61.3 · 72.6 · 83.9.
 */
internal fun niceTicks(lo: Float, hi: Float, target: Int = 4): List<Float> {
  var min = lo
  var max = hi
  if (max - min < 1e-6f) {
    // A flat series still needs a scale around it.
    val pad = if (abs(max) < 1e-6f) 1f else abs(max) * 0.1f
    min -= pad; max += pad
  }
  val rawStep = (max - min) / target
  val mag = 10.0.pow(floor(log10(rawStep.toDouble()))).toFloat()
  val norm = rawStep / mag
  val step = mag * when {
    norm <= 1f   -> 1f
    norm <= 2f   -> 2f
    norm <= 2.5f -> 2.5f
    norm <= 5f   -> 5f
    else         -> 10f
  }
  val start = floor(min / step) * step
  val end = ceil(max / step) * step
  val ticks = mutableListOf<Float>()
  var t = start
  while (t <= end + step * 0.001f && ticks.size < 12) { ticks += t; t += step }
  return ticks
}

/** Line chart with a labelled Y axis, gridlines, X labels and a tap/drag readout. */
@Composable
fun AxisLineChart(
  points: List<ChartPoint>,
  color: Color,
  modifier: Modifier = Modifier.fillMaxWidth().height(180.dp),
  unit: String = "",
  highlight: Set<Int> = emptySet(),
  referenceValue: Float? = null,
  zeroBased: Boolean = false
) = AxisChart(points, color, modifier, unit, highlight, referenceValue, zeroBased, bars = false, secondary = null)

/**
 * Bar chart with a labelled Y axis (always from zero). [secondary] draws a faint bar behind each
 * value — e.g. sessions planned behind sessions completed.
 */
@Composable
fun AxisBarChart(
  points: List<ChartPoint>,
  color: Color,
  modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
  unit: String = "",
  secondary: List<Float>? = null,
  highlight: Set<Int> = emptySet()
) = AxisChart(points, color, modifier, unit, highlight, null, zeroBased = true, bars = true, secondary = secondary)

@Composable
private fun AxisChart(
  points: List<ChartPoint>,
  color: Color,
  modifier: Modifier,
  unit: String,
  highlight: Set<Int>,
  referenceValue: Float?,
  zeroBased: Boolean,
  bars: Boolean,
  secondary: List<Float>?
) {
  if (points.isEmpty()) return
  val measurer = rememberTextMeasurer()
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
  val surface = MaterialTheme.colorScheme.surface
  val onSurface = MaterialTheme.colorScheme.onSurface
  val axisStyle = TextStyle(fontSize = 10.sp, color = labelColor)
  val tipStyle = TextStyle(fontSize = 11.sp, color = surface, fontWeight = FontWeight.SemiBold)

  val ticks = remember(points, secondary, referenceValue, zeroBased) {
    val all = points.map { it.value } + secondary.orEmpty() + listOfNotNull(referenceValue)
    niceTicks(if (zeroBased) 0f else all.min(), all.max())
  }
  var selected by remember(points) { mutableStateOf<Int?>(null) }

  // Shared between drawing and hit-testing so a tap resolves to the point drawn under the finger.
  val geometry = remember { ChartGeometry() }

  // What TalkBack reads: the chart's shape in words, since the drawing itself says nothing.
  val spoken = remember(points, unit) {
    val u = if (unit.isNotEmpty()) " $unit" else ""
    val lo = points.minBy { it.value }
    val hi = points.maxBy { it.value }
    "Chart of ${points.size} points, ${points.first().label} to ${points.last().label}. " +
      "Starts at ${formatAxisValue(points.first().value)}$u, latest ${formatAxisValue(points.last().value)}$u. " +
      "Lowest ${formatAxisValue(lo.value)}$u in ${lo.label}, highest ${formatAxisValue(hi.value)}$u in ${hi.label}."
  }

  Canvas(
    modifier
      .semantics { contentDescription = spoken }
      .pointerInput(points) {
        detectTapGestures { pos ->
          val i = geometry.indexAt(pos.x, points.size, bars)
          selected = if (selected == i) null else i
        }
      }
      .pointerInput(points) {
        detectHorizontalDragGestures { change, _ ->
          selected = geometry.indexAt(change.position.x, points.size, bars)
        }
      }
  ) {
    val tickLabels = ticks.map { measurer.measure(formatAxisValue(it), axisStyle) }
    val yAxisW = (tickLabels.maxOf { it.size.width }.toFloat()) + 8.dp.toPx()
    val xLabelH = 18.dp.toPx()
    val left = yAxisW
    val right = size.width - 6.dp.toPx()
    val top = 8.dp.toPx()
    val bottom = size.height - xLabelH
    val lo = ticks.first()
    val hi = ticks.last()
    val span = (hi - lo).takeIf { it > 0f } ?: 1f
    fun y(v: Float) = bottom - (v - lo) / span * (bottom - top)

    geometry.left = left; geometry.right = right

    // Gridlines + Y labels.
    ticks.forEachIndexed { i, t ->
      val yy = y(t)
      drawLine(gridColor, Offset(left, yy), Offset(right, yy), strokeWidth = 1.dp.toPx())
      val lbl = tickLabels[i]
      drawText(lbl, topLeft = Offset(left - 6.dp.toPx() - lbl.size.width, yy - lbl.size.height / 2f))
    }

    // Optional reference line (e.g. 1.0 = balanced).
    referenceValue?.let { ref ->
      drawLine(
        labelColor.copy(alpha = 0.7f), Offset(left, y(ref)), Offset(right, y(ref)),
        strokeWidth = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
      )
    }

    val n = points.size
    fun x(i: Int): Float = geometry.xFor(i, n, bars)

    // X labels, thinned so they never collide; the last point is always labelled.
    val maxLabels = ((right - left) / 34.dp.toPx()).toInt().coerceAtLeast(2)
    val every = ceil(n / maxLabels.toFloat()).toInt().coerceAtLeast(1)
    points.forEachIndexed { i, p ->
      val show = i == n - 1 || (i % every == 0 && (n - 1 - i) >= every / 2f)
      if (!show) return@forEachIndexed
      val lbl = measurer.measure(p.label, axisStyle)
      val lx = (x(i) - lbl.size.width / 2f).coerceIn(left - 4.dp.toPx(), size.width - lbl.size.width.toFloat())
      drawText(lbl, topLeft = Offset(lx, bottom + 4.dp.toPx()))
    }

    if (bars) {
      val slot = (right - left) / n
      val barW = (slot * 0.6f).coerceAtMost(28.dp.toPx())
      val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
      points.forEachIndexed { i, p ->
        val cx = x(i)
        secondary?.getOrNull(i)?.let { s ->
          val sy = y(s)
          drawRoundRect(color.copy(alpha = 0.18f), Offset(cx - barW / 2f, sy), Size(barW, bottom - sy), radius)
        }
        val vy = y(p.value)
        val dim = selected != null && selected != i
        drawRoundRect(
          if (dim) color.copy(alpha = 0.45f) else color,
          Offset(cx - barW / 2f, vy), Size(barW, (bottom - vy).coerceAtLeast(0f)), radius
        )
      }
    } else {
      if (n >= 2) {
        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(i), y(p.value)) else line.lineTo(x(i), y(p.value)) }
        val area = Path().apply {
          addPath(line)
          lineTo(x(n - 1), bottom); lineTo(x(0), bottom); close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f)), top, bottom))
        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
      }
      points.forEachIndexed { i, p ->
        val c = Offset(x(i), y(p.value))
        if (i in highlight) {
          drawCircle(color, radius = 5.dp.toPx(), center = c)
          drawCircle(surface, radius = 2.dp.toPx(), center = c)
        } else {
          drawCircle(color, radius = 3.dp.toPx(), center = c)
        }
      }
    }

    // Readout for the touched point.
    selected?.takeIf { it in points.indices }?.let { i ->
      val p = points[i]
      val cx = x(i)
      drawLine(onSurface.copy(alpha = 0.35f), Offset(cx, top), Offset(cx, bottom), strokeWidth = 1.dp.toPx())
      if (!bars) drawCircle(color, radius = 6.dp.toPx(), center = Offset(cx, y(p.value)))
      val text = "${p.label}  ${formatAxisValue(p.value)}${if (unit.isNotEmpty()) " $unit" else ""}"
      drawTooltip(measurer, text, tipStyle, onSurface, cx, top, left, size.width)
    }
  }
}

/** Plot-area x positions, written during draw and read by the gesture handlers. */
private class ChartGeometry {
  var left = 0f
  var right = 0f

  fun xFor(i: Int, n: Int, bars: Boolean): Float {
    val w = right - left
    return if (bars || n == 1) left + w / n * (i + 0.5f)
    else left + w * i / (n - 1)
  }

  fun indexAt(px: Float, n: Int, bars: Boolean): Int {
    if (n <= 1 || right <= left) return 0
    val w = right - left
    val raw = if (bars) (px - left) / (w / n) - 0.5f else (px - left) / w * (n - 1)
    return raw.let { kotlin.math.round(it).toInt() }.coerceIn(0, n - 1)
  }
}

private fun DrawScope.drawTooltip(
  measurer: TextMeasurer, text: String, style: TextStyle, bg: Color,
  anchorX: Float, top: Float, minX: Float, maxX: Float
) {
  val layout = measurer.measure(text, style)
  val padH = 8.dp.toPx()
  val padV = 4.dp.toPx()
  val w = layout.size.width + padH * 2
  val h = layout.size.height + padV * 2
  val x = (anchorX - w / 2f).coerceIn(minX, (maxX - w).coerceAtLeast(minX))
  val y = (top - 4.dp.toPx()).coerceAtLeast(0f)
  drawRoundRect(bg.copy(alpha = 0.9f), Offset(x, y), Size(w, h), CornerRadius(6.dp.toPx(), 6.dp.toPx()))
  drawText(layout, topLeft = Offset(x + padH, y + padV))
}

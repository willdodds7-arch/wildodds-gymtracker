@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.data.analytics.TrendData
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.components.AxisBarChart
import com.wildodds.gymtracker.ui.components.AxisLineChart
import com.wildodds.gymtracker.ui.components.ChartPoint
import com.wildodds.gymtracker.ui.components.formatAxisValue
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

@Composable
fun TrendsSheet(
  trends: TrendData?,
  loading: Boolean,
  rangeDays: Int,
  onRange: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  val accent = LocalAccentColor.current
  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(
  Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 36.dp)
  .verticalScroll(rememberScrollState())
  ) {
  Text("Trends", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))

  // Date-range filter.
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  listOf(0 to "All time", 30 to "30 days", 90 to "90 days").forEach { (days, label) ->
  FilterChip(selected = rangeDays == days, onClick = { onRange(days) }, label = { Text(label) })
  }
  }
  Spacer(Modifier.height(16.dp))

  if (loading) {
  Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
  CircularProgressIndicator(color = accent)
  }
  return@Column
  }
  val data = trends ?: run {
  Text("No logged data yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
  return@Column
  }

  // ── Consistency ─────────────────────────────────────────────
  Section("Consistency") {
  val c = data.consistency
  Text("Adherence ${c.adherencePct}%  ·  streak ${c.currentStreak} (best ${c.longestStreak})",
  style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
  color = MaterialTheme.colorScheme.onBackground)
  if (c.perWeek.isNotEmpty()) {
  Spacer(Modifier.height(8.dp))
  AxisBarChart(
  points = c.perWeek.map { ChartPoint("W${it.week}", it.completed.toFloat()) },
  secondary = c.perWeek.map { it.planned.toFloat() },
  color = accent,
  unit = "sessions"
  )
  Text("Sessions completed (solid) vs planned (faint) per week. Tap a bar for its number.",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }

  // ── Volume ─────────────────────────────────────────────────
  if (data.volume.size >= 2) {
  Section("Total volume per week") {
  val vols = data.volume.map { it.totalKg }
  ChangeHeader(latest = vols.last(), first = vols.first(), unit = "kg")
  AxisLineChart(
  points = data.volume.map { ChartPoint("W${it.week}", it.totalKg) },
  color = accent, unit = "kg", zeroBased = true,
  highlight = setOf(vols.indexOf(vols.max()))
  )
  Text("Weight × reps across every set, per week · peak ${vols.max().toInt()} kg (larger dot)",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }

  // ── PRs over time (top lifts) ──────────────────────────────
  data.prs.take(4).forEach { lift ->
  if (lift.points.size >= 2) {
  Section("${lift.exerciseName} — est. 1RM") {
  val prIdx = lift.points.indices.filter { lift.points[it].isE1rmPr }.toSet()
  ChangeHeader(latest = lift.points.last().est1rm, first = lift.points.first().est1rm, unit = "kg")
  AxisLineChart(
  points = lift.points.map { ChartPoint("W${it.week}", it.est1rm) },
  color = accent, unit = "kg", highlight = prIdx
  )
  val prCount = lift.points.count { it.isWeightPr || it.isRepPr }
  Text("$prCount PR(s) — larger dots mark a new best estimated 1RM",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  }

  // ── Push / Pull balance ────────────────────────────────────
  if (data.balance.size >= 2) {
  Section("Push / pull balance") {
  val ratio = data.balance.map {
  if (it.pullVolume > 0f) it.pushVolume / it.pullVolume else 0f
  }
  AxisLineChart(
  points = data.balance.mapIndexed { i, b -> ChartPoint("W${b.week}", ratio[i]) },
  color = accent, referenceValue = 1f
  )
  val latest = ratio.last()
  Text(
  when {
  latest > 1.2f -> "Push : pull ratio per week. Latest ${"%.2f".format(latest)} — push-heavy, add some pulling."
  latest in 0.01f..0.8f -> "Push : pull ratio per week. Latest ${"%.2f".format(latest)} — pull-heavy."
  else -> "Push : pull ratio per week. Dashed line = 1.0 (balanced)."
  },
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
  }
  }
}

/** "1,250 kg  ▲ +150 kg since first week" — the number the chart is about, stated outright. */
@Composable
private fun ChangeHeader(latest: Float, first: Float, unit: String) {
  val delta = latest - first
  val up = delta >= 0f
  Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 8.dp)) {
  Text("${formatAxisValue(latest)} $unit",
  style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onBackground)
  if (delta != 0f) {
  Spacer(Modifier.width(8.dp))
  Text("${if (up) "▲ +" else "▼ "}${formatAxisValue(delta)} $unit since first week",
  style = MaterialTheme.typography.labelMedium,
  color = if (up) Color(0xFF4CAF50) else Color(0xFFF44336),
  modifier = Modifier.padding(bottom = 2.dp))
  }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp,
  modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
  GlassCard(modifier = Modifier.fillMaxWidth()) { Column { content() } }
}

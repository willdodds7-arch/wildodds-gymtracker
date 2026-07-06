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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wildodds.gymtracker.data.analytics.TrendData
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.components.MiniBarChart
import com.wildodds.gymtracker.ui.components.MiniLineChart
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
  MiniBarChart(
  values = c.perWeek.map { it.completed.toFloat() },
  planned = c.perWeek.map { it.planned.toFloat() },
  color = accent
  )
  Text("sessions completed (solid) vs planned (faint), per week",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }

  // ── Volume ─────────────────────────────────────────────────
  if (data.volume.size >= 2) {
  Section("Total volume per week") {
  MiniLineChart(values = data.volume.map { it.totalKg }, color = accent)
  Text("peak ${data.volume.maxOf { it.totalKg }.toInt()} kg",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }

  // ── PRs over time (top lifts) ──────────────────────────────
  data.prs.take(4).forEach { lift ->
  if (lift.points.size >= 2) {
  Section("${lift.exerciseName} — est. 1RM") {
  val prIdx = lift.points.indices.filter { lift.points[it].isE1rmPr }.toSet()
  MiniLineChart(values = lift.points.map { it.est1rm }, color = accent, prMarkers = prIdx)
  val prCount = lift.points.count { it.isWeightPr || it.isRepPr }
  Text("latest ${lift.points.last().est1rm.toInt()} kg  ·  $prCount PR(s) — dots mark new bests",
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
  MiniLineChart(values = ratio, color = accent)
  Text("push : pull volume ratio per week (1.0 = balanced)",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  }
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

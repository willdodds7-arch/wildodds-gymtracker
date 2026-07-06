@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.wildodds.gymtracker.ui.library

import androidx.compose.foundation.clickable
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
import com.wildodds.gymtracker.data.intelligence.Equipment
import com.wildodds.gymtracker.data.ondemand.OnDemandFilter
import com.wildodds.gymtracker.data.ondemand.OnDemandRecommender
import com.wildodds.gymtracker.data.ondemand.OnDemandSession
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

@Composable
fun OnDemandLibrarySheet(
  sessions: List<OnDemandSession>,
  loading: Boolean,
  filter: OnDemandFilter,
  onFilter: (OnDemandFilter) -> Unit,
  onRun: (OnDemandSession) -> Unit,
  onDismiss: () -> Unit
) {
  val accent = LocalAccentColor.current
  ModalBottomSheet(onDismissRequest = onDismiss) {
  Column(
  Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 36.dp)
  .verticalScroll(rememberScrollState())
  ) {
  Text("On-demand sessions", style = MaterialTheme.typography.titleLarge,
  fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(10.dp))

  if (loading) {
  Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
  CircularProgressIndicator(color = accent)
  }
  return@Column
  }

  // Recommended for your block (the items that complement current training).
  val recommended = sessions.filter { it.reason != null }.take(5)
  if (recommended.isNotEmpty()) {
  Label("Recommended for your block")
  recommended.forEach { SessionCard(it, accent, onRun) }
  Spacer(Modifier.height(12.dp))
  }

  // Filters.
  Label("Filters")
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  listOf<Pair<Int?, String>>(null to "Any time", 20 to "≤20 min", 30 to "≤30 min").forEach { (d, label) ->
  FilterChip(selected = filter.maxDurationMin == d, onClick = { onFilter(filter.copy(maxDurationMin = d)) },
  label = { Text(label) })
  }
  }
  Spacer(Modifier.height(6.dp))
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  listOf<Pair<Equipment?, String>>(null to "Any kit", Equipment.DUMBBELL to "Dumbbell", Equipment.BODYWEIGHT to "Bodyweight").forEach { (e, label) ->
  FilterChip(selected = filter.equipment == e, onClick = { onFilter(filter.copy(equipment = e)) },
  label = { Text(label) })
  }
  }

  Spacer(Modifier.height(12.dp))
  Label("All sessions")
  val filtered = OnDemandRecommender.filter(sessions, filter)
  if (filtered.isEmpty()) {
  Text("No sessions match those filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
  } else {
  filtered.forEach { SessionCard(it, accent, onRun) }
  }
  }
  }
}

@Composable
private fun SessionCard(s: OnDemandSession, accent: androidx.compose.ui.graphics.Color, onRun: (OnDemandSession) -> Unit) {
  GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onRun(s) }) {
  Row(verticalAlignment = Alignment.CenterVertically) {
  Column(Modifier.weight(1f)) {
  Text(s.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
  color = MaterialTheme.colorScheme.onBackground)
  Text("${s.source.name.lowercase().replaceFirstChar { it.uppercase() }} · ${s.durationMin} min · ${s.exercises.size} exercises",
  style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  s.reason?.let {
  Text(it, style = MaterialTheme.typography.labelSmall, color = accent)
  }
  }
  Surface(onClick = { onRun(s) }, shape = RoundedCornerShape(20.dp), color = accent) {
  Text("Start", modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
  color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
  }
  }
  }
}

@Composable
private fun Label(text: String) {
  Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
  color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp,
  modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

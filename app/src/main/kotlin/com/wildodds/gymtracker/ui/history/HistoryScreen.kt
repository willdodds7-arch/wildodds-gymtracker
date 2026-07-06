package com.wildodds.gymtracker.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(navController: NavController, vm: HistoryViewModel = viewModel()) {
  val items  by vm.items.collectAsStateWithLifecycle()
  val accent  = LocalAccentColor.current
  val dateFormat = remember { SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()) }

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
  Row(
  verticalAlignment = Alignment.CenterVertically,
  modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
  ) {
  IconButton(onClick = { navController.popBackStack() }) {
  Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
  }
  Text(
  "History",
  fontWeight = FontWeight.Bold,
  fontSize  = 22.sp,
  color  = MaterialTheme.colorScheme.onBackground
  )
  }

  if (items.isEmpty()) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Icon(
  Icons.Default.FitnessCenter, null,
  tint  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
  modifier = Modifier.size(64.dp)
  )
  Spacer(Modifier.height(16.dp))
  Text(
  "No completed sessions yet",
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.bodyLarge
  )
  }
  }
  } else {
  val grouped = items.groupBy { it.completion.weekNumber }
  LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
  grouped.entries.sortedByDescending { it.key }.forEach { (week, weekItems) ->
  item {
  Text(
  "Week $week",
  fontWeight = FontWeight.SemiBold,
  color  = MaterialTheme.colorScheme.onSurfaceVariant,
  modifier  = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
  )
  }
  items(weekItems) { histItem ->
  GlassCard(
  modifier = Modifier
  .fillMaxWidth()
  .padding(horizontal = 16.dp, vertical = 4.dp)
  ) {
  Column {
  Text(
  "Week ${histItem.completion.weekNumber} · Day ${histItem.session.dayNumber}",
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelMedium
  )
  Text(
  histItem.session.name,
  fontWeight = FontWeight.Bold,
  color  = MaterialTheme.colorScheme.onBackground,
  modifier  = Modifier.padding(vertical = 2.dp)
  )
  Row(
  modifier  = Modifier.fillMaxWidth(),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment  = Alignment.CenterVertically
  ) {
  Text(
  dateFormat.format(Date(histItem.completion.completedAt)),
  color = MaterialTheme.colorScheme.onSurfaceVariant,
  style = MaterialTheme.typography.labelMedium
  )
  if (histItem.totalVolumeKg > 0) {
  Surface(
  color = accent.copy(alpha = 0.15f),
  shape = RoundedCornerShape(12.dp)
  ) {
  Text(
  "Vol: ${"%,.0f".format(histItem.totalVolumeKg)} kg",
  modifier  = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
  color  = accent,
  style  = MaterialTheme.typography.labelSmall,
  fontWeight = FontWeight.Medium
  )
  }
  }
  }
  }
  }
  }
  }
  }
  }
  }
}

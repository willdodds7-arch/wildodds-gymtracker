package com.wildodds.gymtracker.ui.achievements

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import com.wildodds.gymtracker.data.gamification.AchievementCategory
import com.wildodds.gymtracker.data.gamification.AchievementProgress
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

/**
 * The calm Achievements/Profile area: a quiet streak + count header, then earned and locked
 * achievements with progress rings. No confetti, no nagging — understated by design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(navController: NavController, vm: AchievementsViewModel = viewModel()) {
  val state by vm.state.collectAsStateWithLifecycle()
  val accent = LocalAccentColor.current
  LaunchedEffect(Unit) { vm.refresh() }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text("Achievements", fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
      )
    }
  ) { padding ->
    if (!state.enabled) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text("Achievements are turned off in Settings.",
          color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
      }
      return@Scaffold
    }

    val byCategory = state.progress.groupBy { it.definition.category }
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      item { HeaderCard(streak = state.currentStreakDays, unlocked = state.unlockedCount, total = state.totalCount, accent = accent) }

      AchievementCategory.entries.forEach { category ->
        val items = byCategory[category].orEmpty()
        if (items.isNotEmpty()) {
          item(key = "hdr_$category") {
            Text(category.label(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp,
              modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
          }
          items(items, key = { it.definition.id }) { AchievementRow(it, accent) }
        }
      }
    }
  }
}

@Composable
private fun HeaderCard(streak: Int, unlocked: Int, total: Int, accent: androidx.compose.ui.graphics.Color) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
      Stat("🔥 $streak", "day streak", accent)
      Stat("$unlocked / $total", "earned", accent)
    }
  }
}

@Composable
private fun Stat(value: String, label: String, accent: androidx.compose.ui.graphics.Color) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = accent)
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun AchievementRow(p: AchievementProgress, accent: androidx.compose.ui.graphics.Color) {
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
      CircularProgressIndicator(
        progress = { p.fraction },
        modifier = Modifier.fillMaxSize(),
        strokeWidth = 4.dp,
        color = if (p.unlocked) accent else accent.copy(alpha = 0.45f),
        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
      )
      if (p.unlocked) {
        Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(22.dp))
      } else {
        Text("${p.current}", style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      Text(p.definition.title, style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (p.unlocked) FontWeight.SemiBold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onBackground)
      Text(
        if (p.unlocked) p.definition.description else "${p.definition.description} · ${p.current}/${p.target}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

private fun AchievementCategory.label(): String = when (this) {
  AchievementCategory.CONSISTENCY -> "CONSISTENCY"
  AchievementCategory.VOLUME -> "VOLUME"
  AchievementCategory.MILESTONES -> "MILESTONES"
  AchievementCategory.HABITS -> "HABITS"
  AchievementCategory.EXPLORATION -> "EXPLORATION"
  AchievementCategory.SIDE_QUESTS -> "SIDE QUESTS"
}

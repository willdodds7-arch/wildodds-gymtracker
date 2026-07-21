@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.profile.MainLift
import com.wildodds.gymtracker.data.tools.PlateMath
import com.wildodds.gymtracker.ui.achievements.AchievementsViewModel
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.settings.FeatureFlags
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import com.wildodds.gymtracker.ui.tools.BarbellVisual
import com.wildodds.gymtracker.data.gamification.AchievementProgress

/**
 * Profile: your barbell maxes (drawn as loaded bars), the potential 1RM your logs suggest, and your
 * achievements — both earned and still locked.
 */
@Composable
fun ProfileScreen(
  navController: NavController,
  vm: ProfileViewModel = viewModel(),
  achievementsVm: AchievementsViewModel = viewModel()
) {
  val state by vm.state.collectAsStateWithLifecycle()
  val achState by achievementsVm.state.collectAsStateWithLifecycle()
  val accent = LocalAccentColor.current
  val friendsEnabled = FeatureFlags.isEnabled(SettingsRegistry.FRIENDS)
  val marketplaceEnabled = FeatureFlags.isEnabled(SettingsRegistry.CREATOR_MARKETPLACE)
  LaunchedEffect(Unit) { vm.refresh(); achievementsVm.refresh() }

  var editing by remember { mutableStateOf<MaxRow?>(null) }
  editing?.let { row ->
    EditOneRmDialog(
      row = row,
      onDismiss = { editing = null },
      onSave = { kg -> vm.setOneRm(row.lift, kg); editing = null }
    )
  }

  Column(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
  ) {
    Spacer(Modifier.height(20.dp))
    // Own verified badge — server-derived (profiles.is_verified_creator via CreatorRepository),
    // never client-asserted. Shown only while the subscription is active.
    var ownVerified by remember { mutableStateOf(false) }
    if (marketplaceEnabled) {
      val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
      LaunchedEffect(Unit) {
        ownVerified = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          (com.wildodds.gymtracker.data.creator.CreatorRepository(ctx).myStatus()
            as? com.wildodds.gymtracker.data.backend.RemoteResult.Success)?.value?.isVerified ?: false
        }
      }
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
    ) {
      Text("Profile", fontWeight = FontWeight.Bold, fontSize = 22.sp,
        color = MaterialTheme.colorScheme.onBackground)
      if (marketplaceEnabled && ownVerified) {
        Spacer(Modifier.width(8.dp))
        com.wildodds.gymtracker.ui.components.VerifiedBadge(com.wildodds.gymtracker.ui.components.BadgeSize.LG)
      }
      Spacer(Modifier.weight(1f))
      IconButton(
        onClick = { navController.navigate("settings") },
        modifier = Modifier.testTag("profile_settings_gear")
      ) {
        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onBackground)
      }
    }
    Spacer(Modifier.height(8.dp))

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        GlassCard(modifier = Modifier.fillMaxWidth().clickable { navController.navigate("history") }) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text("Training History", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
              Text("Every completed session", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (marketplaceEnabled) {
        item {
          GlassCard(modifier = Modifier.fillMaxWidth()
            .clickable { navController.navigate("creator_hub") }
            .testTag("profile_creator_row")
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.WorkspacePremium, null, tint = MaterialTheme.colorScheme.onBackground)
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text("Creator hub", style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text("Verified Creator status, your listings, earnings", style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
        item {
          GlassCard(modifier = Modifier.fillMaxWidth()
            .clickable { navController.navigate("marketplace") }
            .testTag("profile_marketplace_row")
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Storefront, null, tint = MaterialTheme.colorScheme.onBackground)
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text("Marketplace", style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text("Programs by Verified Creators · your purchases", style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
      if (friendsEnabled) {
        item {
          GlassCard(modifier = Modifier.fillMaxWidth()
            .clickable { navController.navigate("friends") }
            .testTag("profile_friends_row")
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Group, null, tint = MaterialTheme.colorScheme.onBackground)
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text("Friends", style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text("Add friends, share programs, send motivation", style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
      item {
        Text("YOUR TRAINING", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
      }
      item {
        FavouriteCard(
          label = "Favourite muscle group",
          value = state.favouriteMuscle,
          caption = "most-trained muscle · last 4 weeks",
          emptyHint = "Log some sets and your most-hit muscle shows here.",
          accent = accent
        )
      }
      item {
        FavouriteCard(
          label = "Favourite split",
          value = state.favouriteSplit,
          caption = "most-trained split · last 12 weeks",
          emptyHint = "Finish a few sessions and your go-to split shows here.",
          accent = accent
        )
      }

      item {
        Spacer(Modifier.height(8.dp))
        Text("YOUR MAXES", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
      }
      items(state.maxes, key = { it.lift.key }) { row ->
        MaxCard(row = row, accent = accent, onEdit = { editing = row })
      }

      item {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("ACHIEVEMENTS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
          if (achState.enabled) {
            Text("${achState.unlockedCount} / ${achState.totalCount}",
              style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Bold)
          }
        }
      }
      if (!achState.enabled) {
        item {
          Text("Achievements are turned off in Settings.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else if (achState.progress.isEmpty()) {
        item {
          Text("Achievements are coming in a future update.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        items(achState.progress, key = { it.definition.id }) { p -> ProfileAchievementRow(p, accent) }
      }

      item { Spacer(Modifier.height(80.dp)) }
    }
  }
}

@Composable
private fun FavouriteCard(
  label: String,
  value: String?,
  caption: String,
  emptyHint: String,
  accent: androidx.compose.ui.graphics.Color
) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(4.dp))
      if (value != null) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
          color = accent)
        Spacer(Modifier.height(2.dp))
        Text(caption, style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        Text(emptyHint, style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun MaxCard(row: MaxRow, accent: androidx.compose.ui.graphics.Color, onEdit: () -> Unit) {
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(row.lift.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
          if (row.statedKg > 0f) {
            Text("${formatKg(row.statedKg)} kg", style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold, color = accent)
          } else {
            Text("Not set", style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(12.dp)) {
          Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (row.statedKg > 0f) "Edit" else "Add")
        }
      }

      if (row.statedKg > 0f) {
        Spacer(Modifier.height(10.dp))
        val perSide = PlateMath.computePerSide(row.statedKg).perSide
        BarbellVisual(perSide = perSide)
      }

      if (row.showPotential) {
        Spacer(Modifier.height(8.dp))
        Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
          Text(
            "Potential ${formatKg(row.potentialKg!!)} kg — from your best logged set",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.SemiBold
          )
        }
      }
    }
  }
}

@Composable
private fun ProfileAchievementRow(p: AchievementProgress, accent: androidx.compose.ui.graphics.Color) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
      CircularProgressIndicator(
        progress = { p.fraction }, modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp,
        color = if (p.unlocked) accent else accent.copy(alpha = 0.4f),
        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
      )
      Icon(
        if (p.unlocked) Icons.Default.Check else Icons.Default.Lock,
        null, tint = if (p.unlocked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp)
      )
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

@Composable
private fun EditOneRmDialog(row: MaxRow, onDismiss: () -> Unit, onSave: (Float) -> Unit) {
  val accent = LocalAccentColor.current
  var text by remember { mutableStateOf(if (row.statedKg > 0f) formatKg(row.statedKg) else "") }
  val value = text.toFloatOrNull()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("${row.lift.label} 1RM") },
    text = {
      Column {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
          label = { Text("Your 1RM (kg)") }, singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
        )
        Spacer(Modifier.height(12.dp))
        Text(
          "Be honest with yourself. If you inflate your 1RM, you only stall your own progress — " +
          "every lift based on a percentage of it becomes harder than it should be, and the numbers " +
          "stop helping you.",
          style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { value?.let { onSave(it) } },
        enabled = value != null && value > 0f
      ) { Text("Save", color = accent, fontWeight = FontWeight.Bold) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

private fun formatKg(v: Float): String =
  if (v % 1f == 0f) v.toInt().toString() else ((v * 10).toInt() / 10f).toString()

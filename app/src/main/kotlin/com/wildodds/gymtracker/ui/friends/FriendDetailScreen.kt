@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.friends.FriendsLogic
import com.wildodds.gymtracker.data.gamification.AchievementCatalog
import com.wildodds.gymtracker.data.profile.MainLift
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A friend's page: name, last visit, current program, PRs, achievements, public programs, and
 *  their friends — plus motivate / share-a-program / unfriend actions. */
@Composable
fun FriendDetailScreen(
  navController: NavController,
  friendId: String,
  vm: FriendsViewModel = viewModel()
) {
  val accent = LocalAccentColor.current
  val detail by vm.detail.collectAsState()
  var showShare by remember { mutableStateOf(false) }
  var showMotivate by remember { mutableStateOf(false) }
  var confirmUnfriend by remember { mutableStateOf(false) }

  LaunchedEffect(friendId) { vm.loadDetail(friendId) }

  val profile = detail.profile
  val name = profile?.username ?: "Friend"

  if (showShare) {
    ShareProgramDialog(
      onShare = { programId -> vm.shareProgram(friendId, programId); showShare = false },
      onDismiss = { showShare = false }
    )
  }
  if (showMotivate) {
    AlertDialog(
      onDismissRequest = { showMotivate = false },
      title = { Text("Motivate $name") },
      text = { Text("They'll get your nudge as a notification and can flex back from their next session.") },
      confirmButton = {
        TextButton(onClick = {
          vm.sendMotivation(friendId, "Get back in there, $name! 💪"); showMotivate = false
        }) { Text("Send 💪") }
      },
      dismissButton = { TextButton(onClick = { showMotivate = false }) { Text("Cancel") } }
    )
  }
  if (confirmUnfriend) {
    AlertDialog(
      onDismissRequest = { confirmUnfriend = false },
      title = { Text("Remove $name?") },
      text = { Text("You'll stop seeing each other's activity. They aren't notified.") },
      confirmButton = {
        TextButton(onClick = {
          confirmUnfriend = false; vm.unfriend(friendId); navController.popBackStack()
        }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { confirmUnfriend = false }) { Text("Cancel") } }
    )
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text(name, fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
      )
    }
  ) { padding ->
    if (detail.isLoading) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = accent)
      }
      return@Scaffold
    }
    val now = System.currentTimeMillis()
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding).testTag("friend_detail"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        GlassCard(Modifier.fillMaxWidth()) {
          Column {
            Text("Last gym visit: ${FriendsLogic.lastGymLabel(profile?.lastSessionAt, now)}",
              color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
            profile?.lastSessionName?.takeIf { it.isNotBlank() }?.let {
              Text("Last session: $it", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            profile?.currentProgram?.takeIf { it.isNotBlank() }?.let {
              Spacer(Modifier.height(4.dp))
              Text("Current program: $it", style = MaterialTheme.typography.bodySmall,
                color = accent)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              if (FriendsLogic.canMotivate(profile?.lastSessionAt, now)) {
                OutlinedButton(onClick = { showMotivate = true }, shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.testTag("detail_motivate")) {
                  Text("Motivate 💪", color = accent)
                }
              }
              OutlinedButton(onClick = { showShare = true }, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("detail_share_program")) {
                Text("Share a program", color = MaterialTheme.colorScheme.onBackground)
              }
            }
          }
        }
      }

      // PRs
      item {
        GlassCard(Modifier.fillMaxWidth()) {
          Column {
            Text("PRs", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            val prs = (profile?.prs as? JsonObject)
            if (prs == null) Text("Not shared yet", style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
            else MainLift.entries.forEach { lift ->
              val kg = prs[lift.key]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
              if (kg > 0f) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                  Text(lift.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                  Text("${if (kg % 1f == 0f) kg.toInt() else kg} kg", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                }
              }
            }
          }
        }
      }

      // Achievements — published as stable ids; titles come from the shared local catalog.
      item {
        GlassCard(Modifier.fillMaxWidth()) {
          Column {
            Text("Achievements", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            val ids = runCatching {
              profile?.achievements?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            }.getOrDefault(emptyList())
            val unlocked = AchievementCatalog.ALL.filter { it.id in ids.toSet() }
            if (unlocked.isEmpty()) {
              Text("None unlocked yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            } else {
              Text("${unlocked.size} unlocked 🏆", color = accent, style = MaterialTheme.typography.bodyMedium)
              Spacer(Modifier.height(4.dp))
              unlocked.forEach { a ->
                Text("• ${a.title} — ${a.description}", style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp))
              }
            }
          }
        }
      }

      // Public programs
      item {
        GlassCard(Modifier.fillMaxWidth()) {
          Column {
            Text("Their programs", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            val programs = runCatching { profile?.publicPrograms?.jsonArray }.getOrNull()
            if (programs.isNullOrEmpty()) {
              Text("No custom programs shared", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else programs.forEach { el ->
              val obj = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
              val pName = obj["name"]?.jsonPrimitive?.content ?: return@forEach
              val active = obj["active"]?.jsonPrimitive?.content == "true"
              Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(pName, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground,
                  style = MaterialTheme.typography.bodySmall)
                if (active) Text("running now", color = accent, style = MaterialTheme.typography.labelSmall)
              }
            }
            Spacer(Modifier.height(4.dp))
            Text("Ask them to share one with you — it lands straight in your library.",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
          }
        }
      }

      // Their friends
      item {
        GlassCard(Modifier.fillMaxWidth()) {
          Column {
            Text("Their friends", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            if (detail.theirFriends.isEmpty()) {
              Text("No friends to show", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else detail.theirFriends.forEach { f ->
              Text("• ${f.username ?: "Anonymous"}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 1.dp))
            }
          }
        }
      }

      item {
        TextButton(onClick = { confirmUnfriend = true }, modifier = Modifier.testTag("unfriend")) {
          Text("Remove friend", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

/** Pick one of MY user-created programs to send. */
@Composable
private fun ShareProgramDialog(onShare: (Long) -> Unit, onDismiss: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var programs by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
  LaunchedEffect(Unit) {
    programs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching {
        com.wildodds.gymtracker.data.db.AppDatabase.getInstance(context)
          .programDao().getAllProgramsOnce()
          .filter { it.isUserCreated && it.name != com.wildodds.gymtracker.data.repository.GymRepository.ON_DEMAND_PROGRAM_NAME }
          .map { it.id to it.name }
      }.getOrDefault(emptyList())
    }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Share a program") },
    text = {
      val list = programs
      when {
        list == null -> CircularProgressIndicator()
        list.isEmpty() -> Text("You have no custom programs to share yet — build or import one first.")
        else -> LazyColumn {
          items(list, key = { it.first }) { (id, pName) ->
            TextButton(onClick = { onShare(id) }, modifier = Modifier.fillMaxWidth()) {
              Text(pName, color = MaterialTheme.colorScheme.onBackground)
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

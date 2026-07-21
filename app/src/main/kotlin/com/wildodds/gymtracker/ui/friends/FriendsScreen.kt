@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.friends

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.data.friends.FriendEventsScheduler
import com.wildodds.gymtracker.data.friends.FriendNotifier
import com.wildodds.gymtracker.data.friends.FriendsLogic
import com.wildodds.gymtracker.ui.components.GlassCard
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

/** Friends home: invite card (share link / enter code), the friends list, and the activity toggle. */
@Composable
fun FriendsScreen(navController: NavController, vm: FriendsViewModel = viewModel()) {
  val accent = LocalAccentColor.current
  val context = LocalContext.current
  val state by vm.state.collectAsState()
  val snackbar = remember { SnackbarHostState() }
  var showRedeem by remember { mutableStateOf(false) }
  var motivateTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // id to name

  // POST_NOTIFICATIONS (Android 13+): friend messages arrive as notifications, so surface a
  // one-tap enable card while it's missing (same opt-in flow as the Phase 7A reminders).
  var hasNotifPermission by remember { mutableStateOf(FriendNotifier.hasPermission(context)) }
  val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    hasNotifPermission = FriendNotifier.hasPermission(context)
  }

  val inbox by vm.programInbox.collectAsState()
  LaunchedEffect(Unit) {
    vm.refresh(); vm.loadProgramInbox()
    // Deliver anything pending right now — makes a fresh motivation/flex visible immediately.
    FriendEventsScheduler.pollNow(context)
  }
  LaunchedEffect(state.error, state.info) {
    state.error?.let { snackbar.showSnackbar(it); vm.clearMessages() }
    state.info?.let { snackbar.showSnackbar(it); vm.clearMessages() }
  }

  if (showRedeem) {
    RedeemCodeDialog(
      onRedeem = { code -> vm.redeem(code); showRedeem = false },
      onDismiss = { showRedeem = false }
    )
  }
  motivateTarget?.let { (id, name) ->
    MotivateDialog(
      friendName = name,
      onSend = { msg -> vm.sendMotivation(id, msg); motivateTarget = null },
      onDismiss = { motivateTarget = null }
    )
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = { Text("Friends", fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
      )
    }
  ) { padding ->
    when {
      state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = accent)
      }
      !state.signedIn -> SignedOutPrompt(padding) { navController.navigate("account_auth") }
      else -> LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        item {
          InviteCard(
            code = state.myCode,
            onShare = {
              val code = state.myCode ?: return@InviteCard
              val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                  Intent.EXTRA_TEXT,
                  "Add me on Wild Odds Gym Tracker! Open ${FriendsLogic.inviteLink(code)} " +
                    "or enter my friend code in the app: $code"
                )
              }
              context.startActivity(Intent.createChooser(send, "Invite a friend"))
            },
            onEnterCode = { showRedeem = true }
          )
        }
        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                  Text("Notifications are off", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                  Text("Enable them to get motivation from friends and their activity",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                  onClick = { notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS") },
                  modifier = Modifier.testTag("friends_enable_notifications")
                ) { Text("Enable", color = accent, fontWeight = FontWeight.SemiBold) }
              }
            }
          }
        }
        item {
          GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text("Friend workout alerts", fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onBackground)
                Text("Get a notification when a friend starts a session",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Switch(
                checked = state.notifyOnFriendSession,
                onCheckedChange = { on ->
                  vm.setNotifyOnFriendSession(on)
                  if (on && !hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermLauncher.launch("android.permission.POST_NOTIFICATIONS")
                  }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = accent, checkedTrackColor = accent.copy(0.4f)),
                modifier = Modifier.testTag("friends_notify_toggle")
              )
            }
          }
        }
        items(inbox, key = { "inbox_${it.first.id}" }) { (event, who) ->
          GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
              Text("$who shared a program with you", fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground)
              Spacer(Modifier.height(8.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = { vm.acceptSharedProgram(event) },
                  colors = ButtonDefaults.buttonColors(containerColor = accent),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.testTag("accept_program_${event.id}")
                ) { Text("Add to library", fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = { vm.dismissSharedProgram(event) }) {
                  Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }
        }
        if (state.friends.isEmpty()) {
          item {
            Text(
              "No friends yet — share your link to get started.",
              modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium
            )
          }
        }
        items(state.friends, key = { it.id }) { friend ->
          val now = System.currentTimeMillis()
          GlassCard(
            modifier = Modifier.fillMaxWidth().clickable { navController.navigate("friend/${friend.id}") }
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(friend.username ?: "Friend", fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onBackground)
                Text(
                  "Last gym visit: ${FriendsLogic.lastGymLabel(friend.lastSessionAt, now)}",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (FriendsLogic.canMotivate(friend.lastSessionAt, now))
                    MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                  else MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              if (FriendsLogic.canMotivate(friend.lastSessionAt, now)) {
                OutlinedButton(
                  onClick = { motivateTarget = friend.id to (friend.username ?: "Friend") },
                  shape = RoundedCornerShape(20.dp),
                  modifier = Modifier.testTag("motivate_${friend.id}")
                ) { Text("Motivate 💪", fontSize = 12.sp, color = accent) }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SignedOutPrompt(padding: PaddingValues, onSignIn: () -> Unit) {
  val accent = LocalAccentColor.current
  Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
      Text("Friends needs an account", fontWeight = FontWeight.Bold, fontSize = 20.sp,
        color = MaterialTheme.colorScheme.onBackground)
      Spacer(Modifier.height(8.dp))
      Text("Sign in to add friends, share programs and send motivation.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(20.dp))
      Button(
        onClick = onSignIn,
        colors = ButtonDefaults.buttonColors(containerColor = accent),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.testTag("friends_sign_in")
      ) { Text("Sign in / Create account", fontWeight = FontWeight.Bold) }
    }
  }
}

@Composable
private fun InviteCard(code: String?, onShare: () -> Unit, onEnterCode: () -> Unit) {
  val accent = LocalAccentColor.current
  GlassCard(modifier = Modifier.fillMaxWidth()) {
    Column {
      Text("Your friend code", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(code ?: "…", fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 2.sp,
        color = accent, modifier = Modifier.testTag("my_friend_code"))
      Spacer(Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
          onClick = onShare, enabled = code != null,
          colors = ButtonDefaults.buttonColors(containerColor = accent),
          shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).testTag("share_invite")
        ) {
          Icon(Icons.Default.Share, null, Modifier.width(18.dp)); Spacer(Modifier.width(6.dp))
          Text("Share invite", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
          onClick = onEnterCode, shape = RoundedCornerShape(10.dp),
          modifier = Modifier.weight(1f).testTag("enter_code")
        ) { Text("Enter a code", color = MaterialTheme.colorScheme.onBackground) }
      }
    }
  }
}

@Composable
fun RedeemCodeDialog(onRedeem: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
  var text by remember { mutableStateOf(initial) }
  val parsed = FriendsLogic.parseInvite(text)
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add a friend") },
    text = {
      Column {
        Text("Paste their invite link or enter their friend code.")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
          value = text, onValueChange = { text = it },
          label = { Text("Code or link") }, singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("redeem_input")
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { parsed?.let(onRedeem) }, enabled = parsed != null,
        modifier = Modifier.testTag("redeem_confirm")) { Text("Add friend") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

@Composable
private fun MotivateDialog(friendName: String, onSend: (String) -> Unit, onDismiss: () -> Unit) {
  var message by remember { mutableStateOf("Get back in there, $friendName! 💪") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Motivate $friendName") },
    text = {
      Column {
        Text("They'll get this as a notification, and can reply with a flex once they've logged a session.")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
          value = message, onValueChange = { message = it },
          modifier = Modifier.fillMaxWidth().testTag("motivate_message"), minLines = 2
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSend(message) }, enabled = message.isNotBlank()) { Text("Send") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

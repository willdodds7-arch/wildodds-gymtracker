@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.wildodds.gymtracker.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wildodds.gymtracker.ui.theme.LocalAccentColor

/**
 * Type-to-confirm + re-auth account deletion. Deletion is immediate and irreversible — the copy
 * says so plainly. The user chooses whether to also wipe local data on this device.
 */
@Composable
fun DeleteAccountScreen(navController: NavController, vm: AccountViewModel = viewModel()) {
  val accent = LocalAccentColor.current
  val state by vm.delete.collectAsState()
  val error = MaterialTheme.colorScheme.error

  var typed by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var wipeLocal by remember { mutableStateOf(false) }

  // On completion, drop back to the root; AuthGate takes over (session is gone).
  LaunchedEffect(state.step) {
    if (state.step == AccountViewModel.DeleteState.Step.DONE) {
      navController.popBackStack(navController.graph.startDestinationId, inclusive = false)
    }
  }

  val busy = state.step == AccountViewModel.DeleteState.Step.REAUTHING ||
    state.step == AccountViewModel.DeleteState.Step.DELETING
  val confirmed = typed.trim() == vm.confirmPhrase

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text("Delete account", fontWeight = FontWeight.Bold) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
      )
    }
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)
        .verticalScroll(rememberScrollState()).imePadding()
    ) {
      Spacer(Modifier.height(8.dp))
      Text(
        "This permanently deletes your account and all data on our servers — your profile, synced " +
          "programs and workout history, and usage analytics. It happens immediately and cannot be " +
          "undone; there is no grace period and nothing is recoverable afterwards.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(Modifier.height(20.dp))

      Text("Type ${vm.confirmPhrase} to confirm", style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground)
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = typed, onValueChange = { typed = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("delete_confirm_phrase"),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = error)
      )

      Spacer(Modifier.height(16.dp))
      Text("Re-enter your password", style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onBackground)
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = password, onValueChange = { password = it }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("delete_password"),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
      )

      Spacer(Modifier.height(16.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = wipeLocal, onCheckedChange = { wipeLocal = it })
        Spacer(Modifier.width(4.dp))
        Text("Also erase the training data stored on this device",
          style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
      }

      state.error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = error, style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.testTag("delete_error"))
      }

      Spacer(Modifier.height(24.dp))
      Button(
        onClick = { vm.deleteAccount(password, wipeLocal) },
        enabled = confirmed && password.isNotBlank() && !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("delete_submit"),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = error)
      ) {
        if (busy) CircularProgressIndicator(Modifier.height(22.dp), color = MaterialTheme.colorScheme.onError, strokeWidth = 2.dp)
        else Text("Permanently delete my account", fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.height(40.dp))
    }
  }
}

package com.wildodds.gymtracker.ui.auth

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Set by MainActivity's deep-link handling when the user arrives via a password-recovery link;
 * the gate then overlays a "set a new password" dialog on whatever it's showing.
 */
object RecoveryFlow {
  val pendingPasswordReset = MutableStateFlow(false)
}

/**
 * Root gate: no session ⇒ onboarding/auth; session but unfinished setup ⇒ consent/username;
 * otherwise the main app. The main graph is unreachable without a session — this composable is
 * the only place [mainContent] is invoked.
 */
@Composable
fun AuthGate(
  actions: AuthActions = viewModel<AuthViewModel>(),
  mainContent: @Composable () -> Unit
) {
  val status by actions.sessionStatus.collectAsState()
  val onboardingComplete by actions.onboardingComplete.collectAsState()

  // Crossfade between gate destinations so intro → app (and sign-out transitions) never hard-cut.
  androidx.compose.animation.AnimatedContent(
    targetState = gateDestination(status, onboardingComplete),
    transitionSpec = {
      androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)) togetherWith
        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
    },
    label = "authGate"
  ) { destination ->
    when (destination) {
      GateDestination.LOADING -> GateLoadingScreen()
      GateDestination.ONBOARDING -> OnboardingFlow(actions)
      GateDestination.MAIN -> mainContent()
    }
  }

  val pendingReset by RecoveryFlow.pendingPasswordReset.collectAsState()
  if (pendingReset) {
    SetNewPasswordDialog(actions, onDone = { RecoveryFlow.pendingPasswordReset.value = false })
  }
}

@Composable
private fun GateLoadingScreen() {
  Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("gate_loading"),
    contentAlignment = Alignment.Center
  ) { CircularProgressIndicator(color = LocalAccentColor.current) }
}

@Composable
private fun SetNewPasswordDialog(actions: AuthActions, onDone: () -> Unit) {
  val accent = LocalAccentColor.current
  val form by actions.form.collectAsState()
  var password by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDone,
    title = { Text("Set a new password") },
    text = {
      Column {
        Text("You arrived from a password-reset link. Choose a new password for your account.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = password, onValueChange = { password = it },
          label = { Text("New password") }, singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
        form.error?.let {
          Spacer(Modifier.height(8.dp))
          Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { actions.updatePassword(password, onDone) },
        enabled = !form.isLoading && password.length >= 8,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent)
      ) { Text("Save password") }
    },
    dismissButton = { TextButton(onClick = onDone) { Text("Not now") } }
  )
}

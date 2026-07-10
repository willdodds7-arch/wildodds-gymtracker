package com.wildodds.gymtracker.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import java.time.LocalDate

// ── Pre-auth onboarding: welcome → age gate → sign in / create account ─────────

@Composable
fun OnboardingFlow(actions: AuthActions) {
  val blocked by actions.ageGateBlocked.collectAsState()
  val passed by actions.ageGatePassed.collectAsState()
  val navController = rememberNavController()

  // A previously-recorded under-13 result is permanent: no route out of the block screen.
  if (blocked) {
    AgeBlockedScreen()
    return
  }

  NavHost(
    navController = navController,
    startDestination = if (passed) "auth" else "welcome",
    modifier = Modifier.fillMaxSize()
  ) {
    composable("welcome") { WelcomeScreen(onContinue = { navController.navigate("age_gate") }) }
    composable("age_gate") {
      AgeGateScreen(
        onResult = { oldEnough ->
          actions.recordAgeGateResult(oldEnough)
          if (oldEnough) navController.navigate("auth") { popUpTo("welcome") { inclusive = true } }
          // Under-13: the persisted flag flips `blocked` above and swaps in the block screen.
        }
      )
    }
    composable("auth") { AuthScreen(actions) }
  }
}

@Composable
private fun OnboardingScaffold(content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .statusBarsPadding()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) { content() }
}

@Composable
private fun WelcomeScreen(onContinue: () -> Unit) {
  val accent = LocalAccentColor.current
  OnboardingScaffold {
    Spacer(Modifier.height(120.dp))
    Text("WILD ODDS", fontWeight = FontWeight.Bold, fontSize = 34.sp,
      color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
    Text("GYM TRACKER", fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
      color = accent, textAlign = TextAlign.Center)
    Spacer(Modifier.height(24.dp))
    Text(
      "Plan programs, log every set, and keep your training in sync — your account backs it all up.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(48.dp))
    Button(
      onClick = onContinue,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("welcome_continue"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Get started", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    Spacer(Modifier.height(40.dp))
  }
}

// ── Age gate ────────────────────────────────────────────────────────────────────

@Composable
private fun AgeGateScreen(onResult: (Boolean) -> Unit) {
  val accent = LocalAccentColor.current
  var day by remember { mutableStateOf("") }
  var month by remember { mutableStateOf("") }
  var year by remember { mutableStateOf("") }
  var invalidDate by remember { mutableStateOf(false) }

  OnboardingScaffold {
    Spacer(Modifier.height(96.dp))
    Text("When were you born?", fontWeight = FontWeight.Bold, fontSize = 24.sp,
      color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(12.dp))
    Text(
      "You must be at least ${AgeGate.MIN_AGE_YEARS} to use this app. We only keep the yes/no answer — never your birth date.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(32.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlinedTextField(
        value = day, onValueChange = { if (it.length <= 2) day = it.filter(Char::isDigit) },
        label = { Text("Day") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f).testTag("age_day"),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
      )
      OutlinedTextField(
        value = month, onValueChange = { if (it.length <= 2) month = it.filter(Char::isDigit) },
        label = { Text("Month") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f).testTag("age_month"),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
      )
      OutlinedTextField(
        value = year, onValueChange = { if (it.length <= 4) year = it.filter(Char::isDigit) },
        label = { Text("Year") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1.4f).testTag("age_year"),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
      )
    }
    if (invalidDate) {
      Spacer(Modifier.height(8.dp))
      Text("That isn't a valid date.", color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelMedium)
    }
    Spacer(Modifier.height(28.dp))
    Button(
      onClick = {
        val birth = AgeGate.parseBirthDate(
          year.toIntOrNull() ?: 0, month.toIntOrNull() ?: 0, day.toIntOrNull() ?: 0
        )
        if (birth == null || birth.isAfter(LocalDate.now())) { invalidDate = true }
        else { invalidDate = false; onResult(AgeGate.isOldEnough(birth, LocalDate.now())) }
      },
      enabled = day.isNotBlank() && month.isNotBlank() && year.length == 4,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("age_continue"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Continue", fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(40.dp))
  }
}

/** Hard block — deliberately no button, no navigation, no retry. */
@Composable
private fun AgeBlockedScreen() {
  Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("age_blocked"),
    contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
      Text("Sorry — you can't use this app yet", fontWeight = FontWeight.Bold, fontSize = 20.sp,
        color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
      Spacer(Modifier.height(12.dp))
      Text(
        "Wild Odds Gym Tracker requires you to be at least ${AgeGate.MIN_AGE_YEARS} years old, because using it means creating an account and storing your training data online. No account was created and nothing about you was collected or stored.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
      )
    }
  }
}

// ── Sign in / create account ───────────────────────────────────────────────────

@Composable
private fun AuthScreen(actions: AuthActions) {
  val accent = LocalAccentColor.current
  val form by actions.form.collectAsState()
  var isSignUp by remember { mutableStateOf(false) }
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var showReset by remember { mutableStateOf(false) }

  OnboardingScaffold {
    Spacer(Modifier.height(80.dp))
    Text(
      if (isSignUp) "Create your account" else "Welcome back",
      fontWeight = FontWeight.Bold, fontSize = 24.sp,
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.testTag("auth_title")
    )
    Spacer(Modifier.height(6.dp))
    Text(
      if (isSignUp) "Your programs and logs will be backed up and synced to your account."
      else "Sign in to get back to your training.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))

    OutlinedTextField(
      value = email, onValueChange = { email = it.trim() },
      label = { Text("Email") }, singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      modifier = Modifier.fillMaxWidth().testTag("auth_email"),
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
      value = password, onValueChange = { password = it },
      label = { Text("Password") }, singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      modifier = Modifier.fillMaxWidth().testTag("auth_password"),
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
    )

    form.error?.let {
      Spacer(Modifier.height(10.dp))
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center, modifier = Modifier.testTag("auth_error"))
    }
    if (form.resetEmailSent) {
      Spacer(Modifier.height(10.dp))
      Text("Password-reset email sent — check your inbox.", color = accent,
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }

    Spacer(Modifier.height(20.dp))
    Button(
      onClick = { if (isSignUp) actions.signUp(email, password) else actions.signIn(email, password) },
      enabled = !form.isLoading && email.isNotBlank() && password.length >= 6,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("auth_submit"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) {
      if (form.isLoading) CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp)
      else Text(if (isSignUp) "Create account" else "Sign in", fontWeight = FontWeight.Bold)
    }

    GoogleSignInButton(onIdToken = { actions.signInWithGoogleIdToken(it) })

    Spacer(Modifier.height(14.dp))
    TextButton(onClick = { isSignUp = !isSignUp; actions.clearError() }, modifier = Modifier.testTag("auth_mode_toggle")) {
      Text(
        if (isSignUp) "Already have an account?  Sign in" else "New here?  Create an account",
        color = accent
      )
    }
    if (!isSignUp) {
      TextButton(onClick = { showReset = true }) {
        Text("Forgot password?", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Spacer(Modifier.height(40.dp))
  }

  if (showReset) {
    PasswordResetDialog(
      initialEmail = email,
      isLoading = form.isLoading,
      onSend = { actions.requestPasswordReset(it); showReset = false },
      onDismiss = { showReset = false }
    )
  }
}

@Composable
private fun PasswordResetDialog(
  initialEmail: String,
  isLoading: Boolean,
  onSend: (String) -> Unit,
  onDismiss: () -> Unit
) {
  var email by remember { mutableStateOf(initialEmail) }
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Reset password") },
    text = {
      Column {
        Text("We'll email you a link that opens the app and lets you set a new password.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = email, onValueChange = { email = it.trim() },
          label = { Text("Email") }, singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSend(email) }, enabled = !isLoading && email.isNotBlank()) { Text("Send link") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

// ── Post-auth setup: analytics consent → username ─────────────────────────────

@Composable
fun PostAuthSetupFlow(actions: AuthActions) {
  val navController = rememberNavController()
  NavHost(navController, startDestination = "consent", modifier = Modifier.fillMaxSize()) {
    composable("consent") {
      ConsentScreen(
        onChoice = { granted ->
          actions.setAnalyticsConsent(granted)
          navController.navigate("username") { popUpTo("consent") { inclusive = true } }
        }
      )
    }
    composable("username") { UsernameScreen(actions) }
  }
}

/**
 * Genuine opt-in (Rule 4): Accept and Decline are visually identical buttons, the copy is
 * honest about what is and isn't collected, and declining changes nothing about the app.
 */
@Composable
private fun ConsentScreen(onChoice: (Boolean) -> Unit) {
  val accent = LocalAccentColor.current
  OnboardingScaffold {
    Spacer(Modifier.height(96.dp))
    Text("Share usage statistics?", fontWeight = FontWeight.Bold, fontSize = 24.sp,
      color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.testTag("consent_title"))
    Spacer(Modifier.height(14.dp))
    Text(
      "Optional. If you accept, the app records which screens and features get used — never " +
        "your workout numbers, notes, name or location — to guide what gets built next. " +
        "You can change this anytime in Settings, and the app works exactly the same either way.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(36.dp))
    // Equal prominence: same component, same size, same shape.
    OutlinedButton(
      onClick = { onChoice(true) },
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("consent_accept"),
      shape = RoundedCornerShape(12.dp)
    ) { Text("Accept", fontWeight = FontWeight.Bold, color = accent) }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
      onClick = { onChoice(false) },
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("consent_decline"),
      shape = RoundedCornerShape(12.dp)
    ) { Text("Decline", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) }
    Spacer(Modifier.height(40.dp))
  }
}

@Composable
private fun UsernameScreen(actions: AuthActions) {
  val accent = LocalAccentColor.current
  var username by remember { mutableStateOf("") }
  OnboardingScaffold {
    Spacer(Modifier.height(96.dp))
    Text("Pick a username", fontWeight = FontWeight.Bold, fontSize = 24.sp,
      color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(12.dp))
    Text("Shown on your profile. You can change it later — or skip it for now.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(28.dp))
    OutlinedTextField(
      value = username, onValueChange = { username = it },
      label = { Text("Username") }, singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("username_field"),
      colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent)
    )
    Spacer(Modifier.height(24.dp))
    Button(
      onClick = { actions.finishOnboarding(username) },
      enabled = username.isNotBlank(),
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("username_save"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Save & start training", fontWeight = FontWeight.Bold) }
    TextButton(onClick = { actions.finishOnboarding(null) }, modifier = Modifier.testTag("username_skip")) {
      Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(40.dp))
  }
}

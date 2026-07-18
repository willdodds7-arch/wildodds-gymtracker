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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wildodds.gymtracker.ui.theme.LocalAccentColor
import java.time.LocalDate

// ── Thin intro: Welcome (3 ways) → Account (optional) → Tour ───────────────────
//
// Accounts are OPTIONAL. The no-account path is just three taps: welcome → "Not now" → tour →
// into the app. The 13+ age gate and analytics consent live ONLY on the "create account" branch,
// because that's the only branch that collects data or leaves the device.

@Composable
fun OnboardingFlow(actions: AuthActions) {
  val navController = rememberNavController()

  NavHost(
    navController = navController,
    startDestination = "welcome",
    modifier = Modifier.fillMaxSize()
  ) {
    composable("welcome") { WelcomeScreen(onContinue = { navController.navigate("account") }) }

    composable("account") {
      AccountScreen(
        onCreateOrSignIn = { navController.navigate("age_gate") },
        onSkip = { navController.navigate("tour") }
      )
    }

    // ── "Create account" branch: age gate → auth → consent → tour ──
    composable("age_gate") {
      AgeGateScreen(
        onResult = { oldEnough ->
          actions.recordAgeGateResult(oldEnough)
          navController.navigate(if (oldEnough) "auth" else "age_blocked_signup")
        }
      )
    }
    composable("age_blocked_signup") {
      AgeBlockedSignupScreen(onContinueWithout = {
        navController.navigate("tour") { popUpTo("account") }
      })
    }
    composable("auth") {
      // When sign-up/sign-in establishes a session, advance to consent (still inside the intro).
      val status by actions.sessionStatus.collectAsState()
      LaunchedEffect(status) {
        if (status is io.github.jan.supabase.gotrue.SessionStatus.Authenticated) {
          navController.navigate("consent") { popUpTo("account") }
        }
      }
      AuthScreen(actions, navController)
    }
    composable("consent") {
      ConsentScreen(
        navController = navController,
        onChoice = { granted ->
          actions.setAnalyticsConsent(granted)
          actions.beginFirstBackup(null) // upload any existing local data to the new account
          navController.navigate("tour") { popUpTo("account") }
        }
      )
    }

    composable("tour") { TourScreen(onFinish = { actions.finishOnboarding(null) }) }
    legalRoute(navController)
  }
}

/** Registers an offline legal-doc viewer inside an onboarding NavHost, so the "I agree" links and
 *  consent-screen links open the BUNDLED docs without leaving onboarding or needing a connection. */
private fun androidx.navigation.NavGraphBuilder.legalRoute(navController: NavHostController) {
  composable(
    route = "legal/{docKey}",
    arguments = listOf(androidx.navigation.navArgument("docKey") { type = androidx.navigation.NavType.StringType })
  ) { entry ->
    val doc = com.wildodds.gymtracker.ui.legal.LegalDoc.byKey(entry.arguments?.getString("docKey"))
    if (doc != null) com.wildodds.gymtracker.ui.legal.LegalDocScreen(navController, doc)
    else navController.popBackStack()
  }
}

/** "I agree to the Privacy Policy and Terms of Service", with the two doc names tappable. */
@Composable
private fun LegalAgreementText(navController: NavHostController) {
  val accent = LocalAccentColor.current
  val body = MaterialTheme.colorScheme.onSurfaceVariant
  val annotated = androidx.compose.ui.text.buildAnnotatedString {
    withStyle(androidx.compose.ui.text.SpanStyle(color = body)) { append("I agree to the ") }
    pushStringAnnotation("nav", "legal/privacy")
    withStyle(androidx.compose.ui.text.SpanStyle(color = accent)) { append("Privacy Policy") }
    pop()
    withStyle(androidx.compose.ui.text.SpanStyle(color = body)) { append(" and ") }
    pushStringAnnotation("nav", "legal/terms")
    withStyle(androidx.compose.ui.text.SpanStyle(color = accent)) { append("Terms of Service") }
    pop()
  }
  androidx.compose.foundation.text.ClickableText(
    text = annotated,
    style = MaterialTheme.typography.bodySmall,
    modifier = Modifier.testTag("auth_agree_text"),
    onClick = { offset ->
      annotated.getStringAnnotations("nav", offset, offset).firstOrNull()?.let { navController.navigate(it.item) }
    }
  )
}

/** Standalone "Privacy Policy · Terms of Service" links for the consent screen. */
@Composable
private fun LegalAgreementLinks(navController: NavHostController) {
  val accent = LocalAccentColor.current
  val annotated = androidx.compose.ui.text.buildAnnotatedString {
    pushStringAnnotation("nav", "legal/privacy")
    withStyle(androidx.compose.ui.text.SpanStyle(color = accent)) { append("Privacy Policy") }
    pop()
    withStyle(
      androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    ) { append("   ·   ") }
    pushStringAnnotation("nav", "legal/terms")
    withStyle(androidx.compose.ui.text.SpanStyle(color = accent)) { append("Terms of Service") }
    pop()
  }
  androidx.compose.foundation.text.ClickableText(
    text = annotated,
    style = MaterialTheme.typography.bodySmall,
    onClick = { offset ->
      annotated.getStringAnnotations("nav", offset, offset).firstOrNull()?.let { navController.navigate(it.item) }
    }
  )
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
    Spacer(Modifier.height(72.dp))
    Text("WILD ODDS", fontWeight = FontWeight.Bold, fontSize = 34.sp,
      color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
    Text("GYM TRACKER", fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
      color = accent, textAlign = TextAlign.Center)
    Spacer(Modifier.height(20.dp))
    Text(
      "Three ways to get started — pick whichever suits you:",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    WayCard("Choose a program", "Start from a ready-made training plan.")
    Spacer(Modifier.height(12.dp))
    WayCard("Log as you go", "Just start training — build your plan set by set.")
    Spacer(Modifier.height(12.dp))
    WayCard("Upload a program", "Bring your own from a spreadsheet or a photo.")
    Spacer(Modifier.height(32.dp))
    Button(
      onClick = onContinue,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("welcome_continue"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Get started", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    Spacer(Modifier.height(40.dp))
  }
}

/** One of the three "ways to start" — a calm labelled card (informational, not a choice to make). */
@Composable
private fun WayCard(title: String, subtitle: String) {
  androidx.compose.material3.Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
  ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
      Text(subtitle, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/**
 * Optional account. "Not now" is a first-class choice — the app is fully usable without an
 * account (everything stays on this device). Signing in adds cross-device sync (and, later,
 * friends). Both buttons are prominent; neither is a dark pattern.
 */
@Composable
private fun AccountScreen(onCreateOrSignIn: () -> Unit, onSkip: () -> Unit) {
  val accent = LocalAccentColor.current
  OnboardingScaffold {
    Spacer(Modifier.height(88.dp))
    Text("Create an account?", fontWeight = FontWeight.Bold, fontSize = 24.sp,
      color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.testTag("account_title"))
    Spacer(Modifier.height(14.dp))
    Text(
      "It's optional — the app works fully without one, and your training stays on this device.\n\n" +
        "With an account you can sync your programs and history across devices, and connect with " +
        "friends to share programs and compare achievements (friends coming soon).",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(32.dp))
    Button(
      onClick = onCreateOrSignIn,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account_create"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Create account or sign in", fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
      onClick = onSkip,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account_skip"),
      shape = RoundedCornerShape(12.dp)
    ) { Text("Not now", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) }
    Spacer(Modifier.height(8.dp))
    Text("You can create one anytime from Settings.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(40.dp))
  }
}

/** Under-13 result on the account branch: they can't create an account, but can still use the
 *  app locally (no account = no data collection), so this is NOT an app-wide block. */
@Composable
private fun AgeBlockedSignupScreen(onContinueWithout: () -> Unit) {
  val accent = LocalAccentColor.current
  OnboardingScaffold {
    Spacer(Modifier.height(120.dp))
    Text("You can still use the app", fontWeight = FontWeight.Bold, fontSize = 22.sp,
      color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center,
      modifier = Modifier.testTag("age_blocked_signup"))
    Spacer(Modifier.height(12.dp))
    Text(
      "You need to be at least ${AgeGate.MIN_AGE_YEARS} to create an account, so we won't set one " +
        "up. You can keep using everything on this device with no account — nothing about you is " +
        "collected or stored online.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(32.dp))
    Button(
      onClick = onContinueWithout,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("age_blocked_continue"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Continue without an account", fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(40.dp))
  }
}

/**
 * Last intro screen: offer a quick tour or go straight in. Both paths call [onFinish], which marks
 * onboarding complete → the gate shows the app. The "tour" is a tiny 3-card highlight reel.
 */
@Composable
private fun TourScreen(onFinish: () -> Unit) {
  val accent = LocalAccentColor.current
  var touring by remember { mutableStateOf(false) }

  if (touring) { TourCards(onDone = onFinish); return }

  OnboardingScaffold {
    Spacer(Modifier.height(120.dp))
    Text("You're all set", fontWeight = FontWeight.Bold, fontSize = 24.sp,
      color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.testTag("tour_title"))
    Spacer(Modifier.height(12.dp))
    Text("Want a quick tour, or jump straight in?",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(32.dp))
    Button(
      onClick = { touring = true },
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("tour_take"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text("Take a quick tour", fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
      onClick = onFinish,
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("tour_skip"),
      shape = RoundedCornerShape(12.dp)
    ) { Text("Skip — take me to the app", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) }
    Spacer(Modifier.height(40.dp))
  }
}

private data class Tip(val title: String, val body: String)

@Composable
private fun TourCards(onDone: () -> Unit) {
  val accent = LocalAccentColor.current
  val tips = remember {
    listOf(
      Tip("Home", "Your active program lives here — tap a day to start logging."),
      Tip("Log fast", "Last week's numbers are pre-filled; just adjust and go. Works offline."),
      Tip("Library & Profile", "Browse programs in the Library; find your maxes, history and settings under Profile."),
    )
  }
  var i by remember { mutableStateOf(0) }
  val last = i == tips.lastIndex

  OnboardingScaffold {
    Spacer(Modifier.height(120.dp))
    Text(tips[i].title, fontWeight = FontWeight.Bold, fontSize = 24.sp,
      color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.testTag("tour_card"))
    Spacer(Modifier.height(12.dp))
    Text(tips[i].body, style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(28.dp))
    Text("${i + 1} / ${tips.size}", style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Button(
      onClick = { if (last) onDone() else i++ },
      modifier = Modifier.fillMaxWidth().height(52.dp).testTag("tour_next"),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(containerColor = accent)
    ) { Text(if (last) "Start training" else "Next", fontWeight = FontWeight.Bold) }
    TextButton(onClick = onDone) { Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

// ── Sign in / create account ───────────────────────────────────────────────────

@Composable
private fun AuthScreen(actions: AuthActions, navController: NavHostController) {
  val accent = LocalAccentColor.current
  val form by actions.form.collectAsState()
  var isSignUp by remember { mutableStateOf(false) }
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var showReset by remember { mutableStateOf(false) }
  var agreed by remember { mutableStateOf(false) }

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

    // Sign-up requires explicit agreement to the Privacy Policy + Terms (with tappable links).
    if (isSignUp) {
      Spacer(Modifier.height(14.dp))
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.material3.Checkbox(
          checked = agreed, onCheckedChange = { agreed = it },
          modifier = Modifier.testTag("auth_agree_checkbox")
        )
        Spacer(Modifier.width(4.dp))
        LegalAgreementText(navController)
      }
    }

    Spacer(Modifier.height(20.dp))
    Button(
      onClick = { if (isSignUp) actions.signUp(email, password) else actions.signIn(email, password) },
      enabled = !form.isLoading && email.isNotBlank() && password.length >= 8 && (!isSignUp || agreed),
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

// ── Analytics consent (create-account branch only) ─────────────────────────────

/**
 * Genuine opt-in (Rule 4): Accept and Decline are visually identical buttons, the copy is
 * honest about what is and isn't collected, and declining changes nothing about the app.
 */
@Composable
private fun ConsentScreen(navController: NavHostController, onChoice: (Boolean) -> Unit) {
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
    Spacer(Modifier.height(12.dp))
    // Links to the same bundled docs (offline) referenced at sign-up.
    LegalAgreementLinks(navController)
    Spacer(Modifier.height(24.dp))
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

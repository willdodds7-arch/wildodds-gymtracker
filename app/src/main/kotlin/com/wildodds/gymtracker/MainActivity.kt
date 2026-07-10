package com.wildodds.gymtracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.wildodds.gymtracker.data.backend.SupabaseModule
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.ui.auth.AuthGate
import com.wildodds.gymtracker.ui.auth.RecoveryFlow
import com.wildodds.gymtracker.ui.navigation.AppNavigation
import com.wildodds.gymtracker.ui.theme.AppTheme
import com.wildodds.gymtracker.ui.theme.accentPalette
import io.github.jan.supabase.gotrue.handleDeeplinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private lateinit var themePrefs: ThemePreferences

  override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  enableEdgeToEdge()
  themePrefs = ThemePreferences(applicationContext)
  consumeAuthDeeplink(intent)

  setContent {
  val isDarkMode  by themePrefs.isDarkMode.collectAsStateWithLifecycle(initialValue = false)
  val accentHue  by themePrefs.accentHue.collectAsStateWithLifecycle(initialValue = 203f)
  val accentSat  by themePrefs.accentSaturation.collectAsStateWithLifecycle(initialValue = 0.72f)

  val (primary, light, dark) = accentPalette(accentHue, accentSat)

  AppTheme(
  isDarkMode  = isDarkMode,
  accentColor  = primary,
  accentLight  = light,
  accentDark  = dark,
  onToggleDarkMode = {
  CoroutineScope(Dispatchers.IO).launch {
  themePrefs.setDarkMode(!isDarkMode)
  }
  }
  ) {
  // Online-first v2: the main graph only renders behind a signed-in session.
  AuthGate {
  AppNavigation()
  }
  }
  }
  }

  override fun onNewIntent(intent: Intent) {
  super.onNewIntent(intent)
  consumeAuthDeeplink(intent)
  }

  /**
   * Password-reset (and email-confirmation) links open the app via wildodds://auth;
   * supabase-kt parses the tokens out of the intent and establishes the session. For recovery
   * links we additionally flag [RecoveryFlow] so AuthGate prompts for a new password.
   */
  private fun consumeAuthDeeplink(intent: Intent?) {
  if (intent?.data?.scheme != SupabaseModule.DEEPLINK_SCHEME) return
  SupabaseModule.client.handleDeeplinks(intent) { session ->
  // Confirmation links also land here; only recovery links should prompt for a new password.
  if (session.type == "recovery") RecoveryFlow.pendingPasswordReset.value = true
  }
  }
}

package com.wildodds.gymtracker.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.ui.theme.AppTheme
import com.wildodds.gymtracker.ui.theme.accentPalette

/**
 * The privacy / rationale screen Health Connect opens when the user asks why the app wants their
 * health data (the manifest registers it for both the legacy rationale action and the Android-14+
 * permission-usage intent). It is a read-only explainer — it does not request anything.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val themePrefs = ThemePreferences(applicationContext)

    setContent {
      val isDarkMode by themePrefs.isDarkMode.collectAsState(initial = false)
      val accentHue by themePrefs.accentHue.collectAsState(initial = 203f)
      val accentSat by themePrefs.accentSaturation.collectAsState(initial = 0.72f)
      val (primary, light, dark) = accentPalette(accentHue, accentSat)

      AppTheme(
        isDarkMode = isDarkMode,
        accentColor = primary,
        accentLight = light,
        accentDark = dark,
        onToggleDarkMode = {}
      ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          RationaleContent(onClose = { finish() })
        }
      }
    }
  }
}

@Composable
private fun RationaleContent(onClose: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .statusBarsPadding()
      .padding(24.dp)
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Text(
      "How Wild Odds uses health data",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground
    )
    Text(
      "If you connect a wearable through Health Connect, Wild Odds Gym Tracker reads:",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Bullet("Heart rate — to show your average and peak heart rate on a workout's summary and to refine its fatigue score.")
    Bullet("Sleep, resting heart rate and heart-rate variability — used by recovery and readiness features.")
    Text(
      "Your privacy",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onBackground
    )
    Text(
      "This data is read on demand for the window of your workout (or the night before) and is used " +
        "entirely on your device. The app has no account and no server: nothing is uploaded, shared, or " +
        "sold. Only the heart-rate summary of a session you complete is saved, alongside that session. " +
        "You can revoke access at any time in the Health Connect app, and turn the feature off in Settings.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) { Text("Got it") }
  }
}

@Composable
private fun Bullet(text: String) {
  Text(
    "•  $text",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant
  )
}

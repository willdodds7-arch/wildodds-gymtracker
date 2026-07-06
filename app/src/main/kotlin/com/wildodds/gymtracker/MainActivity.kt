package com.wildodds.gymtracker

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
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.ui.navigation.AppNavigation
import com.wildodds.gymtracker.ui.theme.AppTheme
import com.wildodds.gymtracker.ui.theme.accentPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private lateinit var themePrefs: ThemePreferences

  override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  enableEdgeToEdge()
  themePrefs = ThemePreferences(applicationContext)

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
  AppNavigation()
  }
  }
  }
}

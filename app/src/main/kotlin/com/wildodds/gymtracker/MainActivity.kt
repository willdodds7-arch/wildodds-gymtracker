package com.wildodds.gymtracker

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.ui.navigation.AppNavigation
import com.wildodds.gymtracker.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private lateinit var themePrefs: ThemePreferences

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    requestHighestRefreshRate()
    themePrefs = ThemePreferences(applicationContext)

    setContent {
      // Only dark mode is read here. The redesign is strictly monochrome, so AppTheme derives its
      // accent from the mode and ignores any stored hue/saturation — collecting those two extra
      // DataStore flows at the ROOT of the tree only bought two more full-app recompositions
      // during cold start, for values nothing reads.
      val isDarkMode by themePrefs.isDarkMode.collectAsStateWithLifecycle(initialValue = false)

      AppTheme(
        isDarkMode = isDarkMode,
        onToggleDarkMode = {
          lifecycleScope.launch(Dispatchers.IO) {
            themePrefs.setDarkMode(!isDarkMode)
          }
        }
      ) {
        // Local-only: the app opens straight to the main navigation (Home). There is no
        // sign-in/account gate — the whole app runs on the on-device database.
        AppNavigation()
      }
    }
  }

  // Some OEMs reset the preferred display mode when the window regains focus, so re-apply it.
  override fun onResume() {
    super.onResume()
    requestHighestRefreshRate()
  }

  /**
   * Opt the window into the display's highest refresh rate (e.g. 120 Hz) at the current resolution.
   * Without this, many devices throttle apps to 60 Hz, so scrolling and animations look choppier
   * than the panel is capable of. Picks the fastest mode that keeps the current physical resolution;
   * falls back to `preferredRefreshRate` where a mode switch isn't available. Best-effort and safe:
   * any failure just leaves the system default in place.
   */
  private fun requestHighestRefreshRate() {
    try {
      val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
                    else @Suppress("DEPRECATION") windowManager.defaultDisplay
      val current = display?.mode ?: return
      val modes = display.supportedModes
      val sameRes = modes.filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
      }
      val best = (sameRes.ifEmpty { modes.toList() }).maxByOrNull { it.refreshRate } ?: return
      val lp = window.attributes
      if (best.modeId != current.modeId && best.refreshRate > current.refreshRate + 1f) {
        lp.preferredDisplayModeId = best.modeId
      }
      lp.preferredRefreshRate = best.refreshRate
      window.attributes = lp
      Log.d("RefreshRate", "requested ${best.refreshRate} Hz (was ${current.refreshRate} Hz)")
    } catch (e: Exception) {
      Log.w("RefreshRate", "could not raise refresh rate: ${e.message}")
    }
  }
}

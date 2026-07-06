package com.wildodds.gymtracker.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildodds.gymtracker.data.datastore.ThemePreferences

/**
 * App-wide feature gate. Any composable can hide an OFF feature's entry point with:
 *
 * ```
 * if (FeatureFlags.isEnabled(SOME_FEATURE_KEY)) {
 *     // ...render the entry point...
 * }
 * ```
 *
 * Backed by the DataStore [ThemePreferences.flag] Flow, so toggling the feature in Settings
 * makes its entry points appear/disappear reactively — no dead buttons for disabled features.
 * The default is taken from [SettingsRegistry], so callers only pass the key.
 */
object FeatureFlags {

  @Composable
  fun isEnabled(key: String): Boolean {
  val context = LocalContext.current
  val prefs = remember(context) { ThemePreferences(context) }
  val default = remember(key) { SettingsRegistry.defaultFor(key) }
  return prefs.flag(key, default)
  .collectAsStateWithLifecycle(initialValue = default).value
  }
}

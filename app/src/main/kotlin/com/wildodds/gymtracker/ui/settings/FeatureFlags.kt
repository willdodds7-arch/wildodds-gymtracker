package com.wildodds.gymtracker.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

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
 *
 * PERF: each flag is backed by ONE process-wide [StateFlow], cached by key and warm-started from
 * its last value. Previously `isEnabled` built a fresh DataStore Flow on every recomposition, so
 * `collectAsStateWithLifecycle` tore down and restarted the collection each time — a screen with
 * eight gated features re-subscribed eight DataStore reads on every frame it recomposed. Now a
 * recomposition just reads a cached value.
 */
object FeatureFlags {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val flows = ConcurrentHashMap<String, StateFlow<Boolean>>()

  /**
   * The shared flow for [key]. Safe to call from anywhere; the first call for a key starts the
   * single collector, every later call reuses it. Kept alive 5 s past the last subscriber so
   * navigating between screens never re-reads DataStore.
   */
  fun flow(context: Context, key: String): StateFlow<Boolean> = flows.getOrPut(key) {
    val default = SettingsRegistry.defaultFor(key)
    ThemePreferences(context.applicationContext)
      .flag(key, default)
      .distinctUntilChanged()
      .stateIn(scope, SharingStarted.WhileSubscribed(5_000), default)
  }

  @Composable
  fun isEnabled(key: String): Boolean {
    val context = LocalContext.current.applicationContext
    val flow = remember(context, key) { flow(context, key) }
    return flow.collectAsStateWithLifecycle().value
  }
}

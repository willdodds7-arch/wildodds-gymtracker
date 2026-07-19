package com.wildodds.gymtracker.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.repository.GymRepository
import com.wildodds.gymtracker.data.wearable.RealHealthConnectGateway
import com.wildodds.gymtracker.data.wearable.WearableAvailability
import com.wildodds.gymtracker.data.wearable.WearableHealthPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Health Connect connection state shown on the Settings "Connect wearable" row. */
enum class WearableConnectionStatus { UNAVAILABLE, UPDATE_REQUIRED, DISCONNECTED, CONNECTED }

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
  private val prefs = ThemePreferences(app)
  private val db  = AppDatabase.getInstance(app)
  private val repo  = GymRepository(db)
  private val gymPrefs = app.getSharedPreferences("gym_prefs", Context.MODE_PRIVATE)
  private val wearableGateway = RealHealthConnectGateway(app)

  private val _claudeApiKey = MutableStateFlow(gymPrefs.getString("claude_api_key", "") ?: "")
  val claudeApiKey: StateFlow<String> = _claudeApiKey.asStateFlow()

  fun setClaudeApiKey(key: String) {
    _claudeApiKey.value = key
    gymPrefs.edit().putString("claude_api_key", key).apply()
  }

  val isDarkMode: StateFlow<Boolean> = prefs.isDarkMode
  .stateIn(viewModelScope, SharingStarted.Eagerly, false)
  val accentHue: StateFlow<Float> = prefs.accentHue
  .stateIn(viewModelScope, SharingStarted.Eagerly, 203f)
  val accentSaturation: StateFlow<Float> = prefs.accentSaturation
  .stateIn(viewModelScope, SharingStarted.Eagerly, 0.72f)

  val featSessionTimer: StateFlow<Boolean>      = prefs.featSessionTimer.stateIn(viewModelScope, SharingStarted.Eagerly, true)
  val featWeightAutofill: StateFlow<Boolean>    = prefs.featWeightAutofill.stateIn(viewModelScope, SharingStarted.Eagerly, true)
  val featAddExercise: StateFlow<Boolean>       = prefs.featAddExercise.stateIn(viewModelScope, SharingStarted.Eagerly, true)
  val featProgressionPicker: StateFlow<Boolean> = prefs.featProgressionPicker.stateIn(viewModelScope, SharingStarted.Eagerly, true)
  val feat1rmCalculator: StateFlow<Boolean>     = prefs.feat1rmCalculator.stateIn(viewModelScope, SharingStarted.Eagerly, true)
  val timerSound: StateFlow<String>             = prefs.timerSound.stateIn(viewModelScope, SharingStarted.Eagerly, "ding")

  // ── Generic, registry-driven flag state ──────────────────────────────────────
  // One combined map { flagKey -> enabled } over every TOGGLE entry in the registry, so the
  // rebuilt Settings screen renders and toggles flags without per-flag plumbing. Shares the
  // same DataStore keys as the typed accessors above, so both stay consistent.
  val toggleStates: StateFlow<Map<String, Boolean>> =
  combine(
  SettingsRegistry.toggleEntries.map { entry ->
  prefs.flag(entry.key, entry.default).map { entry.key to it }
  }
  ) { pairs -> pairs.toMap() }
  .stateIn(
  viewModelScope, SharingStarted.Eagerly,
  SettingsRegistry.toggleEntries.associate { it.key to it.default }
  )

  /** Toggle/set any registry flag by key. */
  fun setFlag(key: String, value: Boolean) {
  viewModelScope.launch { prefs.setFlag(key, value) }
  com.wildodds.gymtracker.data.analytics.AnalyticsGate.log(
  com.wildodds.gymtracker.data.analytics.AnalyticsEvent.FeatureToggled(key, value))
  }

  // ── Account (Phase 2, online-first) ──────────────────────────────────────────
  // Lazy + guarded: constructing AuthRepository builds the Supabase client, whose Auth plugin
  // needs device session storage that JVM/Robolectric tests don't have. Guarding keeps every
  // Settings test (and any exotic runtime failure) rendering the screen instead of crashing.
  private val authRepo by lazy { com.wildodds.gymtracker.data.backend.AuthRepository() }

  // Observable so the Account rows update the moment sign-in/out completes (a plain getter only
  // refreshed on recomposition). Guarded: constructing the Supabase client can fail on JVM tests.
  private val _signedInEmail = MutableStateFlow<String?>(null)
  val signedInEmail: StateFlow<String?> = _signedInEmail.asStateFlow()

  fun refreshAccountState() {
  viewModelScope.launch(Dispatchers.IO) {
  _signedInEmail.value = runCatching { authRepo.currentUserEmail }.getOrNull()
  }
  }

  init { refreshAccountState() }

  /** "unset" | "granted" | "denied" — the Settings toggle shows granted as ON, anything else OFF. */
  val analyticsConsent: StateFlow<String> = prefs.analyticsConsent
  .stateIn(viewModelScope, SharingStarted.Eagerly, "unset")

  fun setAnalyticsConsent(granted: Boolean) {
  viewModelScope.launch(Dispatchers.IO) {
  val value = if (granted) "granted" else "denied"
  prefs.setAnalyticsConsent(value)
  runCatching { authRepo.setAnalyticsConsentOnProfile(value) } // audit mirror, best-effort
  if (!granted) com.wildodds.gymtracker.data.analytics.AnalyticsGate.onConsentRevoked(getApplication())
  }
  }

  // ── Sync (Phase 3) ───────────────────────────────────────────────────────────
  val syncState: StateFlow<com.wildodds.gymtracker.data.sync.SyncState> =
  com.wildodds.gymtracker.data.sync.SyncStatus.state

  init {
  // Seed the "Last synced …" label from the persisted cursor (SyncStatus resets per process).
  val persisted = com.wildodds.gymtracker.data.sync.PrefsSyncCursorStore(app).lastSyncAt
  if (persisted > 0L) {
  com.wildodds.gymtracker.data.sync.SyncStatus.update { it.copy(lastSyncAt = maxOf(it.lastSyncAt, persisted)) }
  }
  }

  fun syncNow() {
  runCatching { com.wildodds.gymtracker.data.sync.SyncScheduler.syncNow(getApplication()) }
  }

  /** Signs out on this device only. Local Room data is untouched and — accounts being optional —
   *  the user stays right where they are in the app; no re-running the intro. */
  fun signOut() {
  viewModelScope.launch(Dispatchers.IO) {
  runCatching { authRepo.signOut() }
  _signedInEmail.value = null
  }
  }

  fun toggleDarkMode() {
  viewModelScope.launch { prefs.setDarkMode(!isDarkMode.value) }
  }
  fun toggleFeatSessionTimer()      { viewModelScope.launch { prefs.setFeatSessionTimer(!featSessionTimer.value) } }
  fun toggleFeatWeightAutofill()    { viewModelScope.launch { prefs.setFeatWeightAutofill(!featWeightAutofill.value) } }
  fun toggleFeatAddExercise()       { viewModelScope.launch { prefs.setFeatAddExercise(!featAddExercise.value) } }
  fun toggleFeatProgressionPicker() { viewModelScope.launch { prefs.setFeatProgressionPicker(!featProgressionPicker.value) } }
  fun toggleFeat1rmCalculator()     { viewModelScope.launch { prefs.setFeat1rmCalculator(!feat1rmCalculator.value) } }
  fun setTimerSound(value: String)  { viewModelScope.launch { prefs.setTimerSound(value) } }

  fun setAccentHue(hue: Float) {
  viewModelScope.launch { prefs.setAccentHue(hue) }
  }

  fun setAccentSaturation(sat: Float) {
  viewModelScope.launch { prefs.setAccentSaturation(sat) }
  }

  fun resetAccentColor() {
  viewModelScope.launch { prefs.resetAccent() }
  }

  fun clearAllData(onDone: () -> Unit) {
  viewModelScope.launch {
  repo.clearAllData()
  onDone()
  }
  }

  // ── Wearable (Health Connect) connection status ──────────────────────────────
  // Drives the "Connect wearable" Settings row. Recomputed off-main-thread on demand: at startup,
  // after the permission flow returns, and whenever Settings resumes (the user may have granted or
  // revoked access in the Health Connect app). Never throws — a bad read just reports UNAVAILABLE.
  private val _wearableStatus = MutableStateFlow(WearableConnectionStatus.UNAVAILABLE)
  val wearableStatus: StateFlow<WearableConnectionStatus> = _wearableStatus.asStateFlow()

  /** The read permissions the connect button requests in one grant flow. */
  val wearablePermissions: Set<String> get() = WearableHealthPermissions.ALL

  init { refreshWearableStatus() }

  fun refreshWearableStatus() {
  viewModelScope.launch {
  _wearableStatus.value = withContext(Dispatchers.IO) {
  when (wearableGateway.availability()) {
  WearableAvailability.NOT_AVAILABLE  -> WearableConnectionStatus.UNAVAILABLE
  WearableAvailability.UPDATE_REQUIRED -> WearableConnectionStatus.UPDATE_REQUIRED
  WearableAvailability.AVAILABLE -> {
  val granted = wearableGateway.grantedHealthPermissions()
  if (WearableHealthPermissions.READ_HEART_RATE in granted)
  WearableConnectionStatus.CONNECTED
  else
  WearableConnectionStatus.DISCONNECTED
  }
  }
  }
  }
  }
}

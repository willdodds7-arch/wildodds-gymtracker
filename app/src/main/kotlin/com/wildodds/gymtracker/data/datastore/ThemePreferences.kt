package com.wildodds.gymtracker.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Default accent hue: ~203° (matches the original sky-blue #60B4E8) */
const val DEFAULT_ACCENT_HUE = 203f
const val DEFAULT_ACCENT_SAT = 0.72f

class ThemePreferences(private val context: Context) {

  // PERF: one de-duplicated view of the preferences file that every accessor below maps off.
  // DataStore replays the whole Preferences object to every collector on every write, so without
  // this each of the ~35 flows the app keeps open would wake (and re-run its `map`) whenever ANY
  // single preference changed. `distinctUntilChanged` on each derived flow confines a write to the
  // consumers that actually care about the key that moved.
  private val prefs: Flow<Preferences> = context.dataStore.data
  private val DARK_MODE_KEY              = booleanPreferencesKey("dark_mode")
  private val ACCENT_HUE_KEY             = floatPreferencesKey("accent_hue")
  private val ACCENT_SAT_KEY             = floatPreferencesKey("accent_sat")
  private val FEAT_TIMER_KEY             = booleanPreferencesKey("feat_session_timer")
  private val FEAT_WEIGHT_AUTOFILL_KEY   = booleanPreferencesKey("feat_weight_autofill")
  private val FEAT_ADD_EXERCISE_KEY      = booleanPreferencesKey("feat_add_exercise_mid_session")
  private val FEAT_PROGRESSION_KEY       = booleanPreferencesKey("feat_progression_picker")
  private val FEAT_1RM_CALC_KEY          = booleanPreferencesKey("feat_1rm_calculator")
  private val TIMER_SOUND_KEY            = stringPreferencesKey("timer_sound")
  private val ONE_RM_SQUAT_KEY           = floatPreferencesKey("one_rm_squat")
  private val ONE_RM_BENCH_KEY           = floatPreferencesKey("one_rm_bench")
  private val ONE_RM_DEADLIFT_KEY        = floatPreferencesKey("one_rm_deadlift")
  private val ONE_RM_OHP_KEY             = floatPreferencesKey("one_rm_ohp")
  // ── Onboarding / account (Phase 2, online-first) ─────────────────────────────
  private val AGE_GATE_PASSED_KEY        = booleanPreferencesKey("age_gate_passed")
  private val AGE_GATE_BLOCKED_KEY       = booleanPreferencesKey("age_gate_blocked")
  private val ONBOARDING_COMPLETE_KEY    = booleanPreferencesKey("onboarding_complete")
  // "unset" | "granted" | "denied" — pre-consent ("unset") is treated exactly like "denied"
  // by the analytics layer (events dropped entirely), per Rule 4.
  private val ANALYTICS_CONSENT_KEY      = stringPreferencesKey("analytics_consent")

  val isDarkMode: Flow<Boolean> = prefs.map { it[DARK_MODE_KEY] ?: false }.distinctUntilChanged()
  val accentHue: Flow<Float> = prefs.map { it[ACCENT_HUE_KEY] ?: DEFAULT_ACCENT_HUE }.distinctUntilChanged()
  val accentSaturation: Flow<Float> = prefs.map { it[ACCENT_SAT_KEY] ?: DEFAULT_ACCENT_SAT }.distinctUntilChanged()
  val featSessionTimer: Flow<Boolean>        = prefs.map { it[FEAT_TIMER_KEY]           ?: true }.distinctUntilChanged()
  val featWeightAutofill: Flow<Boolean>      = prefs.map { it[FEAT_WEIGHT_AUTOFILL_KEY] ?: true }.distinctUntilChanged()
  val featAddExercise: Flow<Boolean>         = prefs.map { it[FEAT_ADD_EXERCISE_KEY]    ?: true }.distinctUntilChanged()
  val featProgressionPicker: Flow<Boolean>   = prefs.map { it[FEAT_PROGRESSION_KEY]    ?: true }.distinctUntilChanged()
  val feat1rmCalculator: Flow<Boolean>       = prefs.map { it[FEAT_1RM_CALC_KEY]        ?: true }.distinctUntilChanged()
  // "none" | "ding" | "ronnie"
  val timerSound: Flow<String>               = prefs.map { it[TIMER_SOUND_KEY]          ?: "ding" }.distinctUntilChanged()
  // Onboarding / account (Phase 2). Only pass/fail booleans are stored — never the birth date
  // itself (data minimisation: the age gate runs before ANY data collection).
  val ageGatePassed: Flow<Boolean>           = prefs.map { it[AGE_GATE_PASSED_KEY]       ?: false }.distinctUntilChanged()
  val ageGateBlocked: Flow<Boolean>          = prefs.map { it[AGE_GATE_BLOCKED_KEY]      ?: false }.distinctUntilChanged()
  val onboardingComplete: Flow<Boolean>      = prefs.map { it[ONBOARDING_COMPLETE_KEY]   ?: false }.distinctUntilChanged()
  val analyticsConsent: Flow<String>         = prefs.map { it[ANALYTICS_CONSENT_KEY]     ?: "unset" }.distinctUntilChanged()
  // Manually-entered 1RMs (kg). 0f = not set yet.
  val oneRmSquat: Flow<Float>                = prefs.map { it[ONE_RM_SQUAT_KEY]          ?: 0f }.distinctUntilChanged()
  val oneRmBench: Flow<Float>                = prefs.map { it[ONE_RM_BENCH_KEY]          ?: 0f }.distinctUntilChanged()
  val oneRmDeadlift: Flow<Float>             = prefs.map { it[ONE_RM_DEADLIFT_KEY]       ?: 0f }.distinctUntilChanged()
  val oneRmOhp: Flow<Float>                  = prefs.map { it[ONE_RM_OHP_KEY]            ?: 0f }.distinctUntilChanged()

  suspend fun setDarkMode(enabled: Boolean) {
  context.dataStore.edit { prefs -> prefs[DARK_MODE_KEY] = enabled }
  }
  suspend fun setAccentHue(hue: Float) {
  context.dataStore.edit { prefs -> prefs[ACCENT_HUE_KEY] = hue }
  }
  suspend fun setAccentSaturation(sat: Float) {
  context.dataStore.edit { prefs -> prefs[ACCENT_SAT_KEY] = sat }
  }
  suspend fun resetAccent() {
  context.dataStore.edit { prefs ->
  prefs[ACCENT_HUE_KEY] = DEFAULT_ACCENT_HUE
  prefs[ACCENT_SAT_KEY] = DEFAULT_ACCENT_SAT
  }
  }
  suspend fun setFeatSessionTimer(v: Boolean)      { context.dataStore.edit { it[FEAT_TIMER_KEY]           = v } }
  suspend fun setFeatWeightAutofill(v: Boolean)    { context.dataStore.edit { it[FEAT_WEIGHT_AUTOFILL_KEY] = v } }
  suspend fun setFeatAddExercise(v: Boolean)       { context.dataStore.edit { it[FEAT_ADD_EXERCISE_KEY]    = v } }
  suspend fun setFeatProgressionPicker(v: Boolean) { context.dataStore.edit { it[FEAT_PROGRESSION_KEY]    = v } }
  suspend fun setFeat1rmCalculator(v: Boolean)     { context.dataStore.edit { it[FEAT_1RM_CALC_KEY]        = v } }
  suspend fun setTimerSound(v: String)             { context.dataStore.edit { it[TIMER_SOUND_KEY]          = v } }
  suspend fun setAgeGatePassed(v: Boolean)         { context.dataStore.edit { it[AGE_GATE_PASSED_KEY]      = v } }
  suspend fun setAgeGateBlocked(v: Boolean)        { context.dataStore.edit { it[AGE_GATE_BLOCKED_KEY]     = v } }
  suspend fun setOnboardingComplete(v: Boolean)    { context.dataStore.edit { it[ONBOARDING_COMPLETE_KEY]  = v } }
  suspend fun setAnalyticsConsent(v: String)       { context.dataStore.edit { it[ANALYTICS_CONSENT_KEY]    = v } }
  suspend fun setOneRm(lift: com.wildodds.gymtracker.data.profile.MainLift, kg: Float) {
  context.dataStore.edit {
  it[when (lift) {
  com.wildodds.gymtracker.data.profile.MainLift.SQUAT    -> ONE_RM_SQUAT_KEY
  com.wildodds.gymtracker.data.profile.MainLift.BENCH    -> ONE_RM_BENCH_KEY
  com.wildodds.gymtracker.data.profile.MainLift.DEADLIFT -> ONE_RM_DEADLIFT_KEY
  com.wildodds.gymtracker.data.profile.MainLift.OHP      -> ONE_RM_OHP_KEY
  }] = kg
  }
  }

  // ── Generic boolean-flag access ──────────────────────────────────────────────
  // The SettingsRegistry-driven UI and the FeatureFlags gate read/write flags by key.
  // The string keys here are the SAME ones the typed accessors above use (e.g.
  // "feat_session_timer"), so the generic and typed APIs stay in sync — a generic
  // write is visible to the matching typed Flow and vice-versa.

  /** Observe a boolean flag by key, falling back to [default] when unset. */
  fun flag(key: String, default: Boolean): Flow<Boolean> =
  prefs.map { it[booleanPreferencesKey(key)] ?: default }.distinctUntilChanged()

  /** Persist a boolean flag by key. */
  suspend fun setFlag(key: String, value: Boolean) {
  context.dataStore.edit { it[booleanPreferencesKey(key)] = value }
  }
}

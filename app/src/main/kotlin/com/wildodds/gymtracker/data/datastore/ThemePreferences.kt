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
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Default accent hue: ~203° (matches the original sky-blue #60B4E8) */
const val DEFAULT_ACCENT_HUE = 203f
const val DEFAULT_ACCENT_SAT = 0.72f

class ThemePreferences(private val context: Context) {
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

  val isDarkMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
  prefs[DARK_MODE_KEY] ?: false
  }
  val accentHue: Flow<Float> = context.dataStore.data.map { prefs ->
  prefs[ACCENT_HUE_KEY] ?: DEFAULT_ACCENT_HUE
  }
  val accentSaturation: Flow<Float> = context.dataStore.data.map { prefs ->
  prefs[ACCENT_SAT_KEY] ?: DEFAULT_ACCENT_SAT
  }
  val featSessionTimer: Flow<Boolean>        = context.dataStore.data.map { it[FEAT_TIMER_KEY]           ?: true }
  val featWeightAutofill: Flow<Boolean>      = context.dataStore.data.map { it[FEAT_WEIGHT_AUTOFILL_KEY] ?: true }
  val featAddExercise: Flow<Boolean>         = context.dataStore.data.map { it[FEAT_ADD_EXERCISE_KEY]    ?: true }
  val featProgressionPicker: Flow<Boolean>   = context.dataStore.data.map { it[FEAT_PROGRESSION_KEY]    ?: true }
  val feat1rmCalculator: Flow<Boolean>       = context.dataStore.data.map { it[FEAT_1RM_CALC_KEY]        ?: true }
  // "none" | "ding" | "ronnie"
  val timerSound: Flow<String>               = context.dataStore.data.map { it[TIMER_SOUND_KEY]          ?: "ding" }
  // Manually-entered 1RMs (kg). 0f = not set yet.
  val oneRmSquat: Flow<Float>                = context.dataStore.data.map { it[ONE_RM_SQUAT_KEY]          ?: 0f }
  val oneRmBench: Flow<Float>                = context.dataStore.data.map { it[ONE_RM_BENCH_KEY]          ?: 0f }
  val oneRmDeadlift: Flow<Float>             = context.dataStore.data.map { it[ONE_RM_DEADLIFT_KEY]       ?: 0f }
  val oneRmOhp: Flow<Float>                  = context.dataStore.data.map { it[ONE_RM_OHP_KEY]            ?: 0f }

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
  context.dataStore.data.map { it[booleanPreferencesKey(key)] ?: default }

  /** Persist a boolean flag by key. */
  suspend fun setFlag(key: String, value: Boolean) {
  context.dataStore.edit { it[booleanPreferencesKey(key)] = value }
  }
}

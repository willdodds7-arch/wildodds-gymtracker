package com.wildodds.gymtracker.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.ai.ClaudeApiClient
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.intelligence.AdaptiveInput
import com.wildodds.gymtracker.data.intelligence.AdaptivePlanEngine
import com.wildodds.gymtracker.data.intelligence.PlanAdjustment
import com.wildodds.gymtracker.data.intelligence.ReadinessEngine
import com.wildodds.gymtracker.data.intelligence.ReadinessInput
import com.wildodds.gymtracker.data.intelligence.ReadinessResult
import com.wildodds.gymtracker.data.repository.GymRepository
import com.wildodds.gymtracker.data.wearable.WearableProvider
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class RecoveryUiState(
  val readiness: ReadinessResult? = null,
  val adjustment: PlanAdjustment? = null,   // only set when actionable AND the feature is on
  val rationaleText: String? = null,        // optional LLM rephrasing of the above
  val loading: Boolean = false
)

/**
 * Drives the Phase 4B recovery features on Home. It is pure orchestration: it gathers inputs
 * (wearable recovery snapshot, recent completions, schedule, program stall) off the main thread and
 * hands them to the deterministic [ReadinessEngine] / [AdaptivePlanEngine]. The engines decide; this
 * VM only routes data and applies the user's accept/revert choices. Both features gate to OFF.
 */
class RecoveryViewModel(app: Application) : AndroidViewModel(app) {

  private val db = AppDatabase.getInstance(app)
  private val repo = GymRepository(db)
  private val themePrefs = ThemePreferences(app)
  private val gymPrefs = app.getSharedPreferences("gym_prefs", Context.MODE_PRIVATE)
  private val wearable = WearableProvider.create(app)

  private val _state = MutableStateFlow(RecoveryUiState())
  val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

  // The user dismissed today's card/suggestion (or accepted it); hide until next refresh.
  private val _dismissed = MutableStateFlow(false)
  val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()
  fun dismiss() { _dismissed.value = true }

  // Whether a "lighter session" overlay is currently armed for the next session. Only surfaced while
  // the Adaptive feature is on (set in refresh()), so turning it OFF removes the entry point.
  private val _overlayActive = MutableStateFlow(false)
  val overlayActive: StateFlow<Boolean> = _overlayActive.asStateFlow()

  init { refresh() }

  /** Recompute readiness + adjustment. Called on init and when Home resumes. */
  fun refresh() {
    viewModelScope.launch {
      val restDayEnabled = themePrefs.flag(SettingsRegistry.REST_DAY_RECS, false).first()
      val adaptiveEnabled = themePrefs.flag(SettingsRegistry.ADAPTIVE_PLAN, false).first()
      _overlayActive.value = adaptiveEnabled && RecoveryAdjustmentStore.isActive(getApplication())

      // Both features off → nothing to show, no work, no health reads.
      if (!restDayEnabled && !adaptiveEnabled) { _state.value = RecoveryUiState(); return@launch }

      _state.value = _state.value.copy(loading = true)
      val result = withContext(Dispatchers.IO) {
        val recent = repo.recentCompletions(5)
        val last = recent.firstOrNull()
        val daysSince = last?.let {
          ((System.currentTimeMillis() - it.completedAt) / 86_400_000L).toInt()
        }
        val snapshot = runCatching { wearable.recoverySnapshot(Instant.now()) }.getOrNull()

        val readiness = ReadinessEngine.score(ReadinessInput(
          sleepMinutes = snapshot?.sleepMinutes,
          restingHr = snapshot?.restingHr,
          restingHrBaseline = snapshot?.restingHrBaseline,
          hrvRmssd = snapshot?.hrvRmssd,
          hrvBaseline = snapshot?.hrvBaseline,
          recentStrain = last?.strainRating,
          recentFatigueScore = last?.fatigueScore,
          daysSinceLastSession = daysSince
        ))
        val stalled = runCatching { repo.isActiveProgramStalled() }.getOrDefault(false)
        val adjustment = AdaptivePlanEngine.adjust(AdaptiveInput(
          readiness = readiness?.level,
          programStalled = stalled,
          daysSinceLastSession = daysSince
        ))
        readiness to adjustment
      }
      val (readiness, adjustment) = result
      _state.value = RecoveryUiState(
        readiness = if (restDayEnabled) readiness else null,
        adjustment = if (adaptiveEnabled && adjustment.isActionable) adjustment else null,
        loading = false
      )
    }
  }

  /** Accept the suggested adjustment → arm the reversible overlay for the next session. */
  fun applyAdjustment() {
    val adj = _state.value.adjustment ?: return
    RecoveryAdjustmentStore.save(
      getApplication(), adj.loadScale, adj.volumeScale, adj.headline, System.currentTimeMillis()
    )
    _overlayActive.value = true
    _dismissed.value = true
  }

  fun declineAdjustment() { _dismissed.value = true }

  /** Disarm the overlay (the next session returns to normal). */
  fun clearOverlay() {
    RecoveryAdjustmentStore.clear(getApplication())
    _overlayActive.value = false
  }

  /**
   * Optional: ask Claude to rephrase the already-decided guidance into a friendlier sentence. The
   * decision is unchanged — only [RecoveryUiState.rationaleText] is filled. No key / any error → no-op.
   */
  fun explainWithAi() {
    viewModelScope.launch {
      val s = _state.value
      val facts = buildString {
        s.readiness?.let { append("Readiness: ${it.headline}. Reason: ${it.reason}. ") }
        s.adjustment?.let { append("Plan: ${it.headline}. ${it.detail}") }
      }.trim()
      val key = gymPrefs.getString("claude_api_key", "") ?: ""
      if (facts.isBlank() || key.isBlank()) return@launch
      val text = withContext(Dispatchers.IO) {
        runCatching { ClaudeApiClient.summarizeRecoveryPlan(key, facts) }.getOrNull()
      }
      if (!text.isNullOrBlank()) _state.value = _state.value.copy(rationaleText = text)
    }
  }
}

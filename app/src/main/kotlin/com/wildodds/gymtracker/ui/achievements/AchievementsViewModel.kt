package com.wildodds.gymtracker.ui.achievements

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.gamification.AchievementEngine
import com.wildodds.gymtracker.data.gamification.AchievementProgress
import com.wildodds.gymtracker.data.gamification.AchievementUnlock
import com.wildodds.gymtracker.data.repository.GymRepository
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AchievementsUiState(
  val enabled: Boolean = true,
  val progress: List<AchievementProgress> = emptyList(),
  val unlockedCount: Int = 0,
  val totalCount: Int = 0,
  val currentStreakDays: Int = 0,
  val loading: Boolean = true
)

/**
 * Drives the Profile/Achievements area and the Home streak chip. Evaluation is delegated to the pure,
 * idempotent [AchievementEngine] via the repository; this VM only routes data and surfaces a calm
 * one-line celebration for anything that just unlocked.
 */
class AchievementsViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = GymRepository(AppDatabase.getInstance(app))
  private val themePrefs = ThemePreferences(app)
  private val gymPrefs = app.getSharedPreferences("gym_prefs", Context.MODE_PRIVATE)

  private val _state = MutableStateFlow(AchievementsUiState())
  val state: StateFlow<AchievementsUiState> = _state.asStateFlow()

  // A just-unlocked achievement to celebrate briefly (then cleared by the UI).
  private val _justUnlocked = MutableStateFlow<AchievementUnlock?>(null)
  val justUnlocked: StateFlow<AchievementUnlock?> = _justUnlocked.asStateFlow()
  fun clearCelebration() { _justUnlocked.value = null }

  init { refresh() }

  private fun usedTravel(): Boolean = gymPrefs.getBoolean("used_travel_mode", false)

  /** Recompute progress for display (no awarding). */
  fun refresh() {
    viewModelScope.launch {
      val enabled = themePrefs.flag(SettingsRegistry.ACHIEVEMENTS, true).first()
      if (!enabled) { _state.value = AchievementsUiState(enabled = false, loading = false); return@launch }
      val metrics = repo.computeAchievementMetrics(usedTravel())
      val unlocked = repo.unlockedAchievementIds()
      val progress = AchievementEngine.progress(metrics, unlocked)
      _state.value = AchievementsUiState(
        enabled = true,
        progress = progress,
        unlockedCount = progress.count { it.unlocked },
        totalCount = progress.size,
        currentStreakDays = metrics.currentStreakDays,
        loading = false
      )
    }
  }

  /**
   * Evaluate after a relevant event (e.g. opening Home after a session). Persists newly-unlocked
   * achievements (idempotent), surfaces a celebration, and publishes the summary if sharing is on.
   */
  fun evaluate() {
    viewModelScope.launch {
      val enabled = themePrefs.flag(SettingsRegistry.ACHIEVEMENTS, true).first()
      if (!enabled) return@launch
      val newly = repo.evaluateAndUnlockAchievements(usedTravel(), System.currentTimeMillis())
      if (newly.isNotEmpty()) _justUnlocked.value = newly.first()
      refresh()
    }
  }
}

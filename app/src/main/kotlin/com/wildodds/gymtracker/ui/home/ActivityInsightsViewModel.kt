package com.wildodds.gymtracker.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.repository.GymRepository
import com.wildodds.gymtracker.data.retention.DropoutAssessment
import com.wildodds.gymtracker.data.retention.DropoutRiskEngine
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Computes the user's OWN dropout-risk for the quiet Home insight card (Phase 7A). This is strictly
 * private — a risk score is never shared or shown for anyone else. Gated by the local
 * `SHOW_INSIGHTS` flag (default ON); when off, nothing is computed or shown.
 */
class ActivityInsightsViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = GymRepository(AppDatabase.getInstance(app))
  private val themePrefs = ThemePreferences(app)

  private val _assessment = MutableStateFlow<DropoutAssessment?>(null)
  val assessment: StateFlow<DropoutAssessment?> = _assessment.asStateFlow()

  private val _dismissed = MutableStateFlow(false)
  val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()
  fun dismiss() { _dismissed.value = true }

  init { refresh() }

  fun refresh() {
    viewModelScope.launch {
      val enabled = themePrefs.flag(SettingsRegistry.SHOW_INSIGHTS, true).first()
      if (!enabled) { _assessment.value = null; return@launch }
      _assessment.value = DropoutRiskEngine.assess(repo.computeDropoutFeatures())
    }
  }
}

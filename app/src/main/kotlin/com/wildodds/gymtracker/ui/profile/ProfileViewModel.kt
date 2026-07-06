package com.wildodds.gymtracker.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.profile.MainLift
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One lift's maxes: what the user states, and the best estimate from their logs (if any). */
data class MaxRow(
  val lift: MainLift,
  val statedKg: Float,        // 0f = not set
  val potentialKg: Float?     // best Epley estimate from logs, null if none logged
) {
  /** Show the potential only when it actually beats the stated 1RM (the whole point of it). */
  val showPotential: Boolean get() = potentialKg != null && potentialKg > statedKg + 0.5f
}

data class ProfileUiState(
  val maxes: List<MaxRow> = emptyList(),
  val favouriteMuscle: String? = null,   // most-hit muscle over the last 4 weeks
  val favouriteSplit: String? = null,    // most-trained split over the last 12 weeks
  val loading: Boolean = true
)

class ProfileViewModel(app: Application) : AndroidViewModel(app) {

  private val repo = GymRepository(AppDatabase.getInstance(app))
  private val themePrefs = ThemePreferences(app)

  private val _state = MutableStateFlow(ProfileUiState())
  val state: StateFlow<ProfileUiState> = _state.asStateFlow()

  init { refresh() }

  fun refresh() {
    viewModelScope.launch {
      val stated = mapOf(
        MainLift.SQUAT    to themePrefs.oneRmSquat.first(),
        MainLift.BENCH    to themePrefs.oneRmBench.first(),
        MainLift.DEADLIFT to themePrefs.oneRmDeadlift.first(),
        MainLift.OHP      to themePrefs.oneRmOhp.first()
      )
      val potential = repo.bestEstimatedOneRm()
      val favMuscle = repo.favouriteMuscle(days = 28)   // last 4 weeks
      val favSplit  = repo.favouriteSplit(days = 84)    // last 12 weeks
      _state.value = ProfileUiState(
        maxes = MainLift.entries.map { lift ->
          MaxRow(lift = lift, statedKg = stated[lift] ?: 0f, potentialKg = potential[lift])
        },
        favouriteMuscle = favMuscle,
        favouriteSplit = favSplit,
        loading = false
      )
    }
  }

  fun setOneRm(lift: MainLift, kg: Float) {
    viewModelScope.launch {
      themePrefs.setOneRm(lift, kg.coerceAtLeast(0f))
      refresh()
    }
  }
}

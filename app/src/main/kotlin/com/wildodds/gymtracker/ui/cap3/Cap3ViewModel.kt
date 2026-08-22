package com.wildodds.gymtracker.ui.cap3

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.cap3.Cap3Config
import com.wildodds.gymtracker.data.cap3.Cap3DaySlot
import com.wildodds.gymtracker.data.cap3.Cap3Preferences
import com.wildodds.gymtracker.data.cap3.Cap3ProgramBuilder
import com.wildodds.gymtracker.data.cap3.Cap3Slot
import com.wildodds.gymtracker.data.cap3.Cap3Units
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs CAP3 setup: the lift slots, their maxes, and turning them into a real program. */
class Cap3ViewModel(app: Application) : AndroidViewModel(app) {

  private val prefs = Cap3Preferences(app)
  private val repo = GymRepository(AppDatabase.getInstance(app))

  val config: StateFlow<Cap3Config> = prefs.config
    .stateIn(viewModelScope, SharingStarted.Eagerly, Cap3Config())

  private val _message = MutableStateFlow<String?>(null)
  val message: StateFlow<String?> = _message.asStateFlow()

  private val _created = MutableStateFlow(false)
  val created: StateFlow<Boolean> = _created.asStateFlow()

  fun setUnits(units: Cap3Units) = viewModelScope.launch { prefs.setUnits(units) }

  fun setExercise(slot: Cap3Slot, exercise: String) =
    viewModelScope.launch { prefs.setExercise(slot, exercise) }

  fun setSecondaryFor(day: Cap3DaySlot, slot: Cap3Slot) =
    viewModelScope.launch { prefs.setSecondaryOverride(day, slot) }

  /**
   * Save a max as it is typed. [referenceMax] is in the display unit; blank or nonsense clears the
   * slot rather than storing a zero, so the "every slot needs a max" check stays honest.
   */
  fun setReferenceMax(slot: Cap3Slot, referenceMax: Double?) = viewModelScope.launch {
    if (referenceMax == null || referenceMax <= 0) prefs.clearReferenceMax(slot)
    else prefs.setReferenceMax(slot, config.value.units.toKg(referenceMax))
  }

  /** Build the 3-week program from the current maxes and make it the active program. */
  fun createProgram() = viewModelScope.launch {
    val cfg = config.value
    if (!cfg.isSetUp) {
      _message.value = "Enter a max for every lift slot first."
      return@launch
    }
    repo.importProgram(Cap3ProgramBuilder.build(cfg), activate = true)
    _message.value = "nSuns CAP3 is now your active program."
    _created.value = true
  }

  fun reset() = viewModelScope.launch { prefs.reset() }

  fun clearMessage() { _message.value = null }
  fun clearCreated() { _created.value = false }
}

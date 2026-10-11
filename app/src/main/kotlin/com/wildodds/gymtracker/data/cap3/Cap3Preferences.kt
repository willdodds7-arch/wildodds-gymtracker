package com.wildodds.gymtracker.data.cap3

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wildodds.gymtracker.data.datastore.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * CAP3 lift setup, on top of the app's existing settings DataStore. Position in the cycle is not
 * stored here — the generated program tracks that like any other program does.
 *
 * Reference and training maxes are persisted in kilograms so switching Kg↔Lb only changes rendering
 * and rounding.
 */
class Cap3Preferences(private val context: Context) {

  private val unitsKey = stringPreferencesKey("cap3_units")

  private fun exerciseKey(slot: Cap3Slot) = stringPreferencesKey("cap3_${slot.name}_exercise")
  private fun referenceMaxKey(slot: Cap3Slot) = floatPreferencesKey("cap3_${slot.name}_ref_max")
  private fun trainingMaxKey(slot: Cap3Slot) = floatPreferencesKey("cap3_${slot.name}_training_max")
  private fun bestEstKey(slot: Cap3Slot) = floatPreferencesKey("cap3_${slot.name}_best_est")
  private fun secondaryKey(day: Cap3DaySlot) = stringPreferencesKey("cap3_secondary_${day.name}")

  val config: Flow<Cap3Config> = context.dataStore.data.map { it.toConfig() }

  private fun Preferences.toConfig(): Cap3Config {
    val lifts = Cap3Slot.entries.associateWith { slot ->
      val referenceMax = this[referenceMaxKey(slot)]?.toDouble() ?: 0.0
      Cap3LiftConfig(
        slot = slot,
        exercise = this[exerciseKey(slot)] ?: slot.defaultExercise,
        referenceMaxKg = referenceMax,
        trainingMaxKg = this[trainingMaxKey(slot)]?.toDouble() ?: (referenceMax * CAP3_TM_FACTOR),
        bestEst1RmKg = this[bestEstKey(slot)]?.toDouble() ?: referenceMax
      )
    }
    val overrides = Cap3DaySlot.entries.mapNotNull { day ->
      val stored = this[secondaryKey(day)] ?: return@mapNotNull null
      Cap3Slot.byName(stored)?.let { day to it }
    }.toMap()
    return Cap3Config(
      units = Cap3Units.byLabel(this[unitsKey]),
      lifts = lifts,
      secondaryOverrides = overrides
    )
  }

  suspend fun setUnits(units: Cap3Units) {
    context.dataStore.edit { it[unitsKey] = units.label }
  }

  suspend fun setExercise(slot: Cap3Slot, exercise: String) {
    context.dataStore.edit { it[exerciseKey(slot)] = exercise }
  }

  /**
   * Set a lift's reference max. Saved as typed — no separate confirm step — and it resets that
   * lift's training max to 90% of the new number, which is what changing your max means. Training
   * maxes earned since then are deliberately discarded, so this is the one destructive edit in
   * setup; progression moves the training max, never this.
   */
  suspend fun setReferenceMax(slot: Cap3Slot, referenceMaxKg: Double) {
    context.dataStore.edit { prefs ->
      prefs[referenceMaxKey(slot)] = referenceMaxKg.toFloat()
      prefs[trainingMaxKey(slot)] = Cap3Engine.trainingMaxFrom(referenceMaxKg).toFloat()
      prefs[bestEstKey(slot)] = referenceMaxKg.toFloat()
    }
  }

  suspend fun clearReferenceMax(slot: Cap3Slot) {
    context.dataStore.edit { prefs ->
      prefs.remove(referenceMaxKey(slot))
      prefs.remove(trainingMaxKey(slot))
      prefs.remove(bestEstKey(slot))
    }
  }

  suspend fun setTrainingMax(slot: Cap3Slot, trainingMaxKg: Double, bestEst1RmKg: Double? = null) {
    context.dataStore.edit { prefs ->
      prefs[trainingMaxKey(slot)] = trainingMaxKg.toFloat()
      bestEst1RmKg?.let { prefs[bestEstKey(slot)] = it.toFloat() }
    }
  }

  /** Clears every CAP3 key, leaving the rest of the app's settings alone. */
  suspend fun reset() {
    context.dataStore.edit { prefs ->
      prefs.asMap().keys.filter { it.name.startsWith("cap3_") }.forEach { prefs.remove(it) }
    }
  }
}

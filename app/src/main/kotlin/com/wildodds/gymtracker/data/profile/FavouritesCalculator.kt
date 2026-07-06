package com.wildodds.gymtracker.data.profile

import com.wildodds.gymtracker.data.ExerciseLibrary

/** One exercise and how many sets of it were performed inside a time window. */
data class ExerciseSetCount(val exerciseName: String, val setCount: Int)

/** One split and how many sessions of it were completed inside a time window. */
data class SplitCount(val split: String, val count: Int)

/**
 * Pure derivations for the profile "favourites" — no Android, fully unit-testable.
 */
object FavouritesCalculator {

  /**
   * The muscle group hit the most, by weighted set count. Each set counts 1.0 toward an exercise's
   * primary muscles and 0.5 toward its secondaries (matching the rest of the app's volume model).
   * Returns null when nothing maps to a known muscle.
   */
  fun favouriteMuscle(rows: List<ExerciseSetCount>): String? {
    val tally = HashMap<String, Double>()
    for (r in rows) {
      if (r.setCount <= 0) continue
      val info = ExerciseLibrary.lookup(r.exerciseName) ?: continue
      info.primary.forEach { tally.merge(it.display, r.setCount.toDouble(), Double::plus) }
      info.secondary.forEach { tally.merge(it.display, r.setCount * 0.5, Double::plus) }
    }
    return tally.maxByOrNull { it.value }?.key
  }

  /** The split with the most completed sessions. Returns null when there's nothing tagged. */
  fun favouriteSplit(rows: List<SplitCount>): String? =
    rows.filter { it.split.isNotBlank() && it.count > 0 }.maxByOrNull { it.count }?.split
}

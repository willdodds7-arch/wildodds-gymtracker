package com.wildodds.gymtracker.data.profile

import com.wildodds.gymtracker.data.analytics.TrendAggregator

/** A single logged set, flattened to just what a 1RM estimate needs. */
data class LiftSetRow(val exerciseName: String, val weightKg: Float, val reps: Int)

/** The barbell lifts the Profile tracks a 1RM for. */
enum class MainLift(val key: String, val label: String) {
  SQUAT("squat", "Squat"),
  BENCH("bench", "Bench Press"),
  DEADLIFT("deadlift", "Deadlift"),
  OHP("ohp", "Overhead Press");

  /** Loose name match so logged exercises map to a main lift (e.g. "Barbell Bench Press" → BENCH). */
  fun matches(exerciseName: String): Boolean {
    val n = exerciseName.lowercase()
    return when (this) {
      SQUAT    -> n.contains("squat")
      BENCH    -> n.contains("bench")
      DEADLIFT -> n.contains("deadlift") || n.contains("dead lift")
      OHP      -> n.contains("overhead press") || n.contains("military press") ||
                  n.contains("strict press") || n == "ohp" || n.contains("ohp ")
    }
  }

  companion object {
    fun forExercise(name: String): MainLift? = entries.firstOrNull { it.matches(name) }
  }
}

/**
 * Pure helper: the best estimated 1RM per main lift from a user's logged sets, using the same Epley
 * formula as the in-app 1RM calculator ([TrendAggregator.epley]). Sets with no real load/reps are
 * ignored. Returns only lifts that have at least one qualifying set.
 */
object OneRepMaxEstimator {

  fun bestByLift(rows: List<LiftSetRow>): Map<MainLift, Float> {
    val best = mutableMapOf<MainLift, Float>()
    for (row in rows) {
      if (row.weightKg <= 0f || row.reps < 1) continue
      val lift = MainLift.forExercise(row.exerciseName) ?: continue
      val est = TrendAggregator.epley(row.weightKg, row.reps)
      val prev = best[lift]
      if (prev == null || est > prev) best[lift] = est
    }
    return best
  }
}

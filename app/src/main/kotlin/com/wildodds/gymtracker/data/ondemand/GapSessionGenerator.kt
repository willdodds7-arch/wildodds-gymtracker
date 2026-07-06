package com.wildodds.gymtracker.data.ondemand

import com.wildodds.gymtracker.data.ExerciseLibrary
import com.wildodds.gymtracker.data.MuscleGroup

/**
 * Pure generator for "fill the gap" sessions: for each under-worked muscle group it assembles a
 * short session from exercises that primarily train it (drawn from [ExerciseLibrary]).
 */
object GapSessionGenerator {

  /** @param underWorked muscle groups to target, most-deficient first. */
  fun generate(underWorked: List<MuscleGroup>, perMuscle: Int = 4): List<OnDemandSession> =
  underWorked.mapNotNull { muscle ->
  val names = ExerciseLibrary.LIBRARY.entries
  .filter { muscle in it.value.primary }
  .map { it.key }
  .take(perMuscle)
  if (names.size < 2) return@mapNotNull null  // not enough to make a session

  val exercises = names.map { OnDemandExercise(titleCase(it), sets = 3, repsTarget = "8-12") }
  val focus = (setOf(muscle) + names.flatMap { ExerciseLibrary.LIBRARY[it]?.primary ?: emptyList() }).toSet()
  OnDemandSession(
  id = "gen_${muscle.name}",
  title = "${muscle.display} focus",
  source = SessionSource.GENERATED,
  muscleFocus = focus,
  durationMin = estimateDuration(exercises),
  equipment = emptySet(),   // equipment-agnostic; enriched at load if needed
  exercises = exercises
  )
  }

  internal fun estimateDuration(exercises: List<OnDemandExercise>): Int =
  (exercises.sumOf { it.sets } * 2 + 5).coerceIn(10, 60)

  private fun titleCase(s: String): String =
  s.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

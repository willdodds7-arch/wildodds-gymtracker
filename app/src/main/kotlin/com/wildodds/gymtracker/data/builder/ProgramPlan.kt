package com.wildodds.gymtracker.data.builder

import java.util.UUID

/**
 * The program builder's in-memory model.
 *
 * Deliberately plain Kotlin with no Android or Room types, so every rule that operates on it
 * ([ProgramMath], [ProgressionMethod], [ProgramPlanJson]) is a pure function with a unit test. The
 * DB mapping happens once, at save time, in the repository.
 *
 * Relationship to the stored schema: a `PlanWorkout` is ONE definition, whereas the `sessions`
 * table physically materialises every week (a 4-day × 12-week program is 48 rows). The builder
 * edits the definition and the save step expands it across weeks, so editing "Push A" stays a
 * single edit rather than twelve.
 */

/** A single prescribed set. [type] separates warm-ups from the working sets volume math counts. */
data class PlanSet(
  val id: String = UUID.randomUUID().toString(),
  val type: SetType = SetType.WORKING,
  /** Free text so "60", "bodyweight" and "" are all valid — never blocks saving. */
  val load: String = "",
  /** "8", "8-12", "AMRAP", "30s" — parsed leniently by [ProgramMath.repsForVolume]. */
  val reps: String = "",
  val restSeconds: Int = 90,
  val rpe: String = "",
  val tempo: String = "",
  val notes: String = ""
)

enum class SetType(val display: String, val countsAsVolume: Boolean) {
  WARMUP("Warm-up", false),
  WORKING("Working", true),
  DROP("Drop set", true),
  FAILURE("To failure", true),
  BACKOFF("Back-off", true)
}

data class PlanExercise(
  val id: String = UUID.randomUUID().toString(),
  val name: String = "",
  val sets: List<PlanSet> = emptyList(),
  val notes: String = "",
  /** Non-null when this exercise belongs to a superset/triset/circuit group. */
  val supersetGroupId: String? = null,
  /** Manually-chosen swaps, shown during the workout. Names only — resolved against the library. */
  val alternatives: List<String> = emptyList(),
  /** Optional exercises can be skipped without the workout counting as incomplete. */
  val isOptional: Boolean = false
) {
  val workingSetCount: Int get() = sets.count { it.type.countsAsVolume }
  /** True when nothing in this exercise prescribes a rep target — surfaced as a soft warning. */
  val hasNoRepTarget: Boolean get() = sets.none { it.reps.isNotBlank() }
}

/** A superset / triset / circuit: exercises performed back to back for [rounds] rounds. */
data class PlanGroup(
  val id: String = UUID.randomUUID().toString(),
  val label: String = "A",
  val rounds: Int = 3,
  val restBetweenExercisesSeconds: Int = 0,
  val restAfterRoundSeconds: Int = 90
)

enum class WorkoutKind { TRAINING, REST, DELOAD }

data class PlanWorkout(
  val id: String = UUID.randomUUID().toString(),
  val name: String = "",
  val kind: WorkoutKind = WorkoutKind.TRAINING,
  val exercises: List<PlanExercise> = emptyList(),
  val groups: List<PlanGroup> = emptyList(),
  /** 0 = unassigned/sequential; 1..7 = Mon..Sun. Scheduling never forces a weekday. */
  val weekday: Int = 0,
  val note: String = "",
  /** Optional workouts are offered but not required to complete the week. */
  val isOptional: Boolean = false
) {
  val isRest: Boolean get() = kind == WorkoutKind.REST
  val exerciseCount: Int get() = exercises.size
  /** A training workout with no exercises, or with exercises nobody gave targets to. */
  val isEmpty: Boolean get() = kind == WorkoutKind.TRAINING && exercises.isEmpty()
}

enum class ProgramGoal(val display: String) {
  HYPERTROPHY("Hypertrophy"),
  STRENGTH("Strength"),
  GENERAL("General fitness"),
  FAT_LOSS("Fat loss"),
  CUSTOM("Custom")
}

enum class ExperienceLevel(val display: String) {
  BEGINNER("Beginner"), INTERMEDIATE("Intermediate"), ADVANCED("Advanced")
}

data class PlanProgram(
  val id: String = UUID.randomUUID().toString(),
  /** The ONLY required field, and even this has a sensible default. */
  val name: String = "",
  val description: String = "",
  val goal: ProgramGoal = ProgramGoal.HYPERTROPHY,
  val experience: ExperienceLevel = ExperienceLevel.INTERMEDIATE,
  /** 0 = "Ongoing". */
  val durationWeeks: Int = 0,
  val workouts: List<PlanWorkout> = emptyList(),
  val progression: ProgressionSettings = ProgressionSettings(),
  val trackRpe: Boolean = false,
  val trackOneRm: Boolean = false,
  val coverImage: String? = null,
  val folder: String = ""
) {
  val isOngoing: Boolean get() = durationWeeks <= 0
  val trainingWorkouts: List<PlanWorkout> get() = workouts.filter { !it.isRest }

  companion object {
    const val DEFAULT_NAME = "My program"

    /** Never let a nameless program block a save (§3 convenience). */
    fun resolvedName(raw: String): String = raw.trim().ifBlank { DEFAULT_NAME }
  }
}

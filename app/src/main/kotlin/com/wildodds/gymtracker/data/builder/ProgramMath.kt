package com.wildodds.gymtracker.data.builder

import com.wildodds.gymtracker.data.ExerciseLibrary
import com.wildodds.gymtracker.data.MuscleGroup

/**
 * Volume, duration and completeness maths for the program builder.
 *
 * Pure functions over [PlanProgram] — no Android, no database, no coroutines — so the summary panel
 * can recompute on every keystroke and each rule is unit-testable in isolation.
 */
object ProgramMath {

  /** Seconds of actual work per set, on top of the prescribed rest. */
  private const val WORK_SECONDS_PER_SET = 45

  /** Fixed overhead per workout: warm-up, setup, changing plates. */
  private const val WORKOUT_OVERHEAD_SECONDS = 5 * 60

  /** A secondary muscle does roughly half the work of a primary one. Matches the Library analysis. */
  private const val SECONDARY_WEIGHT = 0.5f

  // ── Duration ───────────────────────────────────────────────────────────────

  /**
   * Estimated wall-clock minutes for one workout: every set costs its rest plus a fixed work
   * period, and grouped exercises (supersets) charge rest only once per round rather than per
   * exercise, which is the whole point of pairing them.
   */
  fun estimateWorkoutMinutes(workout: PlanWorkout): Int {
    if (workout.isRest || workout.exercises.isEmpty()) return 0

    val (grouped, solo) = workout.exercises.partition { it.supersetGroupId != null }

    var seconds = WORKOUT_OVERHEAD_SECONDS

    solo.forEach { ex ->
      ex.sets.forEach { set -> seconds += WORK_SECONDS_PER_SET + set.restSeconds }
    }

    grouped.groupBy { it.supersetGroupId }.forEach { (groupId, members) ->
      val group = workout.groups.firstOrNull { it.id == groupId }
      val rounds = group?.rounds ?: members.maxOfOrNull { it.sets.size } ?: 0
      val betweenRest = group?.restBetweenExercisesSeconds ?: 0
      val roundRest = group?.restAfterRoundSeconds ?: 90
      // Per round: work every member, short rest between them, one long rest at the end.
      val perRound = members.size * (WORK_SECONDS_PER_SET + betweenRest) + roundRest
      seconds += rounds * perRound
    }

    return Math.round(seconds / 60f).coerceAtLeast(1)
  }

  /** Total training minutes across one week of the program. Rest days contribute nothing. */
  fun estimateWeeklyMinutes(program: PlanProgram): Int =
    program.workouts.sumOf { estimateWorkoutMinutes(it) }

  /** "4 h 20 min", "45 min", "0 min" — the form used in the summary panel. */
  fun formatMinutes(total: Int): String {
    if (total < 60) return "$total min"
    val h = total / 60
    val m = total % 60
    return if (m == 0) "$h h" else "$h h $m min"
  }

  // ── Volume ─────────────────────────────────────────────────────────────────

  /**
   * Working sets per muscle group across one week. Primary muscles count fully, secondary at half,
   * matching the existing Library analysis so the two never disagree. Exercises whose name is not
   * in the library contribute nothing and are reported by [unrecognisedExercises] instead.
   */
  fun weeklySetsByMuscle(program: PlanProgram): Map<MuscleGroup, Float> {
    val totals = mutableMapOf<MuscleGroup, Float>()
    program.workouts.filter { !it.isRest }.forEach { workout ->
      workout.exercises.forEach { ex ->
        val info = ExerciseLibrary.lookup(ex.name) ?: return@forEach
        val sets = effectiveSetCount(workout, ex).toFloat()
        if (sets <= 0f) return@forEach
        info.primary.forEach { m -> totals[m] = (totals[m] ?: 0f) + sets }
        info.secondary.forEach { m -> totals[m] = (totals[m] ?: 0f) + sets * SECONDARY_WEIGHT }
      }
    }
    return totals
  }

  /**
   * How many working sets this exercise actually contributes. Inside a superset the group's round
   * count wins — three rounds of a pair is three sets each, however many rows were typed in.
   */
  fun effectiveSetCount(workout: PlanWorkout, exercise: PlanExercise): Int {
    val group = exercise.supersetGroupId?.let { id -> workout.groups.firstOrNull { it.id == id } }
    return group?.rounds ?: exercise.workingSetCount
  }

  /** Muscle totals, biggest first — the order the summary panel lists them in. */
  fun rankedVolume(program: PlanProgram): List<Pair<MuscleGroup, Float>> =
    weeklySetsByMuscle(program).toList().sortedByDescending { it.second }

  /** Names the exercise library could not identify, so volume for them is missing, not zero. */
  fun unrecognisedExercises(program: PlanProgram): List<String> =
    program.workouts.filter { !it.isRest }
      .flatMap { it.exercises }
      .filter { it.name.isNotBlank() && ExerciseLibrary.lookup(it.name) == null }
      .map { it.name }
      .distinct()

  /**
   * Leniently reads a rep target for volume purposes: "8" → 8, "8-12" → 10 (midpoint),
   * "30s" → 0 (timed work has no rep count), "AMRAP"/"" → 0.
   */
  fun repsForVolume(target: String): Int {
    if (target.isBlank()) return 0
    if (target.trim().endsWith("s", ignoreCase = true) &&
      target.none { it.isLetter() && !it.equals('s', ignoreCase = true) }
    ) return 0
    val nums = Regex("\\d+").findAll(target).map { it.value.toInt() }.toList()
    return when {
      nums.isEmpty() -> 0
      nums.size == 1 -> nums[0]
      else -> (nums.min() + nums.max()) / 2
    }
  }

  // ── Equipment + frequency ──────────────────────────────────────────────────

  /** Distinct equipment the program calls for, for the summary line. */
  fun equipmentUsed(program: PlanProgram): List<String> =
    program.workouts.filter { !it.isRest }
      .flatMap { it.exercises }
      .mapNotNull { EquipmentGuess.of(it.name) }
      .distinct()
      .sorted()

  /** Training days per week — rest days excluded. */
  fun trainingDaysPerWeek(program: PlanProgram): Int = program.trainingWorkouts.size

  // ── Warnings (never blocking) ──────────────────────────────────────────────

  /**
   * Soft warnings shown in the summary and next to Save. Every one of these is informational —
   * an incomplete program always saves.
   */
  fun warnings(program: PlanProgram): List<String> {
    val out = mutableListOf<String>()

    val noTarget = program.workouts.filter { !it.isRest }
      .flatMap { it.exercises }
      .count { it.name.isNotBlank() && it.hasNoRepTarget }
    if (noTarget > 0) {
      out += "$noTarget exercise${if (noTarget == 1) "" else "s"} " +
        "ha${if (noTarget == 1) "s" else "ve"} no rep target"
    }

    val emptyWorkouts = program.workouts.filter { it.isEmpty }
    if (emptyWorkouts.isNotEmpty()) {
      val names = emptyWorkouts.joinToString(", ") { it.name.ifBlank { "Untitled" } }
      out += "$names ${if (emptyWorkouts.size == 1) "has" else "have"} no exercises"
    }

    val unnamed = program.workouts.count { it.name.isBlank() }
    if (unnamed > 0) out += "$unnamed workout${if (unnamed == 1) "" else "s"} unnamed"

    val unknown = unrecognisedExercises(program)
    if (unknown.isNotEmpty()) {
      out += "${unknown.size} exercise${if (unknown.size == 1) "" else "s"} " +
        "not in the library, so ${if (unknown.size == 1) "it is" else "they are"} " +
        "missing from the volume totals"
    }

    if (program.trainingWorkouts.isEmpty()) out += "No training days yet"

    return out
  }

  // ── Whole-program summary ──────────────────────────────────────────────────

  fun summarise(program: PlanProgram): ProgramSummary {
    val training = program.trainingWorkouts
    return ProgramSummary(
      workoutCount = training.size,
      exerciseCount = training.sumOf { it.exerciseCount },
      weeklyMinutes = estimateWeeklyMinutes(program),
      volume = rankedVolume(program),
      equipment = equipmentUsed(program),
      warnings = warnings(program)
    )
  }
}

data class ProgramSummary(
  val workoutCount: Int,
  val exerciseCount: Int,
  val weeklyMinutes: Int,
  val volume: List<Pair<MuscleGroup, Float>>,
  val equipment: List<String>,
  val warnings: List<String>
) {
  /** "5 workouts · 34 exercises · ~4 h 20 min/week" */
  val headline: String
    get() = buildString {
      append("$workoutCount workout${if (workoutCount == 1) "" else "s"}")
      append(" · $exerciseCount exercise${if (exerciseCount == 1) "" else "s"}")
      if (weeklyMinutes > 0) append(" · ~${ProgramMath.formatMinutes(weeklyMinutes)}/week")
    }
}

/**
 * Best-effort equipment tag from an exercise name. The bundled exercise graph has authoritative
 * equipment data, but it is loaded asynchronously from assets; this keeps the summary pure and
 * instant, and only ever drives a descriptive line.
 */
internal object EquipmentGuess {
  private val PATTERNS = listOf(
    "barbell" to "Barbell",
    "dumbbell" to "Dumbbell",
    "machine" to "Machine",
    "cable" to "Cable",
    "smith" to "Machine",
    "band" to "Bands",
    "kettlebell" to "Kettlebell",
    "bodyweight" to "Bodyweight",
    "push-up" to "Bodyweight",
    "pushup" to "Bodyweight",
    "pull-up" to "Bodyweight",
    "pullup" to "Bodyweight",
    "chin-up" to "Bodyweight",
    "dip" to "Bodyweight"
  )

  fun of(name: String): String? {
    val lower = name.lowercase()
    return PATTERNS.firstOrNull { lower.contains(it.first) }?.second
  }
}

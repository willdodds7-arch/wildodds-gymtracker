package com.wildodds.gymtracker.data.builder

import com.wildodds.gymtracker.data.ExerciseLibrary
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.ExerciseAlternative
import com.wildodds.gymtracker.data.db.entity.PlannedSet
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession

/**
 * Converts between the builder's [PlanProgram] and the stored schema.
 *
 * The one structural decision worth knowing: a `PlanWorkout` is a single DEFINITION, but `sessions`
 * physically materialises every week. [toParsed] expands one plan week across the program's length,
 * so "Push A" is authored once and written N times. [fromStored] collapses it back by reading week 1
 * only. That keeps editing cheap and, crucially, non-destructive — nothing renumbers or deletes the
 * later weeks' rows behind the user's back.
 *
 * Rest days are NOT written as sessions (the schema has no concept of one); they only occupy a
 * `dayNumber` slot so the surrounding days keep their positions. The plan JSON keeps them, so an
 * export/import round trip is still lossless even though the DB round trip is not.
 */
object PlanMapper {

  /** Ongoing programs still need a finite number of materialised weeks. */
  const val ONGOING_WEEKS = 12

  // ── Plan → storage ─────────────────────────────────────────────────────────

  fun toParsed(plan: PlanProgram, isUserCreated: Boolean = true): ParsedProgram {
    val weeks = if (plan.isOngoing) ONGOING_WEEKS else plan.durationWeeks

    // Day numbers follow the plan order INCLUDING rest days, so a rest day between Push and Pull
    // keeps Pull on day 3 rather than silently sliding it up to day 2.
    val training = plan.workouts.mapIndexedNotNull { index, w ->
      if (w.isRest) null else (index + 1) to w
    }

    val sessions = (1..weeks).flatMap { week ->
      training.map { (dayNumber, w) ->
        ParsedSession(
          weekNumber = week,
          dayNumber = dayNumber,
          name = w.name.ifBlank { "Day $dayNumber" },
          muscleGroups = muscleSummary(w),
          exercises = w.exercises.mapIndexed { i, ex -> toParsedExercise(ex, i) }
        )
      }
    }

    return ParsedProgram(
      name = PlanProgram.resolvedName(plan.name),
      totalWeeks = weeks,
      sessions = sessions,
      coverImage = plan.coverImage,
      category = plan.goal.display,
      isUserCreated = isUserCreated,
      trackRpe = plan.trackRpe,
      trackOneRm = plan.trackOneRm,
      description = plan.description,
      daysPerWeek = ProgramMath.trainingDaysPerWeek(plan),
      style = plan.goal.display
    )
  }

  /**
   * Collapse a plan exercise into the legacy single-row shape every existing screen reads.
   * `sets` is the working-set count and `repsTarget` the most common rep target, so a program
   * saved by the new builder still renders correctly in the session screen, the importers and the
   * CAP3 engine — none of which know about [PlannedSet].
   */
  private fun toParsedExercise(ex: PlanExercise, orderIndex: Int) = ParsedExercise(
    name = ex.name,
    sets = ex.workingSetCount.coerceAtLeast(1),
    repsTarget = dominantRepTarget(ex),
    notes = ex.notes,
    orderIndex = orderIndex,
    rpeTarget = ex.sets.firstOrNull { it.rpe.isNotBlank() }?.rpe.orEmpty(),
    pct1rmTarget = ""
  )

  /** The rep target most working sets share — the single value the legacy column can hold. */
  internal fun dominantRepTarget(ex: PlanExercise): String =
    ex.sets.filter { it.type.countsAsVolume && it.reps.isNotBlank() }
      .groupingBy { it.reps }
      .eachCount()
      .maxByOrNull { it.value }
      ?.key
      .orEmpty()

  /** "Chest / Triceps / Front Delts" — derived, so the user never has to type it. */
  internal fun muscleSummary(workout: PlanWorkout): String {
    val counts = mutableMapOf<String, Int>()
    workout.exercises.forEach { ex ->
      ExerciseLibrary.lookup(ex.name)?.primary?.forEach { m ->
        counts[m.display] = (counts[m.display] ?: 0) + 1
      }
    }
    return counts.entries.sortedByDescending { it.value }.take(3).joinToString(" / ") { it.key }
  }

  /**
   * Per-set rows for one saved exercise. Returns empty when the exercise has no detail worth
   * storing — a plain "3 × 8-12" is already fully described by the legacy columns, and writing
   * redundant rows would just be noise in the database.
   */
  fun plannedSetsFor(exerciseId: Long, ex: PlanExercise): List<PlannedSet> {
    if (!hasPerSetDetail(ex)) return emptyList()
    return ex.sets.mapIndexed { i, s ->
      PlannedSet(
        exerciseId = exerciseId,
        setNumber = i + 1,
        type = s.type.name,
        load = s.load,
        reps = s.reps,
        restSeconds = s.restSeconds,
        rpe = s.rpe,
        tempo = s.tempo,
        notes = s.notes
      )
    }
  }

  /** True when the sets differ from each other, or carry anything the legacy columns can't hold. */
  internal fun hasPerSetDetail(ex: PlanExercise): Boolean {
    if (ex.sets.isEmpty()) return false
    if (ex.sets.any { it.type != SetType.WORKING }) return true
    if (ex.sets.any { it.load.isNotBlank() || it.tempo.isNotBlank() || it.notes.isNotBlank() }) return true
    return ex.sets.map { it.reps }.distinct().size > 1 ||
      ex.sets.map { it.restSeconds }.distinct().size > 1
  }

  fun alternativesFor(exerciseId: Long, ex: PlanExercise): List<ExerciseAlternative> =
    ex.alternatives.mapIndexed { i, name ->
      ExerciseAlternative(exerciseId = exerciseId, name = name, orderIndex = i)
    }

  // ── Storage → plan ─────────────────────────────────────────────────────────

  /**
   * Rebuild an editable plan from week 1 of a stored program. Later weeks are ignored: they are
   * copies of the same definition, and reading them would produce N duplicate workouts.
   */
  fun fromStored(
    program: com.wildodds.gymtracker.data.db.entity.Program,
    week1Sessions: List<Session>,
    exercisesBySession: Map<Long, List<Exercise>>,
    plannedSets: Map<Long, List<PlannedSet>> = emptyMap(),
    alternatives: Map<Long, List<ExerciseAlternative>> = emptyMap()
  ): PlanProgram {
    val ordered = week1Sessions.sortedBy { it.dayNumber }
    val workouts = ordered.map { session ->
      val exercises = (exercisesBySession[session.id] ?: emptyList())
        .sortedBy { it.orderIndex }
        .map { ex -> toPlanExercise(ex, plannedSets[ex.id], alternatives[ex.id]) }
      PlanWorkout(
        name = session.name,
        kind = WorkoutKind.TRAINING,
        exercises = exercises,
        weekday = 0
      )
    }

    return PlanProgram(
      name = program.name,
      description = program.description,
      goal = ProgramGoal.entries.firstOrNull { it.display.equals(program.style, true) }
        ?: ProgramGoal.entries.firstOrNull { it.display.equals(program.category, true) }
        ?: ProgramGoal.HYPERTROPHY,
      durationWeeks = program.totalWeeks,
      workouts = workouts,
      trackRpe = program.trackRpe,
      trackOneRm = program.trackOneRm,
      coverImage = program.coverImage
    )
  }

  private fun toPlanExercise(
    ex: Exercise,
    planned: List<PlannedSet>?,
    alts: List<ExerciseAlternative>?
  ): PlanExercise {
    // Prefer the detailed rows when they exist; otherwise expand the legacy columns into
    // [ex.sets] identical working sets so the builder always has something to show.
    val sets = if (!planned.isNullOrEmpty()) {
      planned.sortedBy { it.setNumber }.map { p ->
        PlanSet(
          type = runCatching { SetType.valueOf(p.type) }.getOrDefault(SetType.WORKING),
          load = p.load,
          reps = p.reps,
          restSeconds = p.restSeconds,
          rpe = p.rpe,
          tempo = p.tempo,
          notes = p.notes
        )
      }
    } else {
      List(ex.sets.coerceAtLeast(1)) {
        PlanSet(type = SetType.WORKING, reps = ex.repsTarget, rpe = ex.rpeTarget)
      }
    }

    return PlanExercise(
      name = ex.name,
      sets = sets,
      notes = ex.notes,
      alternatives = alts?.sortedBy { it.orderIndex }?.map { it.name } ?: emptyList()
    )
  }
}

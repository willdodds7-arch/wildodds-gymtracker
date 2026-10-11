package com.wildodds.gymtracker.data.builder

import com.wildodds.gymtracker.data.ExerciseLibrary
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.ExerciseAlternative
import com.wildodds.gymtracker.data.db.entity.PlannedSet
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.importer.SessionType
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
    // keeps Pull on day 3 rather than silently sliding it up to day 2. Since v26 the rest days are
    // WRITTEN too, as sessions with a non-TRAINING `sessionType` and no exercises, so the shape of
    // the week survives a save/reload instead of coming back as pure training days. Every existing
    // query filters them out (see SessionDao), so nothing that counts training days changes.
    val ordered = plan.workouts.mapIndexed { index, w -> (index + 1) to w }

    val sessions = (1..weeks).flatMap { week ->
      ordered.map { (dayNumber, w) ->
        ParsedSession(
          weekNumber = week,
          dayNumber = dayNumber,
          name = w.name.ifBlank { if (w.isRest) "Rest" else "Day $dayNumber" },
          muscleGroups = if (w.isRest) "" else muscleSummary(w),
          exercises = if (w.isRest) emptyList() else w.exercises.mapIndexed { i, ex -> toParsedExercise(ex, i, week) },
          sessionType = sessionTypeOf(w)
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

  /** [WorkoutKind] -> the stored `sessions.sessionType`. The two vocabularies are deliberately 1:1. */
  private fun sessionTypeOf(w: PlanWorkout): String = when (w.kind) {
    WorkoutKind.TRAINING -> SessionType.TRAINING.name
    WorkoutKind.REST -> SessionType.REST.name
    WorkoutKind.ACTIVE_RECOVERY -> SessionType.ACTIVE_RECOVERY.name
    WorkoutKind.DELOAD -> SessionType.DELOAD_NOTE.name
  }

  /** The stored `sessions.sessionType` -> [WorkoutKind]. Unknown values read as TRAINING. */
  internal fun workoutKindOf(sessionType: String): WorkoutKind =
    when (runCatching { SessionType.valueOf(sessionType) }.getOrNull()) {
      SessionType.REST -> WorkoutKind.REST
      SessionType.ACTIVE_RECOVERY -> WorkoutKind.ACTIVE_RECOVERY
      SessionType.DELOAD_NOTE -> WorkoutKind.DELOAD
      else -> WorkoutKind.TRAINING
    }

  /**
   * Collapse a plan exercise into the legacy single-row shape every existing screen reads.
   * `sets` is the working-set count and `repsTarget` the most common rep target, so a program
   * saved by the new builder still renders correctly in the session screen, the importers and the
   * CAP3 engine — none of which know about [PlannedSet].
   */
  private fun toParsedExercise(ex: PlanExercise, orderIndex: Int, week: Int): ParsedExercise {
    // The one per-week exception: a 12-week linear progression writes each week's own sets × reps
    // and a coach note with that week's target. Tagged so later edits know not to flatten it, and
    // the starting weight is kept in loadValue so the builder can read the spec back.
    ex.linear12?.let { spec ->
      val start = spec.startLoad.toFloatOrNull()
      val wp = TwelveWeekLinear.weekPlan(week - 1, start)
      return ParsedExercise(
        name = ex.name,
        sets = wp.sets,
        repsTarget = wp.reps.toString(),
        notes = TwelveWeekLinear.mergeNotes(ex.notes, wp.note),
        orderIndex = orderIndex,
        rpeTarget = ex.sets.firstOrNull { it.rpe.isNotBlank() }?.rpe.orEmpty(),
        pct1rmTarget = "",
        loadValue = start?.toDouble(),
        modifiers = listOf(TwelveWeekLinear.MODIFIER)
      )
    }
    return ParsedExercise(
      name = ex.name,
      sets = ex.workingSetCount.coerceAtLeast(1),
      repsTarget = dominantRepTarget(ex),
      notes = ex.notes,
      orderIndex = orderIndex,
      rpeTarget = ex.sets.firstOrNull { it.rpe.isNotBlank() }?.rpe.orEmpty(),
      pct1rmTarget = ""
    )
  }

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
    // Per-set rows would pin week 1's set count on the session screen; the progression's weeks
    // are fully described by each week's own legacy columns.
    if (ex.linear12 != null) return emptyList()
    if (!hasPerSetDetail(ex)) return emptyList()
    return ex.sets.mapIndexed { i, s ->
      PlannedSet(
        exerciseId = exerciseId,
        setNumber = i + 1,
        type = s.type.name,
        load = s.load,
        loadPercent = s.loadPercent,
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
    // A percentage ladder is the whole prescription — it must survive a save even when every row
    // happens to share the same reps and rest.
    if (ex.sets.any { it.loadPercent != null }) return true
    return ex.sets.map { it.reps }.distinct().size > 1 ||
      ex.sets.map { it.restSeconds }.distinct().size > 1
  }

  fun alternativesFor(exerciseId: Long, ex: PlanExercise): List<ExerciseAlternative> =
    ex.alternatives.mapIndexed { i, name ->
      ExerciseAlternative(exerciseId = exerciseId, name = name, orderIndex = i)
    }

  /** Which week of a multi-week import seeds the builder. */
  enum class SeedWeek {
    /** The program as written in its first week. The default, and what the builder always did. */
    FIRST,
    /** The week with the most prescribed working sets — usually the peak, sometimes the one to edit. */
    HEAVIEST
  }

  /**
   * Convert a freshly-imported [ParsedProgram] into builder workouts.
   *
   * Only ONE week is read, and that is deliberate rather than a limitation of convenience: a
   * `PlanWorkout` is a single definition that the save step expands across every week, so reading
   * all N weeks would produce N copies of each day. [seed] chooses which week that is — first
   * (default), heaviest, or a specific one via [seedWeekNumber].
   *
   * Weeks 2..N carry progression a single plan definition cannot hold. That is not silently
   * dropped: `ImportReviewViewModel` tells the user plainly, and the per-week detail stays in the
   * imported program itself, which is committed in full.
   *
   * Rest days DO come through, as `WorkoutKind.REST` workouts in their original day positions.
   */
  fun workoutsFromParsed(
    parsed: ParsedProgram,
    seed: SeedWeek = SeedWeek.FIRST,
    seedWeekNumber: Int? = null
  ): List<PlanWorkout> {
    val week = seedWeekNumber
      ?: when (seed) {
        SeedWeek.FIRST -> parsed.sessions.minOfOrNull { it.weekNumber } ?: 1
        SeedWeek.HEAVIEST -> parsed.sessions
          .groupBy { it.weekNumber }
          // Ties go to the earliest week, so the choice is deterministic on every device.
          .entries.sortedWith(compareByDescending<Map.Entry<Int, List<ParsedSession>>> { (_, ss) ->
            ss.sumOf { s -> s.exercises.sumOf { it.sets } }
          }.thenBy { it.key })
          .firstOrNull()?.key ?: 1
      }
    return parsed.sessions
      .filter { it.weekNumber == week }
      .sortedBy { it.dayNumber }
      .map { session ->
        val kind = workoutKindOf(session.sessionType)
        PlanWorkout(
          name = session.name,
          kind = kind,
          exercises = if (kind == WorkoutKind.REST || kind == WorkoutKind.ACTIVE_RECOVERY) emptyList()
          else session.exercises.sortedBy { it.orderIndex }.map(::toPlanExerciseFromParsed)
        )
      }
  }

  /**
   * One imported exercise -> builder exercise. Prefers the per-set detail the importer recorded
   * (so "5/3/1" arrives as three sets with three rep counts rather than three identical ones) and
   * falls back to expanding the flat target when the source really did prescribe uniform sets.
   */
  private fun toPlanExerciseFromParsed(ex: ParsedExercise): PlanExercise {
    val sets = if (ex.perSet.isNotEmpty()) {
      ex.perSet.sortedBy { it.setNumber }.map { p ->
        PlanSet(
          type = SetType.WORKING,
          reps = p.reps,
          restSeconds = p.restSeconds ?: ex.restSeconds ?: DEFAULT_REST_SECONDS,
          rpe = if (p.loadKind == "RPE") fmtLoad(p.loadValue) else ex.rpeTarget,
          tempo = p.tempo.ifBlank { ex.tempo.orEmpty() }
        )
      }
    } else {
      List(ex.sets.coerceAtLeast(1)) {
        PlanSet(
          type = SetType.WORKING,
          reps = ex.repsTarget,
          restSeconds = ex.restSeconds ?: DEFAULT_REST_SECONDS,
          rpe = ex.rpeTarget,
          tempo = ex.tempo.orEmpty()
        )
      }
    }
    return PlanExercise(
      name = ex.name,
      notes = ex.notes,
      sets = sets,
      supersetGroupId = ex.supersetGroup,
      isOptional = ex.modifiers.contains(com.wildodds.gymtracker.data.importer.Modifier.OPTIONAL)
    )
  }

  /**
   * The rest the builder shows when the source didn't state one. It is a UI default, not a claim
   * about the program: the stored `Exercise.restSeconds` stays NULL, so nothing downstream reads
   * this back as "the coach said 90 seconds".
   */
  const val DEFAULT_REST_SECONDS = 90

  private fun fmtLoad(d: Double?): String =
    d?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()

  // ── Storage → plan ─────────────────────────────────────────────────────────

  /**
   * Rebuild an editable plan from week 1 of a stored program. Later weeks are ignored: they are
   * copies of the same definition, and reading them would produce N duplicate workouts.
   *
   * Pass [week1Sessions] from `SessionDao.getSessionsForWeekIncludingRest` so rest days come back
   * as rest days; the training-only query would silently reshape the week.
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
      val kind = workoutKindOf(session.sessionType)
      val exercises = if (kind == WorkoutKind.REST || kind == WorkoutKind.ACTIVE_RECOVERY) emptyList()
        else (exercisesBySession[session.id] ?: emptyList())
          .sortedBy { it.orderIndex }
          .map { ex -> toPlanExercise(ex, plannedSets[ex.id], alternatives[ex.id]) }
      PlanWorkout(
        name = session.name,
        kind = kind,
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
          loadPercent = p.loadPercent,
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

    // A stored progression comes back as the spec, with its generated week-1 note removed so the
    // next save doesn't stack a second one on top.
    val linear12 = if (TwelveWeekLinear.MODIFIER in ex.modifiers)
      Linear12Spec(startLoad = fmtLoad(ex.loadValue)) else null

    return PlanExercise(
      name = ex.name,
      sets = sets,
      notes = if (linear12 != null) TwelveWeekLinear.stripNote(ex.notes) else ex.notes,
      alternatives = alts?.sortedBy { it.orderIndex }?.map { it.name } ?: emptyList(),
      linear12 = linear12
    )
  }
}

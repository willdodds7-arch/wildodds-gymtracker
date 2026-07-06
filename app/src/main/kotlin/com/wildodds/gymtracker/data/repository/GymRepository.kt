package com.wildodds.gymtracker.data.repository

import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.dao.SetLogWithWeek
import com.wildodds.gymtracker.data.db.entity.*
import com.wildodds.gymtracker.data.gamification.AchievementEngine
import com.wildodds.gymtracker.data.gamification.AchievementUnlock
import com.wildodds.gymtracker.data.gamification.MetricsCalculator
import com.wildodds.gymtracker.data.gamification.MetricsSnapshot
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Materialise a single representative week across [weeks] weeks. When [weeks] <= 1 the input is
 * returned unchanged (programs that already carry their full multi-week structure are untouched).
 */
internal fun expandWeeks(weekOne: List<ParsedSession>, weeks: Int): List<ParsedSession> {
  if (weeks <= 1) return weekOne
  val base = weekOne.filter { it.weekNumber == 1 }.ifEmpty { weekOne }
  return (1..weeks).flatMap { w -> base.map { it.copy(weekNumber = w) } }
}

class GymRepository(private val db: AppDatabase) {

  companion object {
  /** Hidden program that holds one-off / on-demand sessions so the active program is untouched. */
  const val ON_DEMAND_PROGRAM_NAME = "__on_demand__"
  }

  // The lifting library hides the hidden on-demand holder program.
  private fun List<Program>.visible() = filterNot { it.name == ON_DEMAND_PROGRAM_NAME }

  /** Best estimated 1RM per main lift (Squat/Bench/Deadlift) from all logged sets. */
  suspend fun bestEstimatedOneRm(): Map<com.wildodds.gymtracker.data.profile.MainLift, Float> =
  withContext(Dispatchers.IO) {
  com.wildodds.gymtracker.data.profile.OneRepMaxEstimator.bestByLift(db.setLogDao().getAllLiftSetRows())
  }

  /** Muscle group hit the most over the last [days] days (profile "favourite muscle"). */
  suspend fun favouriteMuscle(days: Int): String? = withContext(Dispatchers.IO) {
  val since = System.currentTimeMillis() - days.toLong() * 86_400_000L
  com.wildodds.gymtracker.data.profile.FavouritesCalculator
  .favouriteMuscle(db.setLogDao().getExerciseSetCountsSince(since))
  }

  /** Split trained the most over the last [days] days (profile "favourite split"). */
  suspend fun favouriteSplit(days: Int): String? = withContext(Dispatchers.IO) {
  val since = System.currentTimeMillis() - days.toLong() * 86_400_000L
  com.wildodds.gymtracker.data.profile.FavouritesCalculator
  .favouriteSplit(db.sessionCompletionDao().getSplitCountsSince(since))
  }

  fun currentProgram(): Flow<Program?> = db.programDao().getCurrentProgram()
  fun allPrograms(): Flow<List<Program>> = db.programDao().getAllPrograms().map { it.visible() }
  fun userPrograms(): Flow<List<Program>> = db.programDao().getUserPrograms().map { it.visible() }
  fun pausedPrograms(): Flow<List<Program>> = db.programDao().getPausedPrograms().map { it.visible() }
  fun allSessions(programId: Long): Flow<List<Session>> = db.sessionDao().getAllSessions(programId)
  fun allCompletions(): Flow<List<SessionCompletion>> = db.sessionCompletionDao().getAllCompletions()
  /** Read-only stream of all exercises in a program, grouped per session by the Home view. */
  fun programExercises(programId: Long): Flow<List<com.wildodds.gymtracker.data.db.entity.Exercise>> =
    db.exerciseDao().observeExercisesForProgram(programId)

  suspend fun importProgram(parsed: ParsedProgram, activate: Boolean = true): Long = withContext(Dispatchers.IO) {
  if (activate) {
  // Delete the currently active non-user program (cascades its sessions/exercises/logs).
  // User-created programs are kept so they can be re-activated later.
  val active = db.programDao().getCurrentProgramOnce()
  if (active != null && !active.isUserCreated) {
  db.programDao().deleteById(active.id)
  } else {
  db.programDao().deactivateAll()
  }
  }

  // Catalogue blocks ship a single representative week; materialise it across the block length.
  val materialisedSessions = expandWeeks(parsed.sessions, parsed.repeatWeeks)
  val totalWeeks = maxOf(parsed.totalWeeks, materialisedSessions.maxOfOrNull { it.weekNumber } ?: 1)

  // Derive days/week from the program's week-1 day count when the parser didn't supply one.
  val derivedDays = parsed.sessions.filter { it.weekNumber == 1 }.map { it.dayNumber }.distinct().size
  val programId = db.programDao().insert(
  Program(name = parsed.name, totalWeeks = totalWeeks,
  coverImage = parsed.coverImage, isFlexible = parsed.isFlexible,
  category = parsed.category, isActive = activate,
  isUserCreated = parsed.isUserCreated,
  trackRpe = parsed.trackRpe, trackOneRm = parsed.trackOneRm,
  description = parsed.description, coach = parsed.coach, coachBio = parsed.coachBio,
  daysPerWeek = if (parsed.daysPerWeek > 0) parsed.daysPerWeek else derivedDays,
  split = parsed.split, style = parsed.style)
  )

  // Persist phases (only when the program genuinely has more than one block).
  if (parsed.phases.size > 1) {
  db.programPhaseDao().insertAll(parsed.phases.map { p ->
  ProgramPhase(programId = programId, phaseNumber = p.phaseNumber,
  name = p.name, durationWeeks = p.durationWeeks, focus = p.focus)
  })
  }

  val sessions = materialisedSessions.map { ps ->
  Session(programId = programId, weekNumber = ps.weekNumber, dayNumber = ps.dayNumber,
  name = ps.name, muscleGroups = ps.muscleGroups, phaseNumber = ps.phaseNumber)
  }
  db.sessionDao().insertAll(sessions)

  for (ps in materialisedSessions) {
  val dbSession = db.sessionDao()
  .getSessionsForWeek(ps.weekNumber, programId)
  .find { it.dayNumber == ps.dayNumber } ?: continue
  db.exerciseDao().insertAll(ps.exercises.map { pe ->
  Exercise(sessionId = dbSession.id, name = pe.name, sets = pe.sets,
  repsTarget = pe.repsTarget, notes = pe.notes, orderIndex = pe.orderIndex,
  rpeTarget = pe.rpeTarget, pct1rmTarget = pe.pct1rmTarget)
  })
  }
  programId
  }

  /** Save without activating. */
  suspend fun addToLibrary(parsed: ParsedProgram) = importProgram(parsed, activate = false)

  /** Clears all logs/completions but keeps the program & exercises intact. */
  suspend fun restartProgram() = withContext(Dispatchers.IO) {
  db.setLogDao().deleteAll()
  db.workoutLogDao().deleteAll()
  db.sessionCompletionDao().deleteAll()
  }

  /** Permanently delete a program and all its associated data. */
  suspend fun deleteProgram(programId: Long) = withContext(Dispatchers.IO) {
  db.setLogDao().deleteForProgram(programId)
  db.workoutLogDao().deleteForProgram(programId)
  db.sessionCompletionDao().deleteForProgram(programId)
  db.sessionDao().deleteForProgram(programId)
  db.programDao().deleteById(programId)
  }

  /**
  * Cancel the active program.
  * - Non-user programs: deleted entirely (cascades all data).
  * - User-created programs: just deactivated so they can be re-activated later.
  */
  suspend fun cancelProgram() = withContext(Dispatchers.IO) {
  val active = db.programDao().getCurrentProgramOnce() ?: return@withContext
  if (active.isUserCreated) {
  db.programDao().deactivateAll()
  } else {
  db.programDao().deleteById(active.id)
  }
  }

  /** Set a stored (user-created) program as the active one. */
  suspend fun activateProgram(programId: Long) = withContext(Dispatchers.IO) {
  val active = db.programDao().getCurrentProgramOnce()
  if (active != null && !active.isUserCreated) {
  db.programDao().deleteById(active.id)
  } else {
  db.programDao().deactivateAll()
  }
  db.programDao().setPaused(programId, false)
  db.programDao().setActiveById(programId)
  }

  /**
   * Start a program from the library. Pauses any currently active program so it
   * can be resumed later (progress intact), then activates the chosen one.
   */
  suspend fun startProgramFromLibrary(programId: Long) = withContext(Dispatchers.IO) {
  db.programDao().pauseActiveProgram()        // current → paused
  db.programDao().setPaused(programId, false)  // target → unpaused
  db.programDao().setActiveById(programId)     // target → active
  }

  /**
   * Resume a paused program. Pauses whatever is currently active first.
   */
  suspend fun resumePausedProgram(programId: Long) = withContext(Dispatchers.IO) {
  db.programDao().pauseActiveProgram()
  db.programDao().setPaused(programId, false)
  db.programDao().setActiveById(programId)
  }

  /**
   * Restart a paused program from Week 1 — wipes its logs/completions, then activates it.
   */
  suspend fun restartPausedProgram(programId: Long) = withContext(Dispatchers.IO) {
  db.setLogDao().deleteForProgram(programId)
  db.workoutLogDao().deleteForProgram(programId)
  db.sessionCompletionDao().deleteForProgram(programId)
  db.programDao().pauseActiveProgram()
  db.programDao().setPaused(programId, false)
  db.programDao().setActiveById(programId)
  }

  suspend fun getSessionById(sessionId: Long): Session? = withContext(Dispatchers.IO) {
  db.sessionDao().getSessionById(sessionId)
  }

  suspend fun getExercisesForSession(sessionId: Long): List<Exercise> = withContext(Dispatchers.IO) {
  db.exerciseDao().getExercisesForSession(sessionId)
  }

  suspend fun getOrCreateWorkoutLog(exerciseId: Long, sessionId: Long, weekNumber: Int): WorkoutLog =
  withContext(Dispatchers.IO) {
  db.workoutLogDao().getWorkoutLogForExercise(exerciseId, weekNumber)
  ?: run {
  val id = db.workoutLogDao().insert(
  WorkoutLog(exerciseId = exerciseId, sessionId = sessionId, weekNumber = weekNumber)
  )
  WorkoutLog(id = id, exerciseId = exerciseId, sessionId = sessionId, weekNumber = weekNumber)
  }
  }

  suspend fun deleteSetLog(workoutLogId: Long, setNumber: Int) = withContext(Dispatchers.IO) {
  db.setLogDao().deleteSetLog(workoutLogId, setNumber)
  }

  suspend fun upsertSetLog(setLog: SetLog) = withContext(Dispatchers.IO) {
  val updated = db.setLogDao().update(setLog.workoutLogId, setLog.setNumber, setLog.weightKg, setLog.reps, setLog.rpe, setLog.pct1rm, setLog.weightRightKg, setLog.repsRight)
  if (updated == 0) db.setLogDao().insert(setLog)
  }

  suspend fun getSetLogsForWorkoutLog(workoutLogId: Long): List<SetLog> = withContext(Dispatchers.IO) {
  db.setLogDao().getSetLogsForWorkoutLog(workoutLogId)
  }

  suspend fun getPrevWeekSetLogsByPosition(
  programId: Long, dayNumber: Int, currentWeek: Int, orderIndex: Int
  ): List<SetLog> = withContext(Dispatchers.IO) {
  db.setLogDao().getPrevWeekSetLogsByPosition(
  programId = programId, dayNumber = dayNumber,
  currentWeek = currentWeek, orderIndex = orderIndex
  )
  }

  suspend fun getPrevWeekProgressionByPosition(
  programId: Long, dayNumber: Int, currentWeek: Int, orderIndex: Int
  ): String? = withContext(Dispatchers.IO) {
  db.workoutLogDao().getPrevWeekProgressionByPosition(
  programId = programId, dayNumber = dayNumber,
  prevWeek = currentWeek - 1, orderIndex = orderIndex
  )
  }

  /** Set (or clear, when [choice] is null) the progression choice on a specific week's log. */
  suspend fun updateProgressionChoice(workoutLogId: Long, choice: String?) = withContext(Dispatchers.IO) {
  db.workoutLogDao().updateProgressionChoice(workoutLogId, choice)
  }

  suspend fun updateExerciseName(exerciseId: Long, newName: String, orderIndex: Int,
  dayNumber: Int, programId: Long, currentWeek: Int) = withContext(Dispatchers.IO) {
  db.exerciseDao().updateName(exerciseId, newName)
  db.exerciseDao().updateNameForFutureWeeks(newName, orderIndex, dayNumber, programId, currentWeek)
  }

  /**
   * Swap an exercise for an alternative by renaming it (muscles/analysis re-derive from the new
   * name via ExerciseLibrary). [applyToFuture] also renames the same position in later weeks.
   */
  suspend fun swapExercise(
  exerciseId: Long, newName: String, orderIndex: Int,
  dayNumber: Int, programId: Long, currentWeek: Int, applyToFuture: Boolean
  ) = withContext(Dispatchers.IO) {
  db.exerciseDao().updateName(exerciseId, newName)
  if (applyToFuture) {
  db.exerciseDao().updateNameForFutureWeeks(newName, orderIndex, dayNumber, programId, currentWeek)
  }
  }

  /** Link two exercises as a superset, propagating to all weeks. */
  suspend fun linkSuperset(
    exerciseId1: Long, orderIndex1: Int,
    exerciseId2: Long, orderIndex2: Int,
    groupId: Int, dayNumber: Int, programId: Long
  ) = withContext(Dispatchers.IO) {
    db.exerciseDao().setSupersetGroupId(exerciseId1, groupId)
    db.exerciseDao().setSupersetGroupId(exerciseId2, groupId)
    db.exerciseDao().setSupersetGroupIdForAllWeeks(groupId, orderIndex1, dayNumber, programId)
    db.exerciseDao().setSupersetGroupIdForAllWeeks(groupId, orderIndex2, dayNumber, programId)
  }

  /** Remove a superset link, propagating to all weeks. */
  suspend fun unlinkSuperset(
    orderIndex1: Int, orderIndex2: Int, dayNumber: Int, programId: Long
  ) = withContext(Dispatchers.IO) {
    db.exerciseDao().setSupersetGroupIdForAllWeeks(null, orderIndex1, dayNumber, programId)
    if (orderIndex2 >= 0) db.exerciseDao().setSupersetGroupIdForAllWeeks(null, orderIndex2, dayNumber, programId)
  }

  /** Set the unilateral mode (0/1/2) for an exercise position, propagating to all weeks. */
  suspend fun setUnilateralMode(
    exerciseId: Long, mode: Int, orderIndex: Int, dayNumber: Int, programId: Long
  ) = withContext(Dispatchers.IO) {
    db.exerciseDao().setUnilateralMode(exerciseId, mode)
    db.exerciseDao().setUnilateralModeForAllWeeks(mode, orderIndex, dayNumber, programId)
  }

  /**
   * Delete an exercise at a position across ALL weeks of the program,
   * shifting later exercises' orderIndex down to keep positions contiguous.
   */
  suspend fun deleteExerciseAtPosition(
    orderIndex: Int, dayNumber: Int, programId: Long
  ) = withContext(Dispatchers.IO) {
    db.exerciseDao().deleteByPositionForAllWeeks(orderIndex, dayNumber, programId)
    db.exerciseDao().shiftOrderIndexesDownAfterDelete(orderIndex, dayNumber, programId)
  }

  /**
   * Reorder exercises within a day. reorderMap = list of (exerciseId, oldOrderIndex, newOrderIndex).
   * Updates the current week's exercises by ID, and propagates index changes to all future weeks
   * using a large offset to avoid transient conflicts.
   */
  suspend fun reorderExercises(
    reorderMap: List<Triple<Long, Int, Int>>,
    dayNumber: Int, programId: Long, currentWeek: Int
  ) = withContext(Dispatchers.IO) {
    val OFFSET = 10000
    reorderMap.forEach { (id, _, newIdx) -> db.exerciseDao().updateOrderIndex(id, newIdx) }
    reorderMap.forEach { (_, oldIdx, _) ->
      db.exerciseDao().updateOrderIndexForFutureWeeks(oldIdx, oldIdx + OFFSET, dayNumber, programId, currentWeek)
    }
    reorderMap.forEach { (_, oldIdx, newIdx) ->
      db.exerciseDao().updateOrderIndexForFutureWeeks(oldIdx + OFFSET, newIdx, dayNumber, programId, currentWeek)
    }
  }

  suspend fun updateExerciseSets(exerciseId: Long, sets: Int, orderIndex: Int,
  dayNumber: Int, programId: Long, currentWeek: Int) = withContext(Dispatchers.IO) {
  db.exerciseDao().updateSets(exerciseId, sets)
  db.exerciseDao().updateSetsForFutureWeeks(sets, orderIndex, dayNumber, programId, currentWeek)
  }

  /** Mark a day done and store its post-session summary (duration + any HR data). */
  suspend fun completeSession(
  sessionId: Long, weekNumber: Int,
  durationSeconds: Long? = null,
  avgHeartRate: Int? = null, peakHeartRate: Int? = null, hrSeries: String? = null,
  fatigueScore: Int? = null
  ) = withContext(Dispatchers.IO) {
  db.sessionCompletionDao().insert(SessionCompletion(
  sessionId = sessionId, weekNumber = weekNumber,
  durationSeconds = durationSeconds,
  avgHeartRate = avgHeartRate, peakHeartRate = peakHeartRate, hrSeries = hrSeries,
  fatigueScore = fatigueScore
  ))
  }

  /** Persist the user's 1–5 strain rating for a completed session. */
  suspend fun setSessionStrain(sessionId: Long, weekNumber: Int, strain: Int?) = withContext(Dispatchers.IO) {
  db.sessionCompletionDao().updateStrain(sessionId, weekNumber, strain)
  }

  suspend fun getSessionCompletion(sessionId: Long, weekNumber: Int): SessionCompletion? =
  withContext(Dispatchers.IO) { db.sessionCompletionDao().getCompletion(sessionId, weekNumber) }

  suspend fun isSessionCompleted(sessionId: Long, weekNumber: Int): Boolean = withContext(Dispatchers.IO) {
  db.sessionCompletionDao().isSessionCompleted(sessionId, weekNumber)
  }

  /**
  * Reset a single day for a given week: wipe its logged sets and workout logs and
  * clear its completion mark. On reopen the day reverts to its carried-over (previous
  * week) prefill values and is no longer marked done. Only this (session, week) is
  * affected — other weeks and days are untouched.
  */
  suspend fun resetSessionDay(sessionId: Long, weekNumber: Int) = withContext(Dispatchers.IO) {
  db.setLogDao().deleteForSessionWeek(sessionId, weekNumber)
  db.workoutLogDao().deleteForSessionWeek(sessionId, weekNumber)
  db.sessionCompletionDao().deleteCompletion(sessionId, weekNumber)
  }

  // ── Editing a program's days from Home ──────────────────────────────────────

  /** True if any session of this program has ever been completed (i.e. the program is under way). */
  suspend fun programHasAnyCompletion(programId: Long): Boolean = withContext(Dispatchers.IO) {
  db.sessionCompletionDao().getCompletionsForProgram(programId).isNotEmpty()
  }

  /**
   * Rotate a program's day order so [startDayNumber] becomes day 1 and the rest follow, wrapping
   * (e.g. start day 3 of 4 → 3,4,1,2). Applied across EVERY week so all weeks share the new order.
   * Only renumbers the `dayNumber` column (via UPDATE, never delete) so logs/completions are kept.
   * No-op when the program has ≤1 day or [startDayNumber] is already first.
   */
  suspend fun rotateProgramStartDay(programId: Long, startDayNumber: Int) = withContext(Dispatchers.IO) {
  val sessions = db.sessionDao().getAllSessionsOnce(programId)
  val days = sessions.map { it.dayNumber }.distinct().sorted()
  val n = days.size
  val startIdx = days.indexOf(startDayNumber)
  if (n <= 1 || startIdx <= 0) return@withContext
  // old dayNumber -> new dayNumber (1-based, rotated)
  val mapping = days.withIndex().associate { (i, d) -> d to (((i - startIdx + n) % n) + 1) }
  val rotated = sessions.mapNotNull { s -> mapping[s.dayNumber]?.let { s.copy(dayNumber = it) } }
  db.sessionDao().updateAll(rotated)
  }

  /** Add a new (empty) day to a program — one session per existing week, at the next dayNumber. */
  suspend fun addDayToProgram(programId: Long, name: String) = withContext(Dispatchers.IO) {
  val sessions = db.sessionDao().getAllSessionsOnce(programId)
  if (sessions.isEmpty()) return@withContext
  val nextDay = (sessions.maxOfOrNull { it.dayNumber } ?: 0) + 1
  sessions.groupBy { it.weekNumber }.forEach { (week, weekSessions) ->
  val phase = weekSessions.firstOrNull()?.phaseNumber ?: 1
  db.sessionDao().insert(Session(
  programId = programId, weekNumber = week, dayNumber = nextDay,
  name = name.ifBlank { "Day $nextDay" }, muscleGroups = "", phaseNumber = phase))
  }
  }

  /** Remove a day (across every week) from a program. Cascades to its logs/completions. */
  suspend fun removeDayFromProgram(programId: Long, dayNumber: Int) = withContext(Dispatchers.IO) {
  db.sessionDao().deleteDay(programId, dayNumber)
  }

  /** Rename a day across every week of a program. */
  suspend fun renameDayInProgram(programId: Long, dayNumber: Int, name: String) = withContext(Dispatchers.IO) {
  db.sessionDao().renameDay(programId, dayNumber, name.ifBlank { "Day $dayNumber" })
  }

  suspend fun getSessionSetLogs(sessionId: Long, weekNumber: Int): Map<Long, List<SetLog>> =
  withContext(Dispatchers.IO) {
  val logs = db.workoutLogDao().getLogsForSession(sessionId, weekNumber)
  logs.associate { log -> log.exerciseId to db.setLogDao().getSetLogsForWorkoutLog(log.id) }
  }

  /** Get the current program (one-shot). Used by session view to read isFlexible. */
  suspend fun getCurrentProgram(): Program? = withContext(Dispatchers.IO) {
  db.programDao().getCurrentProgramOnce()
  }

  /**
  * Create an exercise in the given session AND propagate it to all later weeks'
  * sessions at the same dayNumber - so "build as you go" exercises appear in future weeks.
  */
  suspend fun createExerciseAndPropagate(
  name: String, sets: Int, repsTarget: String, notes: String = "",
  sessionId: Long, orderIndex: Int,
  programId: Long, dayNumber: Int, currentWeek: Int
  ) = withContext(Dispatchers.IO) {
  val template = Exercise(sessionId = sessionId, name = name, sets = sets,
  repsTarget = repsTarget, notes = notes, orderIndex = orderIndex)
  db.exerciseDao().insert(template)
  // Also insert into every future week's session at the same day
  val futureSessions = db.sessionDao().getSessionsFromWeek(programId, dayNumber, currentWeek + 1)
  for (s in futureSessions) {
  db.exerciseDao().insert(template.copy(sessionId = s.id))
  }
  }

  /**
  * Restart the program using Week 2's logged weights/reps as the new Week 1 baseline.
  * Clears all logs and completions, then seeds Week 1 workout_logs with Week 2's data
  * so the prefill shows the right weights when the user opens Week 1 again.
  */
  suspend fun restartFromWeek2(programId: Long) = withContext(Dispatchers.IO) {
  data class SetEntry(val dayNumber: Int, val orderIndex: Int,
  val setNumber: Int, val weightKg: Float?, val reps: Int?)

  // Snapshot Week 2 data by position
  val w2Sessions  = db.sessionDao().getSessionsForWeek(2, programId)
  val w1Sessions  = db.sessionDao().getSessionsForWeek(1, programId)
  val collectedSets = mutableListOf<SetEntry>()

  for (w2s in w2Sessions) {
  for (ex in db.exerciseDao().getExercisesForSession(w2s.id)) {
  val log = db.workoutLogDao().getWorkoutLogForExercise(ex.id, 2) ?: continue
  db.setLogDao().getSetLogsForWorkoutLog(log.id).forEach { sl ->
  collectedSets.add(SetEntry(w2s.dayNumber, ex.orderIndex, sl.setNumber, sl.weightKg, sl.reps))
  }
  }
  }

  // Wipe all logs and completions
  db.setLogDao().deleteAll()
  db.workoutLogDao().deleteAll()
  db.sessionCompletionDao().deleteAll()

  // Re-seed Week 1 with the snapshotted Week 2 data
  for (w1s in w1Sessions) {
  val dayEntries = collectedSets.filter { it.dayNumber == w1s.dayNumber }
  for (ex in db.exerciseDao().getExercisesForSession(w1s.id)) {
  val exEntries = dayEntries.filter { it.orderIndex == ex.orderIndex }
  if (exEntries.isEmpty()) continue
  val logId = db.workoutLogDao().insert(
  WorkoutLog(exerciseId = ex.id, sessionId = w1s.id, weekNumber = 1)
  )
  exEntries.forEach { se ->
  db.setLogDao().insert(
  SetLog(workoutLogId = logId, setNumber = se.setNumber,
  weightKg = se.weightKg, reps = se.reps)
  )
  }
  }
  }
  }

  /**
  * Analyse a program - returns sets per week per muscle group.
  * Primary muscles count at 1.0 × sets, secondary at 0.5 × sets.
  * Result is averaged across all weeks that have exercises.
  */
  suspend fun analyseProgram(programId: Long): Map<com.wildodds.gymtracker.data.MuscleGroup, Float> =
  withContext(Dispatchers.IO) {
  // Use Week 1 as the structural template; exercises are propagated so Week 1 = all weeks
  val allW1Sessions = db.sessionDao().getSessionsForWeek(1, programId)
  val totals = mutableMapOf<com.wildodds.gymtracker.data.MuscleGroup, Float>()

  for (session in allW1Sessions) {
  val exercises = db.exerciseDao().getExercisesForSession(session.id)
  for (exercise in exercises) {
  val info = com.wildodds.gymtracker.data.ExerciseLibrary.lookup(exercise.name)
  ?: continue
  val sets = exercise.sets.toFloat()
  info.primary.forEach { muscle ->
  totals[muscle] = (totals[muscle] ?: 0f) + sets
  }
  info.secondary.forEach { muscle ->
  totals[muscle] = (totals[muscle] ?: 0f) + sets * 0.5f
  }
  }
  }
  totals
  }

  /** All set logs for an exercise at a given position, across all weeks. */
  suspend fun getAllWeekSetLogs(
  programId: Long, dayNumber: Int, orderIndex: Int
  ): List<SetLogWithWeek> = withContext(Dispatchers.IO) {
  db.setLogDao().getAllWeekSetLogs(programId, dayNumber, orderIndex)
  }

  // ── Recovery / readiness inputs (Phase 4B) ──────────────────────────────────

  /** The N most-recently-completed sessions — strain/fatigue + schedule inputs for readiness. */
  suspend fun recentCompletions(limit: Int): List<SessionCompletion> = withContext(Dispatchers.IO) {
  db.sessionCompletionDao().getRecentCompletions(limit)
  }

  /** Whether the active program is in a multi-week stall (3B rule), or false if no active program. */
  suspend fun isActiveProgramStalled(): Boolean = withContext(Dispatchers.IO) {
  val program = db.programDao().getCurrentProgramOnce() ?: return@withContext false
  isProgramStalled(program.id)
  }

  /**
   * Program-wide multi-week stall, centralised here so Home re-calibration (3B) and the adaptive
   * plan engine (4B) share one definition. Per-exercise [ProgressionEngine.isStalled] over each
   * day-1 position, reduced by [RecalibrationDetector.isProgramStalled].
   */
  suspend fun isProgramStalled(programId: Long): Boolean = withContext(Dispatchers.IO) {
  val stalls = mutableListOf<Boolean>()
  for (s in db.sessionDao().getSessionsForWeek(1, programId)) {
  for (ex in db.exerciseDao().getExercisesForSession(s.id)) {
  val weeks = db.setLogDao().getAllWeekSetLogs(programId, s.dayNumber, ex.orderIndex)
  .groupBy { it.weekNumber }.toSortedMap()
  .map { (wk, rows) -> com.wildodds.gymtracker.data.intelligence.WeekRecord(
  wk, rows.map { com.wildodds.gymtracker.data.intelligence.SetRecord(it.weightKg, it.reps) }) }
  if (weeks.size >= 4) stalls.add(
  com.wildodds.gymtracker.data.intelligence.ProgressionEngine.isStalled(weeks, 3))
  }
  }
  com.wildodds.gymtracker.data.intelligence.RecalibrationDetector.isProgramStalled(stalls)
  }

  // ── Achievements / gamification (Phase 6A) ──────────────────────────────────

  fun observeAchievements(): Flow<List<Achievement>> = db.achievementDao().observeAll()

  suspend fun unlockedAchievementIds(): Set<String> = withContext(Dispatchers.IO) {
  db.achievementDao().getUnlockedIds().toSet()
  }

  /** Aggregate local data into the metrics the achievement engine evaluates. */
  suspend fun computeAchievementMetrics(usedTravelMode: Boolean): MetricsSnapshot = withContext(Dispatchers.IO) {
  val dao = db.backupDao()
  MetricsCalculator.compute(
  programs = dao.programs(), sessions = dao.sessions(), exercises = dao.exercises(),
  workoutLogs = dao.workoutLogs(), setLogs = dao.setLogs(), completions = dao.completions(),
  habitCompletions = dao.habitCompletions(), usedTravelMode = usedTravelMode,
  today = LocalDate.now(), zone = ZoneId.systemDefault()
  )
  }

  /**
   * Evaluate achievements against current local data and persist any newly-unlocked ones. Idempotent:
   * the engine skips already-unlocked ids and the DAO inserts with IGNORE, so re-running (or re-sync)
   * awards nothing twice. Returns only what unlocked on THIS pass (for a one-line celebration).
   */
  suspend fun evaluateAndUnlockAchievements(usedTravelMode: Boolean, now: Long): List<AchievementUnlock> =
  withContext(Dispatchers.IO) {
  val metrics = computeAchievementMetrics(usedTravelMode)
  val unlocked = db.achievementDao().getUnlockedIds().toSet()
  val newly = AchievementEngine.evaluate(metrics, unlocked, now)
  newly.forEach { db.achievementDao().insert(Achievement(it.definition.id, it.unlockedAt)) }
  newly
  }

  /** Aggregate local cadence + volume into dropout-risk features (Phase 7A). */
  suspend fun computeDropoutFeatures(): com.wildodds.gymtracker.data.retention.DropoutFeatures =
  withContext(Dispatchers.IO) {
  val dao = db.backupDao()
  val sessionTimestamps = dao.completions().map { it.completedAt }
  val logById = dao.workoutLogs().associateBy { it.id }
  val volumeEvents = dao.setLogs().mapNotNull { s ->
  val log = logById[s.workoutLogId] ?: return@mapNotNull null
  log.completedAt to ((s.weightKg ?: 0f) * (s.reps ?: 0)).toDouble()
  }
  com.wildodds.gymtracker.data.retention.DropoutFeatureCalculator.compute(
  sessionTimestamps, volumeEvents, LocalDate.now(), ZoneId.systemDefault())
  }

  /** Read a local program's full structure into a [ParsedProgram] (for sharing — Phase 6B). */
  suspend fun exportProgramToParsed(programId: Long): ParsedProgram? = withContext(Dispatchers.IO) {
  val program = db.programDao().getAllProgramsOnce().firstOrNull { it.id == programId } ?: return@withContext null
  val parsedSessions = db.sessionDao().getAllSessionsOnce(programId).map { s ->
  val ex = db.exerciseDao().getExercisesForSession(s.id)
  com.wildodds.gymtracker.data.parser.ParsedSession(
  weekNumber = s.weekNumber, dayNumber = s.dayNumber, name = s.name, muscleGroups = s.muscleGroups,
  exercises = ex.map {
  com.wildodds.gymtracker.data.parser.ParsedExercise(
  it.name, it.sets, it.repsTarget, it.notes, it.orderIndex, it.rpeTarget, it.pct1rmTarget)
  },
  phaseNumber = s.phaseNumber
  )
  }
  ParsedProgram(
  name = program.name, totalWeeks = program.totalWeeks, sessions = parsedSessions,
  coverImage = program.coverImage, isFlexible = program.isFlexible, category = program.category,
  isUserCreated = true, trackRpe = program.trackRpe, trackOneRm = program.trackOneRm
  )
  }

  /**
   * Aggregate trend analytics for a program (volume, PRs, consistency, balance) entirely on IO,
   * then hand the flat rows to the pure [com.wildodds.gymtracker.data.analytics.TrendAggregator].
   */
  suspend fun analyseTrends(
  programId: Long,
  range: com.wildodds.gymtracker.data.analytics.DateRange? = null
  ): com.wildodds.gymtracker.data.analytics.TrendData = withContext(Dispatchers.IO) {
  val setRows = db.setLogDao().getTrendSetRows(programId)
  val completions = db.sessionCompletionDao().getCompletionsForProgram(programId)
  .map { it.weekNumber to it.completedAt }
  val planned = db.sessionDao().getSessionCountsPerWeek(programId).associate { it.week to it.count }
  com.wildodds.gymtracker.data.analytics.TrendAggregator.aggregate(
  setRows = setRows,
  completions = completions,
  plannedPerWeek = planned,
  muscleLookup = { com.wildodds.gymtracker.data.ExerciseLibrary.lookup(it) },
  range = range
  )
  }

  // ── On-demand / one-off sessions (Phase 3H) ─────────────────────────────────

  /** Weekly sets-per-muscle for the active block (reused for on-demand recommendations). */
  suspend fun weeklySetsByMuscle(programId: Long): Map<com.wildodds.gymtracker.data.MuscleGroup, Float> =
  analyseProgram(programId)

  private suspend fun getOrCreateOnDemandProgramId(): Long {
  db.programDao().getAllProgramsOnce().firstOrNull { it.name == ON_DEMAND_PROGRAM_NAME }?.let { return it.id }
  return db.programDao().insert(Program(
  name = ON_DEMAND_PROGRAM_NAME, totalWeeks = 1, isUserCreated = true, isActive = false, isFlexible = true))
  }

  /**
   * Materialise a one-off session under the hidden on-demand program and return its sessionId to
   * navigate to. NON-DESTRUCTIVE: it only ever writes to the on-demand program, so the active
   * program's week structure is never touched. Logging then works through the normal session flow.
   */
  suspend fun startOnDemandSession(
  title: String, muscleGroups: String,
  exercises: List<Triple<String, Int, String>>   // (name, sets, repsTarget)
  ): Long = withContext(Dispatchers.IO) {
  val programId = getOrCreateOnDemandProgramId()
  val nextDay = (db.sessionDao().getAllSessionsOnce(programId).maxOfOrNull { it.dayNumber } ?: 0) + 1
  db.sessionDao().insertAll(listOf(Session(
  programId = programId, weekNumber = 1, dayNumber = nextDay, name = title, muscleGroups = muscleGroups)))
  val session = db.sessionDao().getSessionsForWeek(1, programId).first { it.dayNumber == nextDay }
  db.exerciseDao().insertAll(exercises.mapIndexed { i, (name, sets, reps) ->
  Exercise(sessionId = session.id, name = name, sets = sets, repsTarget = reps, orderIndex = i)
  })
  session.id
  }

  suspend fun clearAllData() = withContext(Dispatchers.IO) {
  db.setLogDao().deleteAll()
  db.workoutLogDao().deleteAll()
  db.sessionCompletionDao().deleteAll()
  db.exerciseDao().deleteAll()
  db.programDao().deleteAll()
  }

  // ── Phases ──────────────────────────────────────────────────────────────────

  fun programPhases(programId: Long): Flow<List<ProgramPhase>> =
  db.programPhaseDao().getPhases(programId)

  suspend fun getPhasesOnce(programId: Long): List<ProgramPhase> = withContext(Dispatchers.IO) {
  db.programPhaseDao().getPhasesOnce(programId)
  }

  /** Change which phase of the active program is currently displayed. */
  suspend fun setCurrentPhase(programId: Long, phase: Int) = withContext(Dispatchers.IO) {
  db.programDao().setCurrentPhase(programId, phase)
  }

  /**
   * Start a program from the library at a specific phase. Pauses any active program,
   * activates the target, and sets its current phase (week numbers stay global so the
   * Home screen simply opens on that phase's first week).
   */
  suspend fun startProgramAtPhase(programId: Long, phase: Int) = withContext(Dispatchers.IO) {
  db.programDao().pauseActiveProgram()
  db.programDao().setPaused(programId, false)
  db.programDao().setCurrentPhase(programId, phase)
  db.programDao().setActiveById(programId)
  }

  // ── Session Library ─────────────────────────────────────────────────────────

  fun librarySessions(): Flow<List<SessionTemplate>> = db.sessionTemplateDao().getAllTemplates()

  fun librarySessionExercises(templateId: Long): Flow<List<SessionTemplateExercise>> =
  db.sessionTemplateDao().getExercisesForTemplate(templateId)

  suspend fun getLibrarySessionExercisesOnce(templateId: Long): List<SessionTemplateExercise> =
  withContext(Dispatchers.IO) { db.sessionTemplateDao().getExercisesForTemplateOnce(templateId) }

  /** Save (create) a session in the library from a list of exercises. Returns the new id. */
  suspend fun saveSessionTemplate(
  name: String, muscleGroups: String, notes: String,
  exercises: List<SessionTemplateExercise>, source: String = "Custom"
  ): Long = withContext(Dispatchers.IO) {
  val id = db.sessionTemplateDao().insertTemplate(
  SessionTemplate(name = name.ifBlank { "Untitled Session" },
  muscleGroups = muscleGroups, notes = notes, source = source)
  )
  if (exercises.isNotEmpty()) {
  db.sessionTemplateDao().insertExercises(exercises.map { it.copy(id = 0, templateId = id) })
  }
  id
  }

  suspend fun updateSessionTemplate(
  template: SessionTemplate, exercises: List<SessionTemplateExercise>
  ) = withContext(Dispatchers.IO) {
  db.sessionTemplateDao().updateTemplate(template)
  db.sessionTemplateDao().deleteExercisesForTemplate(template.id)
  if (exercises.isNotEmpty()) {
  db.sessionTemplateDao().insertExercises(exercises.map { it.copy(id = 0, templateId = template.id) })
  }
  }

  suspend fun deleteSessionTemplate(id: Long) = withContext(Dispatchers.IO) {
  db.sessionTemplateDao().deleteTemplate(id)
  }

  /** Signature used to de-duplicate library sessions: name + ordered exercise names. */
  private fun signatureOf(name: String, exerciseNames: List<String>): String =
  (name.trim().lowercase() + "|" + exerciseNames.joinToString(",") { it.trim().lowercase() })

  /**
   * Turn every distinct day in every program into a library session. Idempotent —
   * only adds days not already represented (by name + exercise list). Uses each
   * program's earliest week as the structural template (exercises are propagated, so
   * week 1 == all weeks). Returns the number of new sessions added.
   */
  suspend fun syncLibraryFromPrograms(): Int = withContext(Dispatchers.IO) {
  val existing = db.sessionTemplateDao().getAllTemplatesOnce().map { tmpl ->
  signatureOf(tmpl.name, db.sessionTemplateDao().getExercisesForTemplateOnce(tmpl.id).map { it.name })
  }.toMutableSet()

  var added = 0
  val programs = db.programDao().getAllProgramsOnce()
  for (program in programs) {
  val all = db.sessionDao().getAllSessionsOnce(program.id)
  if (all.isEmpty()) continue
  // Earliest week present (handles phased programs whose first week may be > 1 after edits)
  val firstWeek = all.minOf { it.weekNumber }
  val daySessions = all.filter { it.weekNumber == firstWeek }.sortedBy { it.dayNumber }
  for (session in daySessions) {
  val exercises = db.exerciseDao().getExercisesForSession(session.id)
  if (exercises.isEmpty()) continue
  val sig = signatureOf(session.name, exercises.map { it.name })
  if (sig in existing) continue
  existing.add(sig)
  val templateId = db.sessionTemplateDao().insertTemplate(
  SessionTemplate(name = session.name, muscleGroups = session.muscleGroups,
  source = program.name)
  )
  db.sessionTemplateDao().insertExercises(exercises.mapIndexed { idx, ex ->
  SessionTemplateExercise(templateId = templateId, name = ex.name, sets = ex.sets,
  repsTarget = ex.repsTarget, notes = ex.notes, orderIndex = idx,
  rpeTarget = ex.rpeTarget, pct1rmTarget = ex.pct1rmTarget)
  })
  added++
  }
  }
  added
  }
}

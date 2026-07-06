package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.SetLog

/** Flat projection used for cross-week progression queries. */
data class SetLogWithWeek(
  val weekNumber: Int,
  val setNumber: Int,
  val weightKg: Float?,
  val reps: Int?
)

@Dao
interface SetLogDao {
  @Query("SELECT * FROM set_logs WHERE workoutLogId = :workoutLogId ORDER BY setNumber")
  suspend fun getSetLogsForWorkoutLog(workoutLogId: Long): List<SetLog>

  // Position-based cross-week query - finds the most recently completed week (< currentWeek)
  // that has actual set data at the given day/orderIndex position.
  @Query("""
  SELECT sl.* FROM set_logs sl
  INNER JOIN workout_logs wl ON sl.workoutLogId = wl.id
  INNER JOIN exercises e  ON wl.exerciseId  = e.id
  INNER JOIN sessions  s  ON e.sessionId  = s.id
  WHERE s.programId  = :programId
  AND s.dayNumber  = :dayNumber
  AND e.orderIndex = :orderIndex
  AND s.weekNumber = (
  SELECT MAX(s2.weekNumber)
  FROM sessions s2
  INNER JOIN exercises e2  ON e2.sessionId  = s2.id
  INNER JOIN workout_logs wl2 ON wl2.exerciseId  = e2.id
  INNER JOIN set_logs sl2  ON sl2.workoutLogId = wl2.id
  WHERE s2.programId  = :programId
  AND s2.dayNumber  = :dayNumber
  AND e2.orderIndex = :orderIndex
  AND s2.weekNumber < :currentWeek
  )
  ORDER BY sl.setNumber
  """)
  suspend fun getPrevWeekSetLogsByPosition(
  programId: Long,
  dayNumber: Int,
  currentWeek: Int,
  orderIndex: Int
  ): List<SetLog>

  // Update existing row; returns affected row count
  @Query("UPDATE set_logs SET weightKg = :weightKg, reps = :reps, rpe = :rpe, pct1rm = :pct1rm, weightRightKg = :weightRightKg, repsRight = :repsRight WHERE workoutLogId = :workoutLogId AND setNumber = :setNumber")
  suspend fun update(workoutLogId: Long, setNumber: Int, weightKg: Float?, reps: Int?, rpe: Float? = null, pct1rm: Float? = null, weightRightKg: Float? = null, repsRight: Int? = null): Int

  @Insert
  suspend fun insert(setLog: SetLog)

  @Query("DELETE FROM set_logs WHERE workoutLogId = :workoutLogId AND setNumber = :setNumber")
  suspend fun deleteSetLog(workoutLogId: Long, setNumber: Int)

  /** All set logs for an exercise position across every week - for progression charting. */
  @Query("""
  SELECT s.weekNumber, sl.setNumber, sl.weightKg, sl.reps
  FROM set_logs sl
  INNER JOIN workout_logs wl ON sl.workoutLogId = wl.id
  INNER JOIN exercises  e  ON wl.exerciseId  = e.id
  INNER JOIN sessions  s  ON e.sessionId  = s.id
  WHERE s.programId  = :programId
  AND s.dayNumber  = :dayNumber
  AND e.orderIndex = :orderIndex
  ORDER BY s.weekNumber, sl.setNumber
  """)
  suspend fun getAllWeekSetLogs(
  programId: Long, dayNumber: Int, orderIndex: Int
  ): List<SetLogWithWeek>

  @Query("""DELETE FROM set_logs WHERE workoutLogId IN
    (SELECT wl.id FROM workout_logs wl
     INNER JOIN sessions s ON wl.sessionId = s.id
     WHERE s.programId = :programId)""")
  suspend fun deleteForProgram(programId: Long)

  /** Wipe logged sets for one day of one week (used by "reset day"). */
  @Query("""DELETE FROM set_logs WHERE workoutLogId IN
    (SELECT id FROM workout_logs WHERE sessionId = :sessionId AND weekNumber = :weekNumber)""")
  suspend fun deleteForSessionWeek(sessionId: Long, weekNumber: Int)

  /** Flat join of every logged set in a program with its week, exercise and completion time. */
  @Query("""
  SELECT s.weekNumber AS week, e.name AS exerciseName, e.orderIndex AS orderIndex,
  sl.weightKg AS weightKg, sl.reps AS reps, wl.completedAt AS completedAt
  FROM set_logs sl
  INNER JOIN workout_logs wl ON sl.workoutLogId = wl.id
  INNER JOIN exercises e  ON wl.exerciseId  = e.id
  INNER JOIN sessions  s  ON e.sessionId  = s.id
  WHERE s.programId = :programId
  ORDER BY s.weekNumber, e.orderIndex, sl.setNumber
  """)
  suspend fun getTrendSetRows(programId: Long): List<com.wildodds.gymtracker.data.analytics.TrendSetRow>

  /** Every logged set across all programs, with its exercise name — for profile 1RM estimates. */
  @Query("""
  SELECT e.name AS exerciseName, sl.weightKg AS weightKg, sl.reps AS reps
  FROM set_logs sl
  INNER JOIN workout_logs wl ON sl.workoutLogId = wl.id
  INNER JOIN exercises e  ON wl.exerciseId  = e.id
  WHERE sl.weightKg IS NOT NULL AND sl.reps IS NOT NULL
  """)
  suspend fun getAllLiftSetRows(): List<com.wildodds.gymtracker.data.profile.LiftSetRow>

  /** Per-exercise count of performed sets since [since] — drives the profile "favourite muscle". */
  @Query("""
  SELECT e.name AS exerciseName, COUNT(*) AS setCount
  FROM set_logs sl
  INNER JOIN workout_logs wl ON sl.workoutLogId = wl.id
  INNER JOIN exercises e  ON wl.exerciseId  = e.id
  WHERE wl.completedAt >= :since AND sl.reps IS NOT NULL
  GROUP BY e.name
  """)
  suspend fun getExerciseSetCountsSince(since: Long): List<com.wildodds.gymtracker.data.profile.ExerciseSetCount>

  @Query("DELETE FROM set_logs")
  suspend fun deleteAll()
}

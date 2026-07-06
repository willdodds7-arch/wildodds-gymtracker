package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.WorkoutLog

@Dao
interface WorkoutLogDao {
  @Query("SELECT * FROM workout_logs WHERE exerciseId = :exerciseId AND weekNumber = :weekNumber LIMIT 1")
  suspend fun getWorkoutLogForExercise(exerciseId: Long, weekNumber: Int): WorkoutLog?

  @Query("SELECT * FROM workout_logs WHERE sessionId = :sessionId AND weekNumber = :weekNumber")
  suspend fun getLogsForSession(sessionId: Long, weekNumber: Int): List<WorkoutLog>

  // Position-based cross-week query - same fix as SetLogDao above.
  @Query("""
  SELECT wl.progressionChoice FROM workout_logs wl
  INNER JOIN exercises e ON wl.exerciseId = e.id
  INNER JOIN sessions  s ON e.sessionId  = s.id
  WHERE s.programId  = :programId
  AND s.dayNumber  = :dayNumber
  AND s.weekNumber = :prevWeek
  AND e.orderIndex = :orderIndex
  LIMIT 1
  """)
  suspend fun getPrevWeekProgressionByPosition(
  programId: Long,
  dayNumber: Int,
  prevWeek: Int,
  orderIndex: Int
  ): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(log: WorkoutLog): Long

  // choice is nullable so a user can CLEAR (deselect) their progression pick, not just set it.
  @Query("UPDATE workout_logs SET progressionChoice = :choice WHERE id = :workoutLogId")
  suspend fun updateProgressionChoice(workoutLogId: Long, choice: String?)

  @Query("DELETE FROM workout_logs WHERE sessionId IN (SELECT id FROM sessions WHERE programId = :programId)")
  suspend fun deleteForProgram(programId: Long)

  /** Remove workout logs for one day of one week (used by "reset day"). */
  @Query("DELETE FROM workout_logs WHERE sessionId = :sessionId AND weekNumber = :weekNumber")
  suspend fun deleteForSessionWeek(sessionId: Long, weekNumber: Int)

  @Query("DELETE FROM workout_logs")
  suspend fun deleteAll()
}

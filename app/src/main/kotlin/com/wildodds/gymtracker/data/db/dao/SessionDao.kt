package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.Session
import kotlinx.coroutines.flow.Flow

/** Planned sessions in a given week of a program. */
data class WeekSessionCount(val week: Int, val count: Int)

@Dao
interface SessionDao {
  @Query("SELECT * FROM sessions WHERE programId = :programId ORDER BY weekNumber, dayNumber")
  fun getAllSessions(programId: Long): Flow<List<Session>>

  @Query("SELECT * FROM sessions WHERE programId = :programId ORDER BY weekNumber, dayNumber")
  suspend fun getAllSessionsOnce(programId: Long): List<Session>

  @Query("SELECT * FROM sessions WHERE weekNumber = :weekNumber AND programId = :programId ORDER BY dayNumber")
  suspend fun getSessionsForWeek(weekNumber: Int, programId: Long): List<Session>

  @Query("SELECT * FROM sessions WHERE id = :sessionId")
  suspend fun getSessionById(sessionId: Long): Session?

  // All sessions for a day from a given week onwards (used for exercise propagation)
  @Query("SELECT * FROM sessions WHERE programId = :programId AND dayNumber = :dayNumber AND weekNumber >= :fromWeek ORDER BY weekNumber")
  suspend fun getSessionsFromWeek(programId: Long, dayNumber: Int, fromWeek: Int): List<Session>

  // Count of total sessions and completions for a program - used for program-complete detection
  @Query("SELECT COUNT(*) FROM sessions WHERE programId = :programId")
  suspend fun countForProgram(programId: Long): Int

  /** Planned session count per week (for consistency trends). */
  @Query("SELECT weekNumber AS week, COUNT(*) AS count FROM sessions WHERE programId = :programId GROUP BY weekNumber")
  suspend fun getSessionCountsPerWeek(programId: Long): List<com.wildodds.gymtracker.data.db.dao.WeekSessionCount>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(sessions: List<Session>)

  @Insert
  suspend fun insert(session: Session): Long

  /** Update existing session rows by PK (no delete/cascade — safe for rotating dayNumbers). */
  @Update
  suspend fun updateAll(sessions: List<Session>)

  @Query("DELETE FROM sessions WHERE programId = :programId")
  suspend fun deleteForProgram(programId: Long)

  /** Remove one "day" (across every week) from a program. Cascades to logs/completions. */
  @Query("DELETE FROM sessions WHERE programId = :programId AND dayNumber = :dayNumber")
  suspend fun deleteDay(programId: Long, dayNumber: Int)

  /** Rename one "day" across every week of a program. */
  @Query("UPDATE sessions SET name = :name WHERE programId = :programId AND dayNumber = :dayNumber")
  suspend fun renameDay(programId: Long, dayNumber: Int, name: String)
}

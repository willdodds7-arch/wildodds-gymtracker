package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.Session
import kotlinx.coroutines.flow.Flow

/** Planned sessions in a given week of a program. */
data class WeekSessionCount(val week: Int, val count: Int)

/** How many distinct training days one program has, from its week 1. */
data class ProgramDayCount(val programId: Long, val dayCount: Int)

@Dao
interface SessionDao {
  /**
   * Every query below that does NOT say `IncludingRest` filters to training sessions only.
   *
   * Since v26 a program can contain rest days, stored as sessions with `sessionType = 'REST'` (or
   * ACTIVE_RECOVERY / DELOAD_NOTE) and no exercises, so that "Wed - off" survives import instead of
   * being silently dropped. Every pre-existing caller means "the days you train" — home screen day
   * list, week progress, day counts, propagation, program-complete detection — so the filter lives
   * here rather than at 20 call sites, and those callers keep behaving exactly as they did.
   */
  @Query("SELECT * FROM sessions WHERE programId = :programId AND sessionType = 'TRAINING' ORDER BY weekNumber, dayNumber")
  fun getAllSessions(programId: Long): Flow<List<Session>>

  @Query("SELECT * FROM sessions WHERE programId = :programId AND sessionType = 'TRAINING' ORDER BY weekNumber, dayNumber")
  suspend fun getAllSessionsOnce(programId: Long): List<Session>

  @Query("SELECT * FROM sessions WHERE weekNumber = :weekNumber AND programId = :programId AND sessionType = 'TRAINING' ORDER BY dayNumber")
  suspend fun getSessionsForWeek(weekNumber: Int, programId: Long): List<Session>

  /** Rest days included, in day order — the plan view and the builder need the real shape of a week. */
  @Query("SELECT * FROM sessions WHERE weekNumber = :weekNumber AND programId = :programId ORDER BY dayNumber")
  suspend fun getSessionsForWeekIncludingRest(weekNumber: Int, programId: Long): List<Session>

  /**
   * Distinct week-1 day count for EVERY program in one query. The Library shows a "N days" badge
   * per card; doing that with one `getSessionsForWeek` call per program was a round-trip per row
   * (and materialised every Session object just to count distinct dayNumbers).
   */
  @Query("""SELECT programId, COUNT(DISTINCT dayNumber) AS dayCount FROM sessions
    WHERE weekNumber = 1 AND sessionType = 'TRAINING' GROUP BY programId""")
  suspend fun getDayCountsForAllPrograms(): List<ProgramDayCount>

  @Query("SELECT * FROM sessions WHERE id = :sessionId")
  suspend fun getSessionById(sessionId: Long): Session?

  // All sessions for a day from a given week onwards (used for exercise propagation)
  @Query("SELECT * FROM sessions WHERE programId = :programId AND dayNumber = :dayNumber AND weekNumber >= :fromWeek AND sessionType = 'TRAINING' ORDER BY weekNumber")
  suspend fun getSessionsFromWeek(programId: Long, dayNumber: Int, fromWeek: Int): List<Session>

  // Count of total sessions and completions for a program - used for program-complete detection
  @Query("SELECT COUNT(*) FROM sessions WHERE programId = :programId AND sessionType = 'TRAINING'")
  suspend fun countForProgram(programId: Long): Int

  /** Planned session count per week (for consistency trends). */
  @Query("SELECT weekNumber AS week, COUNT(*) AS count FROM sessions WHERE programId = :programId AND sessionType = 'TRAINING' GROUP BY weekNumber")
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

package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionCompletionDao {
  @Query("SELECT COUNT(*) > 0 FROM session_completions WHERE sessionId = :sessionId AND weekNumber = :weekNumber")
  suspend fun isSessionCompleted(sessionId: Long, weekNumber: Int): Boolean

  @Query("SELECT * FROM session_completions ORDER BY completedAt DESC")
  fun getAllCompletions(): Flow<List<SessionCompletion>>

  /** The N most-recently-completed sessions (one-shot) — drives recovery/readiness (Phase 4B). */
  @Query("SELECT * FROM session_completions ORDER BY completedAt DESC LIMIT :limit")
  suspend fun getRecentCompletions(limit: Int): List<SessionCompletion>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(completion: SessionCompletion)

  @Query("SELECT COUNT(*) FROM session_completions WHERE weekNumber = :weekNumber")
  suspend fun getCompletedCountForWeek(weekNumber: Int): Int

  @Query("SELECT * FROM session_completions WHERE sessionId = :sessionId AND weekNumber = :weekNumber LIMIT 1")
  suspend fun getCompletion(sessionId: Long, weekNumber: Int): SessionCompletion?

  /** Update just the strain rating on an existing completion (set from the summary sheet). */
  @Query("UPDATE session_completions SET strainRating = :strain WHERE sessionId = :sessionId AND weekNumber = :weekNumber")
  suspend fun updateStrain(sessionId: Long, weekNumber: Int, strain: Int?)

  @Query("DELETE FROM session_completions WHERE sessionId IN (SELECT id FROM sessions WHERE programId = :programId)")
  suspend fun deleteForProgram(programId: Long)

  /** Completions for a program (for consistency trends). */
  @Query("""SELECT * FROM session_completions
    WHERE sessionId IN (SELECT id FROM sessions WHERE programId = :programId)""")
  suspend fun getCompletionsForProgram(programId: Long): List<SessionCompletion>

  /** Clear the "done" mark for one day of one week (used by "reset day"). */
  @Query("DELETE FROM session_completions WHERE sessionId = :sessionId AND weekNumber = :weekNumber")
  suspend fun deleteCompletion(sessionId: Long, weekNumber: Int)

  /** Completed-session counts per split since [since] — drives the profile "favourite split". */
  @Query("""
  SELECT p.split AS split, COUNT(*) AS count
  FROM session_completions sc
  INNER JOIN sessions s ON sc.sessionId = s.id
  INNER JOIN programs  p ON s.programId = p.id
  WHERE sc.completedAt >= :since AND p.split != ''
  GROUP BY p.split
  """)
  suspend fun getSplitCountsSince(since: Long): List<com.wildodds.gymtracker.data.profile.SplitCount>

  @Query("DELETE FROM session_completions")
  suspend fun deleteAll()
}

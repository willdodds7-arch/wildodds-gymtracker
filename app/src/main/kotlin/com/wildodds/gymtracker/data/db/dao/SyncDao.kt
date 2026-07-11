package com.wildodds.gymtracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.ProgramPhase
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.db.entity.SyncTombstone
import com.wildodds.gymtracker.data.db.entity.WorkoutLog

/**
 * Everything the sync engine needs: changed-since reads for push, syncId lookups + upsert
 * halves for pull-apply, and syncId-keyed deletes for applying remote tombstones (local
 * ON DELETE CASCADE then removes children, whose own remote tombstones make that idempotent).
 */
@Dao
interface SyncDao {

  // ── Changed-since reads (push) ──────────────────────────────────────────────
  @Query("SELECT * FROM programs WHERE updatedAt > :since") suspend fun programsSince(since: Long): List<Program>
  @Query("SELECT * FROM program_phases WHERE updatedAt > :since") suspend fun programPhasesSince(since: Long): List<ProgramPhase>
  @Query("SELECT * FROM sessions WHERE updatedAt > :since") suspend fun sessionsSince(since: Long): List<Session>
  @Query("SELECT * FROM exercises WHERE updatedAt > :since") suspend fun exercisesSince(since: Long): List<Exercise>
  @Query("SELECT * FROM workout_logs WHERE updatedAt > :since") suspend fun workoutLogsSince(since: Long): List<WorkoutLog>
  @Query("SELECT * FROM set_logs WHERE updatedAt > :since") suspend fun setLogsSince(since: Long): List<SetLog>
  @Query("SELECT * FROM session_completions WHERE updatedAt > :since") suspend fun completionsSince(since: Long): List<SessionCompletion>

  // ── syncId lookups (pull-apply + FK resolution) ─────────────────────────────
  @Query("SELECT * FROM programs WHERE syncId = :syncId LIMIT 1") suspend fun programBySyncId(syncId: String): Program?
  @Query("SELECT * FROM program_phases WHERE syncId = :syncId LIMIT 1") suspend fun programPhaseBySyncId(syncId: String): ProgramPhase?
  @Query("SELECT * FROM sessions WHERE syncId = :syncId LIMIT 1") suspend fun sessionBySyncId(syncId: String): Session?
  @Query("SELECT * FROM exercises WHERE syncId = :syncId LIMIT 1") suspend fun exerciseBySyncId(syncId: String): Exercise?
  @Query("SELECT * FROM workout_logs WHERE syncId = :syncId LIMIT 1") suspend fun workoutLogBySyncId(syncId: String): WorkoutLog?
  @Query("SELECT * FROM set_logs WHERE syncId = :syncId LIMIT 1") suspend fun setLogBySyncId(syncId: String): SetLog?
  @Query("SELECT * FROM session_completions WHERE syncId = :syncId LIMIT 1") suspend fun completionBySyncId(syncId: String): SessionCompletion?

  // ── Inserts / updates (pull-apply) ──────────────────────────────────────────
  @Insert suspend fun insertProgram(row: Program): Long
  @Insert suspend fun insertProgramPhase(row: ProgramPhase): Long
  @Insert suspend fun insertSession(row: Session): Long
  @Insert suspend fun insertExercise(row: Exercise): Long
  @Insert suspend fun insertWorkoutLog(row: WorkoutLog): Long
  @Insert suspend fun insertSetLog(row: SetLog): Long
  @Insert suspend fun insertCompletion(row: SessionCompletion): Long

  @Update suspend fun updateProgram(row: Program)
  @Update suspend fun updateProgramPhase(row: ProgramPhase)
  @Update suspend fun updateSession(row: Session)
  @Update suspend fun updateExercise(row: Exercise)
  @Update suspend fun updateWorkoutLog(row: WorkoutLog)
  @Update suspend fun updateSetLog(row: SetLog)
  @Update suspend fun updateCompletion(row: SessionCompletion)

  // ── Remote-tombstone application ────────────────────────────────────────────
  @Query("DELETE FROM programs WHERE syncId = :syncId") suspend fun deleteProgramBySyncId(syncId: String)
  @Query("DELETE FROM program_phases WHERE syncId = :syncId") suspend fun deleteProgramPhaseBySyncId(syncId: String)
  @Query("DELETE FROM sessions WHERE syncId = :syncId") suspend fun deleteSessionBySyncId(syncId: String)
  @Query("DELETE FROM exercises WHERE syncId = :syncId") suspend fun deleteExerciseBySyncId(syncId: String)
  @Query("DELETE FROM workout_logs WHERE syncId = :syncId") suspend fun deleteWorkoutLogBySyncId(syncId: String)
  @Query("DELETE FROM set_logs WHERE syncId = :syncId") suspend fun deleteSetLogBySyncId(syncId: String)
  @Query("DELETE FROM session_completions WHERE syncId = :syncId") suspend fun deleteCompletionBySyncId(syncId: String)

  // ── Local tombstones (push) ─────────────────────────────────────────────────
  @Query("SELECT * FROM sync_tombstones WHERE deletedAt > :since") suspend fun tombstonesSince(since: Long): List<SyncTombstone>
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTombstone(row: SyncTombstone)
  @Query("DELETE FROM sync_tombstones WHERE syncId = :syncId AND entityType = :entityType")
  suspend fun clearTombstone(entityType: String, syncId: String)
}

package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.ProgramPhase
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgramPhaseDao {
  @Query("SELECT * FROM program_phases WHERE programId = :programId ORDER BY phaseNumber")
  fun getPhases(programId: Long): Flow<List<ProgramPhase>>

  @Query("SELECT * FROM program_phases WHERE programId = :programId ORDER BY phaseNumber")
  suspend fun getPhasesOnce(programId: Long): List<ProgramPhase>

  @Query("SELECT COUNT(*) FROM program_phases WHERE programId = :programId")
  suspend fun countForProgram(programId: Long): Int

  @Insert
  suspend fun insert(phase: ProgramPhase): Long

  @Insert
  suspend fun insertAll(phases: List<ProgramPhase>)

  @Query("DELETE FROM program_phases WHERE programId = :programId")
  suspend fun deleteForProgram(programId: Long)
}

package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.Program
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgramDao {
  @Query("SELECT * FROM programs WHERE isActive = 1 LIMIT 1")
  fun getCurrentProgram(): Flow<Program?>

  @Query("SELECT * FROM programs WHERE isActive = 1 LIMIT 1")
  suspend fun getCurrentProgramOnce(): Program?

  @Query("SELECT * FROM programs ORDER BY createdAt DESC")
  fun getAllPrograms(): Flow<List<Program>>

  @Query("SELECT * FROM programs ORDER BY createdAt DESC")
  suspend fun getAllProgramsOnce(): List<Program>

  @Query("UPDATE programs SET currentPhase = :phase WHERE id = :id")
  suspend fun setCurrentPhase(id: Long, phase: Int)

  @Query("SELECT * FROM programs WHERE isUserCreated = 1 ORDER BY createdAt DESC")
  fun getUserPrograms(): Flow<List<Program>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(program: Program): Long

  @Query("UPDATE programs SET isActive = 0")
  suspend fun deactivateAll()

  @Query("UPDATE programs SET isActive = 1 WHERE id = :id")
  suspend fun setActiveById(id: Long)

  @Query("DELETE FROM programs WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("SELECT * FROM programs WHERE isPaused = 1 ORDER BY createdAt DESC")
  fun getPausedPrograms(): Flow<List<Program>>

  @Query("UPDATE programs SET isPaused = :paused WHERE id = :id")
  suspend fun setPaused(id: Long, paused: Boolean)

  @Query("UPDATE programs SET isActive = 0, isPaused = 1 WHERE isActive = 1")
  suspend fun pauseActiveProgram()

  @Query("DELETE FROM programs")
  suspend fun deleteAll()
}

package com.wildodds.gymtracker.data.db.dao

import androidx.room.*
import com.wildodds.gymtracker.data.db.entity.Exercise

@Dao
interface ExerciseDao {
  @Query("SELECT * FROM exercises WHERE sessionId = :sessionId ORDER BY orderIndex")
  suspend fun getExercisesForSession(sessionId: Long): List<Exercise>

  /** Read-only stream of every exercise in a program, used by the Home weekly view. */
  @Query("""
    SELECT * FROM exercises
    WHERE sessionId IN (SELECT id FROM sessions WHERE programId = :programId)
    ORDER BY sessionId, orderIndex
  """)
  fun observeExercisesForProgram(programId: Long): kotlinx.coroutines.flow.Flow<List<Exercise>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(exercise: Exercise): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(exercises: List<Exercise>)

  @Query("UPDATE exercises SET name = :name WHERE id = :exerciseId")
  suspend fun updateName(exerciseId: Long, name: String)

  // Propagate name change to all future weeks (same day + orderIndex position).
  @Query("""
  UPDATE exercises SET name = :name
  WHERE orderIndex = :orderIndex
  AND sessionId IN (
  SELECT id FROM sessions
  WHERE dayNumber  = :dayNumber
  AND programId  = :programId
  AND weekNumber > :currentWeek
  )
  """)
  suspend fun updateNameForFutureWeeks(
  name: String, orderIndex: Int, dayNumber: Int, programId: Long, currentWeek: Int
  )

  @Query("UPDATE exercises SET sets = :sets WHERE id = :exerciseId")
  suspend fun updateSets(exerciseId: Long, sets: Int)

  // Propagate sets count change to future weeks so they pre-show the right number of rows.
  @Query("""
  UPDATE exercises SET sets = :sets
  WHERE orderIndex = :orderIndex
  AND sessionId IN (
  SELECT id FROM sessions
  WHERE dayNumber  = :dayNumber
  AND programId  = :programId
  AND weekNumber > :currentWeek
  )
  """)
  suspend fun updateSetsForFutureWeeks(
  sets: Int, orderIndex: Int, dayNumber: Int, programId: Long, currentWeek: Int
  )

  @Query("UPDATE exercises SET repsTarget = :repsTarget WHERE id = :exerciseId")
  suspend fun updateRepsTarget(exerciseId: Long, repsTarget: String)

  // Propagate reps change to all weeks (same day + orderIndex position).
  @Query("""
  UPDATE exercises SET repsTarget = :repsTarget
  WHERE orderIndex = :orderIndex
  AND sessionId IN (
  SELECT id FROM sessions
  WHERE dayNumber  = :dayNumber
  AND programId  = :programId
  )
  """)
  suspend fun updateRepsTargetForAllWeeks(
  repsTarget: String, orderIndex: Int, dayNumber: Int, programId: Long
  )

  @Query("UPDATE exercises SET supersetGroupId = :groupId WHERE id = :exerciseId")
  suspend fun setSupersetGroupId(exerciseId: Long, groupId: Int?)

  @Query("""
    UPDATE exercises SET supersetGroupId = :groupId
    WHERE orderIndex = :orderIndex
    AND sessionId IN (
      SELECT id FROM sessions WHERE dayNumber = :dayNumber AND programId = :programId
    )
  """)
  suspend fun setSupersetGroupIdForAllWeeks(groupId: Int?, orderIndex: Int, dayNumber: Int, programId: Long)

  @Query("UPDATE exercises SET orderIndex = :orderIndex WHERE id = :exerciseId")
  suspend fun updateOrderIndex(exerciseId: Long, orderIndex: Int)

  @Query("""
    UPDATE exercises SET orderIndex = :newIndex
    WHERE orderIndex = :currentIndex
    AND sessionId IN (
      SELECT id FROM sessions
      WHERE dayNumber = :dayNumber AND programId = :programId
      AND weekNumber != :currentWeek
    )
  """)
  suspend fun updateOrderIndexForFutureWeeks(
    currentIndex: Int, newIndex: Int, dayNumber: Int, programId: Long, currentWeek: Int
  )

  @Query("UPDATE exercises SET unilateralMode = :mode WHERE id = :exerciseId")
  suspend fun setUnilateralMode(exerciseId: Long, mode: Int)

  @Query("""
    UPDATE exercises SET unilateralMode = :mode
    WHERE orderIndex = :orderIndex
    AND sessionId IN (
      SELECT id FROM sessions WHERE dayNumber = :dayNumber AND programId = :programId
    )
  """)
  suspend fun setUnilateralModeForAllWeeks(mode: Int, orderIndex: Int, dayNumber: Int, programId: Long)

  @Query("""
    DELETE FROM exercises
    WHERE orderIndex = :orderIndex
    AND sessionId IN (
      SELECT id FROM sessions WHERE dayNumber = :dayNumber AND programId = :programId
    )
  """)
  suspend fun deleteByPositionForAllWeeks(orderIndex: Int, dayNumber: Int, programId: Long)

  @Query("""
    UPDATE exercises SET orderIndex = orderIndex - 1
    WHERE orderIndex > :deletedIndex
    AND sessionId IN (
      SELECT id FROM sessions WHERE dayNumber = :dayNumber AND programId = :programId
    )
  """)
  suspend fun shiftOrderIndexesDownAfterDelete(deletedIndex: Int, dayNumber: Int, programId: Long)

  @Query("DELETE FROM exercises")
  suspend fun deleteAll()
}

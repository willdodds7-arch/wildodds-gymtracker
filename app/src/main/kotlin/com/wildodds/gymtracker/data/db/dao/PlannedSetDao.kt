package com.wildodds.gymtracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wildodds.gymtracker.data.db.entity.ExerciseAlternative
import com.wildodds.gymtracker.data.db.entity.PlannedSet

@Dao
interface PlannedSetDao {

  @Query("SELECT * FROM planned_sets WHERE exerciseId = :exerciseId ORDER BY setNumber")
  suspend fun forExercise(exerciseId: Long): List<PlannedSet>

  /**
   * Every planned set in one program, in one query — the builder loads a whole program at once and
   * an exercise-at-a-time read would be a round trip per row.
   */
  @Query(
    """SELECT ps.* FROM planned_sets ps
       JOIN exercises e ON e.id = ps.exerciseId
       JOIN sessions s ON s.id = e.sessionId
       WHERE s.programId = :programId
       ORDER BY ps.exerciseId, ps.setNumber"""
  )
  suspend fun forProgram(programId: Long): List<PlannedSet>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(sets: List<PlannedSet>)

  @Query("DELETE FROM planned_sets WHERE exerciseId = :exerciseId")
  suspend fun deleteForExercise(exerciseId: Long)

  // ── Alternatives ───────────────────────────────────────────────────────────

  @Query("SELECT * FROM exercise_alternatives WHERE exerciseId = :exerciseId ORDER BY orderIndex")
  suspend fun alternativesFor(exerciseId: Long): List<ExerciseAlternative>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAlternatives(rows: List<ExerciseAlternative>)

  @Query("DELETE FROM exercise_alternatives WHERE exerciseId = :exerciseId")
  suspend fun deleteAlternativesForExercise(exerciseId: Long)
}

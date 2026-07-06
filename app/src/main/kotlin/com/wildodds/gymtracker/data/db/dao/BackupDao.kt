package com.wildodds.gymtracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wildodds.gymtracker.data.db.entity.Achievement
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Habit
import com.wildodds.gymtracker.data.db.entity.HabitCompletion
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.ProgramPhase
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.db.entity.SessionTemplate
import com.wildodds.gymtracker.data.db.entity.SessionTemplateExercise
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.db.entity.WorkoutLog

/**
 * Cross-table reads/writes for full snapshot backup & restore (Phase 5B). A DAO may query any table,
 * so this one DAO covers every backed-up entity without touching the per-entity DAOs. Inserts use
 * REPLACE and the snapshot's original primary keys, preserving every foreign-key relationship.
 */
@Dao
interface BackupDao {

  @Query("SELECT * FROM programs") suspend fun programs(): List<Program>
  @Query("SELECT * FROM program_phases") suspend fun programPhases(): List<ProgramPhase>
  @Query("SELECT * FROM sessions") suspend fun sessions(): List<Session>
  @Query("SELECT * FROM exercises") suspend fun exercises(): List<Exercise>
  @Query("SELECT * FROM workout_logs") suspend fun workoutLogs(): List<WorkoutLog>
  @Query("SELECT * FROM set_logs") suspend fun setLogs(): List<SetLog>
  @Query("SELECT * FROM session_completions") suspend fun completions(): List<SessionCompletion>
  @Query("SELECT * FROM habits") suspend fun habits(): List<Habit>
  @Query("SELECT * FROM habit_completions") suspend fun habitCompletions(): List<HabitCompletion>
  @Query("SELECT * FROM session_templates") suspend fun templates(): List<SessionTemplate>
  @Query("SELECT * FROM session_template_exercises") suspend fun templateExercises(): List<SessionTemplateExercise>
  @Query("SELECT * FROM achievements") suspend fun achievements(): List<Achievement>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPrograms(rows: List<Program>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProgramPhases(rows: List<ProgramPhase>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSessions(rows: List<Session>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertExercises(rows: List<Exercise>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWorkoutLogs(rows: List<WorkoutLog>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSetLogs(rows: List<SetLog>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCompletions(rows: List<SessionCompletion>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHabits(rows: List<Habit>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHabitCompletions(rows: List<HabitCompletion>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTemplates(rows: List<SessionTemplate>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTemplateExercises(rows: List<SessionTemplateExercise>)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAchievements(rows: List<Achievement>)

  // Wipe order respects FKs (children first); CASCADE would handle it, but explicit is clearer.
  @Query("DELETE FROM set_logs") suspend fun clearSetLogs()
  @Query("DELETE FROM workout_logs") suspend fun clearWorkoutLogs()
  @Query("DELETE FROM session_completions") suspend fun clearCompletions()
  @Query("DELETE FROM exercises") suspend fun clearExercises()
  @Query("DELETE FROM sessions") suspend fun clearSessions()
  @Query("DELETE FROM program_phases") suspend fun clearProgramPhases()
  @Query("DELETE FROM programs") suspend fun clearPrograms()
  @Query("DELETE FROM habit_completions") suspend fun clearHabitCompletions()
  @Query("DELETE FROM habits") suspend fun clearHabits()
  @Query("DELETE FROM session_template_exercises") suspend fun clearTemplateExercises()
  @Query("DELETE FROM session_templates") suspend fun clearTemplates()
  @Query("DELETE FROM achievements") suspend fun clearAchievements()
}

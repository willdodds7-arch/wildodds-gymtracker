package com.wildodds.gymtracker.data.sync

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
 * A complete, portable copy of the user's local training data — the unit for backup/restore and the
 * JSON export. Stores rows with their original primary keys so restore rebuilds every relationship
 * exactly. [schemaVersion] lets a future restore migrate older snapshots.
 */
data class TrainingSnapshot(
  val schemaVersion: Int = CURRENT,
  val exportedAt: Long = 0L,
  val programs: List<Program> = emptyList(),
  val programPhases: List<ProgramPhase> = emptyList(),
  val sessions: List<Session> = emptyList(),
  val exercises: List<Exercise> = emptyList(),
  val workoutLogs: List<WorkoutLog> = emptyList(),
  val setLogs: List<SetLog> = emptyList(),
  val completions: List<SessionCompletion> = emptyList(),
  val habits: List<Habit> = emptyList(),
  val habitCompletions: List<HabitCompletion> = emptyList(),
  val templates: List<SessionTemplate> = emptyList(),
  val templateExercises: List<SessionTemplateExercise> = emptyList(),
  val achievements: List<Achievement> = emptyList()
) {
  companion object {
    const val CURRENT = 1
  }
}

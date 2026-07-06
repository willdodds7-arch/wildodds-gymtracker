package com.wildodds.gymtracker.data.sync

import androidx.room.withTransaction
import com.google.gson.Gson
import com.wildodds.gymtracker.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-snapshot backup & restore (Phase 5B). Local Room is the source of truth; a snapshot is just a
 * portable copy of it. Restore rebuilds the local DB from a snapshot — used by cloud restore and by
 * restore-onto-a-fresh-install.
 *
 * Safety: restore runs in a single transaction (all-or-nothing) and inserts in foreign-key order, so
 * a failure can never leave a half-rebuilt database.
 */
class BackupManager(private val db: AppDatabase, private val gson: Gson = Gson()) {

  /** Read all local training data into a portable snapshot. */
  suspend fun snapshot(exportedAt: Long): TrainingSnapshot = withContext(Dispatchers.IO) {
    val dao = db.backupDao()
    TrainingSnapshot(
      exportedAt = exportedAt,
      programs = dao.programs(),
      programPhases = dao.programPhases(),
      sessions = dao.sessions(),
      exercises = dao.exercises(),
      workoutLogs = dao.workoutLogs(),
      setLogs = dao.setLogs(),
      completions = dao.completions(),
      habits = dao.habits(),
      habitCompletions = dao.habitCompletions(),
      templates = dao.templates(),
      templateExercises = dao.templateExercises(),
      achievements = dao.achievements()
    )
  }

  /**
   * Replace all local training data with [snapshot]. Atomic: wipes then re-inserts in FK-safe order
   * inside one transaction. Restoring onto a fresh install reproduces the exact source database.
   */
  suspend fun restore(snapshot: TrainingSnapshot) = withContext(Dispatchers.IO) {
    val dao = db.backupDao()
    db.withTransaction {
      // Wipe children → parents.
      dao.clearSetLogs(); dao.clearWorkoutLogs(); dao.clearCompletions()
      dao.clearExercises(); dao.clearSessions(); dao.clearProgramPhases(); dao.clearPrograms()
      dao.clearHabitCompletions(); dao.clearHabits()
      dao.clearTemplateExercises(); dao.clearTemplates()
      dao.clearAchievements()

      // Insert parents → children.
      dao.insertPrograms(snapshot.programs)
      dao.insertProgramPhases(snapshot.programPhases)
      dao.insertSessions(snapshot.sessions)
      dao.insertExercises(snapshot.exercises)
      dao.insertWorkoutLogs(snapshot.workoutLogs)
      dao.insertSetLogs(snapshot.setLogs)
      dao.insertCompletions(snapshot.completions)
      dao.insertHabits(snapshot.habits)
      dao.insertHabitCompletions(snapshot.habitCompletions)
      dao.insertTemplates(snapshot.templates)
      dao.insertTemplateExercises(snapshot.templateExercises)
      dao.insertAchievements(snapshot.achievements)
    }
  }

  fun toJson(snapshot: TrainingSnapshot): String = gson.toJson(snapshot)

  fun fromJson(json: String): TrainingSnapshot =
    gson.fromJson(json, TrainingSnapshot::class.java)
}

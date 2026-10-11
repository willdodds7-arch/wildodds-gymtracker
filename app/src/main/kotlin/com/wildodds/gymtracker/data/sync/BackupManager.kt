package com.wildodds.gymtracker.data.sync

import androidx.room.withTransaction
import com.google.gson.Gson
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.*
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

  /**
   * Read a backup file for restoring — tolerant of backups written by an OLDER version of the app.
   *
   * Plain Gson builds objects without calling their constructors, so a field the old version didn't
   * have comes back null even when the Kotlin type says it can't be, and the insert then fails on a
   * NOT NULL column. Building each row from a real default instance instead means a missing field
   * takes the same default a fresh row would. Throws [IllegalArgumentException] for a file that isn't
   * a Wild Odds backup.
   */
  fun fromBackupJson(json: String): TrainingSnapshot {
    val snapshot = runCatching { restoreGson.fromJson(json, TrainingSnapshot::class.java) }.getOrNull()
    require(snapshot != null && snapshot.programs != null && snapshot.sessions != null) {
      "That file isn't a Wild Odds backup. Use a file made with Export data → Export as JSON."
    }
    // Lists missing from an older backup arrive as null rather than empty.
    return snapshot.copy(
      programPhases = snapshot.programPhases.orEmptyIfNull(),
      exercises = snapshot.exercises.orEmptyIfNull(),
      workoutLogs = snapshot.workoutLogs.orEmptyIfNull(),
      setLogs = snapshot.setLogs.orEmptyIfNull(),
      completions = snapshot.completions.orEmptyIfNull(),
      habits = snapshot.habits.orEmptyIfNull(),
      habitCompletions = snapshot.habitCompletions.orEmptyIfNull(),
      templates = snapshot.templates.orEmptyIfNull(),
      templateExercises = snapshot.templateExercises.orEmptyIfNull(),
      achievements = snapshot.achievements.orEmptyIfNull()
    )
  }

  @Suppress("USELESS_ELVIS", "UNCHECKED_CAST")
  private fun <T> List<T>?.orEmptyIfNull(): List<T> = (this ?: emptyList<T>()).filterNotNull() as List<T>

  private val restoreGson: Gson by lazy {
    fun <T> creator(make: () -> T) = com.google.gson.InstanceCreator<T> { make() }
    com.google.gson.GsonBuilder()
      .registerTypeAdapter(Program::class.java, creator { Program(name = "", totalWeeks = 1) })
      .registerTypeAdapter(ProgramPhase::class.java,
        creator { ProgramPhase(programId = 0, phaseNumber = 1, name = "", durationWeeks = 1) })
      .registerTypeAdapter(Session::class.java,
        creator { Session(programId = 0, weekNumber = 1, dayNumber = 1, name = "", muscleGroups = "") })
      .registerTypeAdapter(Exercise::class.java,
        creator { Exercise(sessionId = 0, name = "", sets = 1, repsTarget = "", orderIndex = 0) })
      .registerTypeAdapter(WorkoutLog::class.java, creator { WorkoutLog(exerciseId = 0, sessionId = 0, weekNumber = 1) })
      .registerTypeAdapter(SetLog::class.java, creator { SetLog(workoutLogId = 0, setNumber = 1) })
      .registerTypeAdapter(SessionCompletion::class.java, creator { SessionCompletion(sessionId = 0, weekNumber = 1) })
      .registerTypeAdapter(Habit::class.java, creator { Habit(name = "") })
      .registerTypeAdapter(HabitCompletion::class.java, creator { HabitCompletion(habitId = 0, completedDate = "") })
      .registerTypeAdapter(SessionTemplate::class.java, creator { SessionTemplate(name = "") })
      .registerTypeAdapter(SessionTemplateExercise::class.java,
        creator { SessionTemplateExercise(templateId = 0, name = "", sets = 1, repsTarget = "", orderIndex = 0) })
      .registerTypeAdapter(Achievement::class.java, creator { Achievement(id = "", unlockedAt = 0) })
      .create()
  }
}

package com.wildodds.gymtracker.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Achievement
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Habit
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.db.entity.WorkoutLog
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Snapshot → wipe → restore round-trip, including restore onto an emptied (fresh) database. */
@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

  private lateinit var db: AppDatabase
  private lateinit var backup: BackupManager

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(), AppDatabase::class.java
    ).allowMainThreadQueries().build()
    backup = BackupManager(db)
  }

  @After
  fun tearDown() = db.close()

  private suspend fun seed() {
    val dao = db.backupDao()
    dao.insertPrograms(listOf(Program(id = 1, name = "P", totalWeeks = 4, isActive = true)))
    dao.insertSessions(listOf(Session(id = 1, programId = 1, weekNumber = 1, dayNumber = 1, name = "Push", muscleGroups = "Chest")))
    dao.insertExercises(listOf(Exercise(id = 1, sessionId = 1, name = "Bench", sets = 3, repsTarget = "5", orderIndex = 0)))
    dao.insertWorkoutLogs(listOf(WorkoutLog(id = 1, exerciseId = 1, sessionId = 1, weekNumber = 1)))
    dao.insertSetLogs(listOf(SetLog(id = 1, workoutLogId = 1, setNumber = 1, weightKg = 100f, reps = 5)))
    dao.insertCompletions(listOf(SessionCompletion(id = 1, sessionId = 1, weekNumber = 1)))
    dao.insertHabits(listOf(Habit(id = 1, name = "Stretch")))
    dao.insertAchievements(listOf(Achievement(id = "sessions_1", unlockedAt = 123L)))
  }

  @Test
  fun snapshotThenRestoreOntoFreshDbReproducesData() = runTest {
    seed()
    val snapshot = backup.snapshot(exportedAt = 123L)
    assertEquals(1, snapshot.programs.size)
    assertEquals(1, snapshot.setLogs.size)

    // Simulate a fresh install: wipe everything.
    backup.restore(TrainingSnapshot())
    assertTrue(db.backupDao().programs().isEmpty())
    assertTrue(db.backupDao().setLogs().isEmpty())

    // Restore from the snapshot → exact data returns, relationships intact.
    backup.restore(snapshot)
    assertEquals(1, db.backupDao().programs().size)
    assertEquals(1, db.backupDao().setLogs().size)
    assertEquals(1, db.backupDao().habits().size)
    assertEquals("Bench", db.backupDao().exercises().first().name)
    assertEquals(100f, db.backupDao().setLogs().first().weightKg)
    // Achievements ride along in the snapshot → sync/restore round-trip (Phase 6A).
    assertEquals(1, db.backupDao().achievements().size)
    assertEquals("sessions_1", db.backupDao().achievements().first().id)
  }

  @Test
  fun jsonRoundTripIsLossless() = runTest {
    seed()
    val snapshot = backup.snapshot(exportedAt = 99L)
    val restored = backup.fromJson(backup.toJson(snapshot))
    assertEquals(snapshot.programs, restored.programs)
    assertEquals(snapshot.setLogs, restored.setLogs)
    assertEquals(99L, restored.exportedAt)
  }
}

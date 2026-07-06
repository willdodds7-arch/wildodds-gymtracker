package com.wildodds.gymtracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 3H: running a one-off on-demand session must not alter the active program. */
@RunWith(RobolectricTestRunner::class)
class OnDemandSessionTest {

  private lateinit var db: AppDatabase
  private lateinit var repo: GymRepository

  @Before
  fun setUp() {
  db = Room.inMemoryDatabaseBuilder(
  ApplicationProvider.getApplicationContext(), AppDatabase::class.java
  ).allowMainThreadQueries().build()
  repo = GymRepository(db)
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun onDemandSessionLogsWithoutAlteringTheActiveProgram() = runTest {
  // Active program with one day + one exercise.
  val activeId = db.programDao().insert(Program(name = "My Program", totalWeeks = 4, isActive = true))
  db.sessionDao().insertAll(listOf(Session(programId = activeId, weekNumber = 1, dayNumber = 1,
  name = "Push", muscleGroups = "Chest")))
  val activeSession = db.sessionDao().getSessionsForWeek(1, activeId).first()
  db.exerciseDao().insert(Exercise(sessionId = activeSession.id, name = "Bench Press", sets = 3, repsTarget = "5", orderIndex = 0))

  val beforeSessions = db.sessionDao().getAllSessionsOnce(activeId)
  val beforeExercises = db.exerciseDao().getExercisesForSession(activeSession.id)

  // Run a one-off session.
  val newSessionId = repo.startOnDemandSession(
  title = "Quick Pull", muscleGroups = "Lats",
  exercises = listOf(Triple("Lat Pulldown", 3, "10-12"), Triple("Face Pull", 3, "15"))
  )

  // The active program is byte-for-byte unchanged.
  assertEquals(beforeSessions, db.sessionDao().getAllSessionsOnce(activeId))
  assertEquals(beforeExercises, db.exerciseDao().getExercisesForSession(activeSession.id))

  // The new session lives under a DIFFERENT (hidden) program with its own exercises.
  val newSession = db.sessionDao().getSessionById(newSessionId)!!
  assertNotEquals(activeId, newSession.programId)
  assertEquals("Quick Pull", newSession.name)
  assertEquals(2, db.exerciseDao().getExercisesForSession(newSessionId).size)

  // The hidden program is filtered out of the visible program lists.
  assertTrue(repo.allPrograms().first().none { it.name == GymRepository.ON_DEMAND_PROGRAM_NAME })
  }
}

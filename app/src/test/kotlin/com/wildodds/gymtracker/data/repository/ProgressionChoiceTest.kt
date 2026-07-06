package com.wildodds.gymtracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BUG 1 regression: the progression choice must be settable AND re-editable on every week,
 * targeting the CURRENT week's workout log — never the previous week's.
 */
@RunWith(RobolectricTestRunner::class)
class ProgressionChoiceTest {

  private lateinit var db: AppDatabase
  private lateinit var repo: GymRepository

  @Before
  fun setUp() {
  db = Room.inMemoryDatabaseBuilder(
  ApplicationProvider.getApplicationContext(),
  AppDatabase::class.java
  ).allowMainThreadQueries().build()
  repo = GymRepository(db)
  }

  @After
  fun tearDown() = db.close()

  /** A seeded week's ids. */
  private data class Week(val sessionId: Long, val exerciseId: Long, val week: Int, val logId: Long)

  private suspend fun seedWeek(programId: Long, week: Int): Week {
  db.sessionDao().insertAll(listOf(Session(programId = programId, weekNumber = week,
  dayNumber = 1, name = "Push", muscleGroups = "Chest")))
  val sessionId = db.sessionDao().getSessionsForWeek(week, programId).first().id
  val exerciseId = db.exerciseDao().insert(
  Exercise(sessionId = sessionId, name = "Bench Press", sets = 3, repsTarget = "5", orderIndex = 0)
  )
  val logId = repo.getOrCreateWorkoutLog(exerciseId, sessionId, week).id
  return Week(sessionId, exerciseId, week, logId)
  }

  private suspend fun choiceOf(w: Week): String? =
  db.workoutLogDao().getWorkoutLogForExercise(w.exerciseId, w.week)?.progressionChoice

  @Test
  fun progressionChoiceIsReEditablePerWeekAndIsolatedAcrossWeeks() = runTest {
  val programId = db.programDao().insert(Program(name = "P", totalWeeks = 4, isActive = true))
  val week1 = seedWeek(programId, 1)
  val week2 = seedWeek(programId, 2)

  // Week 1 gets its own choice.
  repo.updateProgressionChoice(week1.logId, "ADD_REPS")

  // Set a choice on week 2…
  repo.updateProgressionChoice(week2.logId, "ADD_WEIGHT")
  assertEquals("ADD_WEIGHT", choiceOf(week2))

  // …re-edit it (the reported bug: locked on weeks 2+)…
  repo.updateProgressionChoice(week2.logId, "DELOAD")
  assertEquals("DELOAD", choiceOf(week2))

  // …and clear it entirely (deselect must persist).
  repo.updateProgressionChoice(week2.logId, null)
  assertNull(choiceOf(week2))

  // Week 1 was never touched by any of the week-2 writes.
  assertEquals("ADD_REPS", choiceOf(week1))
  }
}

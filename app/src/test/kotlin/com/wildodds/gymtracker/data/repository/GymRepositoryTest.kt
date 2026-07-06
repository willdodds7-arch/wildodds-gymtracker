package com.wildodds.gymtracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
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

/**
 * Exercises [GymRepository.getPrevWeekSetLogsByPosition] against a real in-memory Room
 * database — the cross-week carry-forward query is positional (programId, dayNumber,
 * orderIndex), so it must keep working even though exercise ids differ between weeks.
 */
@RunWith(RobolectricTestRunner::class)
class GymRepositoryTest {

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

  @Test
  fun prevWeekSetLogs_areLookedUpByPosition() = runTest {
  // ── Arrange: one program, day 1, the same exercise position in weeks 1 and 2 ──
  val programId = db.programDao().insert(
  Program(name = "Test Program", totalWeeks = 4, isActive = true)
  )

  // Week 1, Day 1 — log real set values at orderIndex 0.
  val week1SessionId = db.sessionDao().let {
  it.insertAll(listOf(Session(programId = programId, weekNumber = 1, dayNumber = 1,
  name = "Push", muscleGroups = "Chest")))
  it.getSessionsForWeek(1, programId).first().id
  }
  val week1ExerciseId = db.exerciseDao().insert(
  Exercise(sessionId = week1SessionId, name = "Bench Press", sets = 3,
  repsTarget = "8-10", orderIndex = 0)
  )
  val week1Log = repo.getOrCreateWorkoutLog(week1ExerciseId, week1SessionId, weekNumber = 1)
  repo.upsertSetLog(SetLog(workoutLogId = week1Log.id, setNumber = 1, weightKg = 60f, reps = 10))
  repo.upsertSetLog(SetLog(workoutLogId = week1Log.id, setNumber = 2, weightKg = 62.5f, reps = 8))

  // Week 2, Day 1 — same position (orderIndex 0) but a brand-new exercise id, no sets yet.
  val week2SessionId = db.sessionDao().let {
  it.insertAll(listOf(Session(programId = programId, weekNumber = 2, dayNumber = 1,
  name = "Push", muscleGroups = "Chest")))
  it.getSessionsForWeek(2, programId).first().id
  }
  db.exerciseDao().insert(
  Exercise(sessionId = week2SessionId, name = "Bench Press", sets = 3,
  repsTarget = "8-10", orderIndex = 0)
  )

  // ── Act: ask for week 2's carry-forward prefill at that position ──
  val carry = repo.getPrevWeekSetLogsByPosition(
  programId = programId, dayNumber = 1, currentWeek = 2, orderIndex = 0
  )

  // ── Assert: week 1's two sets come back, in order, with the right values ──
  assertEquals(2, carry.size)
  assertEquals(1, carry[0].setNumber)
  assertEquals(60f, carry[0].weightKg!!, 0.001f)
  assertEquals(10, carry[0].reps)
  assertEquals(2, carry[1].setNumber)
  assertEquals(62.5f, carry[1].weightKg!!, 0.001f)
  assertEquals(8, carry[1].reps)

  // A different position has no prior data — must come back empty (graceful prefill).
  val emptyCarry = repo.getPrevWeekSetLogsByPosition(
  programId = programId, dayNumber = 1, currentWeek = 2, orderIndex = 5
  )
  assertTrue(emptyCarry.isEmpty())
  }

  @Test
  fun importProgram_persistsCoachAndTags() = runTest {
  val parsed = com.wildodds.gymtracker.data.parser.ParsedProgram(
  name = "Coached Program", totalWeeks = 4,
  sessions = listOf(
  com.wildodds.gymtracker.data.parser.ParsedSession(
  weekNumber = 1, dayNumber = 1, name = "Day 1", muscleGroups = "Chest",
  exercises = listOf(com.wildodds.gymtracker.data.parser.ParsedExercise(
  name = "Bench Press", sets = 3, repsTarget = "8-10", notes = "", orderIndex = 0))
  )
  ),
  description = "A focused block.", coach = "Jane Doe", coachBio = "Strength coach.",
  daysPerWeek = 4, split = "Upper/Lower", style = "Hypertrophy"
  )
  repo.importProgram(parsed, activate = false)

  val saved = db.backupDao().programs().first { it.name == "Coached Program" }
  assertEquals("Jane Doe", saved.coach)
  assertEquals("Strength coach.", saved.coachBio)
  assertEquals("A focused block.", saved.description)
  assertEquals("Upper/Lower", saved.split)
  assertEquals("Hypertrophy", saved.style)
  assertEquals(4, saved.daysPerWeek)
  }

  @Test
  fun bestEstimatedOneRm_picksHighestEpleyForMainLift() = runTest {
  val programId = db.programDao().insert(Program(name = "P", totalWeeks = 1, isActive = true))
  val sessionId = db.sessionDao().let {
  it.insertAll(listOf(Session(programId = programId, weekNumber = 1, dayNumber = 1,
  name = "Push", muscleGroups = "Chest")))
  it.getSessionsForWeek(1, programId).first().id
  }
  val exId = db.exerciseDao().insert(
  Exercise(sessionId = sessionId, name = "Barbell Bench Press", sets = 3, repsTarget = "8", orderIndex = 0))
  val log = repo.getOrCreateWorkoutLog(exId, sessionId, weekNumber = 1)
  repo.upsertSetLog(SetLog(workoutLogId = log.id, setNumber = 1, weightKg = 60f, reps = 10)) // e1RM 80
  repo.upsertSetLog(SetLog(workoutLogId = log.id, setNumber = 2, weightKg = 62.5f, reps = 8)) // e1RM ~79.2

  val best = repo.bestEstimatedOneRm()
  assertEquals(80f, best[com.wildodds.gymtracker.data.profile.MainLift.BENCH]!!, 0.1f)
  assertTrue("squat should be absent", best[com.wildodds.gymtracker.data.profile.MainLift.SQUAT] == null)
  }
}

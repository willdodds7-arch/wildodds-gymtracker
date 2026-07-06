package com.wildodds.gymtracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.wearable.NoOpWearableSessionData
import com.wildodds.gymtracker.data.wearable.decodeHrSeries
import com.wildodds.gymtracker.data.wearable.encodeHrSeries
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Phase 2A: the post-session summary (duration, strain, HR) persists on the completion row. */
@RunWith(RobolectricTestRunner::class)
class SessionSummaryPersistenceTest {

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

  private suspend fun seedSession(): Long {
  val programId = db.programDao().insert(Program(name = "P", totalWeeks = 4, isActive = true))
  db.sessionDao().insertAll(listOf(Session(programId = programId, weekNumber = 1,
  dayNumber = 1, name = "Push", muscleGroups = "Chest")))
  return db.sessionDao().getSessionsForWeek(1, programId).first().id
  }

  @Test
  fun durationAndStrainPersistThenClearOnReset() = runTest {
  val sessionId = seedSession()

  repo.completeSession(sessionId, weekNumber = 1, durationSeconds = 1830)
  var summary = repo.getSessionCompletion(sessionId, 1)
  assertEquals(1830L, summary?.durationSeconds)
  assertNull("strain not chosen yet", summary?.strainRating)

  repo.setSessionStrain(sessionId, 1, 4)
  summary = repo.getSessionCompletion(sessionId, 1)
  assertEquals(4, summary?.strainRating)
  assertEquals("duration untouched by strain write", 1830L, summary?.durationSeconds)

  // Reset day clears the completion (and thus its summary) — shared lifecycle.
  repo.resetSessionDay(sessionId, 1)
  assertNull(repo.getSessionCompletion(sessionId, 1))
  }

  @Test
  fun heartRateFieldsRoundTripWhenAProviderSuppliesThem() = runTest {
  val sessionId = seedSession()
  repo.completeSession(
  sessionId, weekNumber = 1, durationSeconds = 600,
  avgHeartRate = 132, peakHeartRate = 175, hrSeries = encodeHrSeries(listOf(110, 150, 175, 140))
  )
  val summary = repo.getSessionCompletion(sessionId, 1)!!
  assertEquals(132, summary.avgHeartRate)
  assertEquals(175, summary.peakHeartRate)
  assertEquals(listOf(110, 150, 175, 140), decodeHrSeries(summary.hrSeries))
  }

  @Test
  fun fatigueScorePersistsToTheSummary() = runTest {
  val sessionId = seedSession()
  repo.completeSession(sessionId, weekNumber = 1, durationSeconds = 1200, fatigueScore = 64)
  assertEquals(64, repo.getSessionCompletion(sessionId, 1)?.fatigueScore)
  }

  @Test
  fun noOpWearableReturnsNull() = runTest {
  val now = Instant.ofEpochMilli(1_000_000)
  assertNull(NoOpWearableSessionData.heartRateForSession(now, now.plusSeconds(600)))
  }
}

package com.wildodds.gymtracker.data.gamification

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.repository.GymRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Repository-level: achievements are awarded from purely local data with NO account, and awarding is
 * idempotent across repeated evaluations (the re-sync correctness property).
 */
@RunWith(RobolectricTestRunner::class)
class AchievementRepoTest {

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
  fun signedOutUserEarnsLocalAchievementsAndAwardIsIdempotent() = runTest {
    val dao = db.backupDao()
    dao.insertPrograms(listOf(Program(id = 1, name = "P", totalWeeks = 1, isActive = true)))
    dao.insertSessions(listOf(Session(id = 1, programId = 1, weekNumber = 1, dayNumber = 1, name = "A", muscleGroups = "")))
    dao.insertCompletions(listOf(SessionCompletion(id = 1, sessionId = 1, weekNumber = 1)))

    // No auth anywhere — purely local.
    val firstPass = repo.evaluateAndUnlockAchievements(usedTravelMode = false, now = 1L)
    assertTrue("first session should unlock", firstPass.any { it.definition.id == "sessions_1" })
    val afterFirst = repo.unlockedAchievementIds()
    assertTrue("sessions_1" in afterFirst)

    // Re-evaluate: nothing new, count unchanged → never double-awards.
    val secondPass = repo.evaluateAndUnlockAchievements(usedTravelMode = false, now = 2L)
    assertTrue("re-evaluation awards nothing new", secondPass.isEmpty())
    assertEquals(afterFirst, repo.unlockedAchievementIds())
  }
}

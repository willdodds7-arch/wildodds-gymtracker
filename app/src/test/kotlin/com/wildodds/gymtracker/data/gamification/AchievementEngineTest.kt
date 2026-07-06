package com.wildodds.gymtracker.data.gamification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure achievement evaluation: triggers exactly once, idempotent, correct progress. */
class AchievementEngineTest {

  @Test
  fun unlocksOnlyAchievementsWhoseTargetIsMet() {
    val newly = AchievementEngine.evaluate(MetricsSnapshot(sessionsCompleted = 1), emptySet(), now = 0L)
    assertEquals(listOf("sessions_1"), newly.map { it.definition.id })
  }

  @Test
  fun reEvaluationNeverDoubleAwards() {
    val metrics = MetricsSnapshot(sessionsCompleted = 1)
    val first = AchievementEngine.evaluate(metrics, emptySet(), 0L)
    assertEquals(listOf("sessions_1"), first.map { it.definition.id })
    // Second pass with the same id already unlocked → nothing new, ever.
    val second = AchievementEngine.evaluate(metrics, setOf("sessions_1"), 0L)
    assertTrue(second.isEmpty())
  }

  @Test
  fun higherMetricsUnlockEveryTierAtOnce() {
    val newly = AchievementEngine.evaluate(MetricsSnapshot(sessionsCompleted = 10), emptySet(), 0L)
      .map { it.definition.id }.toSet()
    assertTrue("sessions_1" in newly)
    assertTrue("sessions_10" in newly)
    assertFalse("sessions_50" in newly)
  }

  @Test
  fun progressReportsFractionAndUnlockedState() {
    val progress = AchievementEngine.progress(MetricsSnapshot(sessionsCompleted = 1), setOf("sessions_1"))
    val s1 = progress.first { it.definition.id == "sessions_1" }
    assertTrue(s1.unlocked)
    assertEquals(1f, s1.fraction)
    val s10 = progress.first { it.definition.id == "sessions_10" }
    assertFalse(s10.unlocked)
    assertEquals(0.1f, s10.fraction, 0.001f)  // 1/10
  }

  @Test
  fun travelAndVolumeAchievementsEvaluateFromTheirSignals() {
    val m = MetricsSnapshot(totalVolumeKg = 10_000, usedTravelMode = true)
    val ids = AchievementEngine.evaluate(m, emptySet(), 0L).map { it.definition.id }.toSet()
    assertTrue("volume_10k" in ids)
    assertTrue("travel_1" in ids)
  }

  @Test
  fun nextUpIsTheMostAdvancedLockedOne() {
    val next = AchievementEngine.nextUp(MetricsSnapshot(sessionsCompleted = 9), setOf("sessions_1"))
    assertEquals("sessions_10", next?.definition?.id)  // 9/10 is the closest locked tier
  }
}

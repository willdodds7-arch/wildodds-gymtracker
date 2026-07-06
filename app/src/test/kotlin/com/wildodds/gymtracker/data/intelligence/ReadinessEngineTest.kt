package com.wildodds.gymtracker.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the deterministic readiness engine. */
class ReadinessEngineTest {

  @Test
  fun goodSleepAndNormalRestingHrMeansTrain() {
    val r = ReadinessEngine.score(ReadinessInput(
      sleepMinutes = 480, restingHr = 55, restingHrBaseline = 54f,
      daysSinceLastSession = 1
    ))!!
    assertEquals(ReadinessLevel.GOOD, r.level)
    assertEquals("Good to train", r.headline)
    assertTrue(r.usedWearable)
  }

  @Test
  fun poorSleepAndElevatedRestingHrMeansRest() {
    val r = ReadinessEngine.score(ReadinessInput(
      sleepMinutes = 270, restingHr = 64, restingHrBaseline = 55f,
      daysSinceLastSession = 1
    ))!!
    assertEquals(ReadinessLevel.REST, r.level)
    assertEquals("Rest recommended", r.headline)
    assertTrue("score should be below the rest threshold", r.score < ReadinessEngine.REST_THRESHOLD)
  }

  @Test
  fun moderatelyDownMarkersMeanCaution() {
    val r = ReadinessEngine.score(ReadinessInput(
      sleepMinutes = 340, restingHr = 59, restingHrBaseline = 55f,
      daysSinceLastSession = 1
    ))!!
    assertEquals(ReadinessLevel.CAUTION, r.level)
    assertEquals("Consider a lighter session", r.headline)
  }

  @Test
  fun suppressedHrvLowersReadiness() {
    val base = ReadinessInput(sleepMinutes = 450, restingHr = 55, restingHrBaseline = 55f,
      hrvRmssd = 60.0, hrvBaseline = 60.0, daysSinceLastSession = 1)
    val healthy = ReadinessEngine.score(base)!!
    val suppressed = ReadinessEngine.score(base.copy(hrvRmssd = 40.0))!!  // ~0.67 of baseline
    assertTrue("suppressed HRV must lower the score", suppressed.score < healthy.score)
    assertTrue(suppressed.reason.contains("HRV"))
  }

  @Test
  fun noWearableFallsBackToTrainingLoadWithNote() {
    // No sleep/HR/HRV at all — only a hard recent session.
    val r = ReadinessEngine.score(ReadinessInput(
      recentStrain = 5, recentFatigueScore = 80, daysSinceLastSession = 0
    ))!!
    assertNotNull(r)
    assertFalse("must be flagged as a non-wearable (load-only) result", r.usedWearable)
    assertTrue(r.score < 100)
  }

  @Test
  fun aRestDayFadesTheRecentStrainPenalty() {
    val yesterday = ReadinessEngine.score(ReadinessInput(
      recentStrain = 5, recentFatigueScore = 80, daysSinceLastSession = 0))!!
    val afterRest = ReadinessEngine.score(ReadinessInput(
      recentStrain = 5, recentFatigueScore = 80, daysSinceLastSession = 3))!!
    assertTrue("having rested should raise readiness", afterRest.score > yesterday.score)
  }

  @Test
  fun nothingAtAllReturnsNull() {
    assertNull(ReadinessEngine.score(ReadinessInput()))
  }
}

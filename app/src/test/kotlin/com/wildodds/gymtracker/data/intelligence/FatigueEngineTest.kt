package com.wildodds.gymtracker.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the real-time fatigue engine. */
class FatigueEngineTest {

  private fun set(reps: Int?, rpe: Float? = null, w: Float? = 100f) = FatigueSet(w, reps, rpe)

  @Test
  fun flatPerformanceIsFreshWithNoSuggestion() {
  val r = FatigueEngine.score(listOf(listOf(set(10, 7f), set(10, 7f), set(10, 7f))))!!
  assertEquals(FatigueLevel.FRESH, r.level)
  assertNull(r.suggestion)
  }

  @Test
  fun repDecayAndRpeDriftRaiseFatigueAndTriggerASuggestion() {
  val flat = FatigueEngine.score(listOf(listOf(set(10, 7f), set(10, 7f), set(10, 7f))))!!
  val decayed = FatigueEngine.score(listOf(listOf(set(10, 7f), set(8, 8f), set(6, 9f), set(5, 9.5f))))!!
  assertTrue("fatigue must rise with decay + drift", decayed.score > flat.score)
  assertEquals(FatigueLevel.FATIGUED, decayed.level)
  assertNotNull("a high-fatigue suggestion should appear", decayed.suggestion)
  }

  @Test
  fun suggestionOnlyAppearsAtTheFatiguedThreshold() {
  // Mild decline → not yet FATIGUED → no suggestion.
  val mild = FatigueEngine.score(listOf(listOf(set(10, 7f), set(9, 7.5f))))!!
  assertTrue(mild.level != FatigueLevel.FATIGUED)
  assertNull(mild.suggestion)
  }

  @Test
  fun worksWithoutHeartRate() {
  val r = FatigueEngine.score(listOf(listOf(set(10, 8f), set(7, 9f))), avgHeartRate = null)
  assertNotNull(r)  // reps + RPE alone are enough
  }

  @Test
  fun heartRateRaisesTheScoreWhenPresent() {
  val sets = listOf(listOf(set(10, 7f), set(8, 8f)))
  val noHr = FatigueEngine.score(sets, avgHeartRate = null)!!
  val withHr = FatigueEngine.score(sets, avgHeartRate = 180)!!
  assertTrue("HR should add to fatigue", withHr.score > noHr.score)
  }

  @Test
  fun notEnoughDataReturnsNullIndicator() {
  // One set per exercise, no RPE pairs, no HR → nothing to score yet.
  assertNull(FatigueEngine.score(listOf(listOf(set(5, null))), avgHeartRate = null))
  }
}

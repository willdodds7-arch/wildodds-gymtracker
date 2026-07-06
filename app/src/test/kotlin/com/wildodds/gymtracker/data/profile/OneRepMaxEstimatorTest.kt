package com.wildodds.gymtracker.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure unit tests for the Profile potential-1RM estimator (no Android). */
class OneRepMaxEstimatorTest {

  @Test
  fun mainLift_matchesByLooseName() {
    assertEquals(MainLift.BENCH, MainLift.forExercise("Barbell Bench Press"))
    assertEquals(MainLift.SQUAT, MainLift.forExercise("High-Bar Back Squat"))
    assertEquals(MainLift.DEADLIFT, MainLift.forExercise("Conventional Deadlift"))
    assertNull(MainLift.forExercise("Lat Pulldown"))
  }

  @Test
  fun bestByLift_keepsHighestEstimatePerLift() {
    val rows = listOf(
      LiftSetRow("Bench Press", 60f, 10),   // 80.0
      LiftSetRow("Bench Press", 62.5f, 8),  // ~79.2
      LiftSetRow("Back Squat", 100f, 5),    // ~116.7
      LiftSetRow("Lat Pulldown", 70f, 12)   // ignored — not a main lift
    )
    val best = OneRepMaxEstimator.bestByLift(rows)
    assertEquals(80f, best[MainLift.BENCH]!!, 0.1f)
    assertEquals(116.67f, best[MainLift.SQUAT]!!, 0.1f)
    assertNull(best[MainLift.DEADLIFT])
  }

  @Test
  fun bestByLift_ignoresEmptyOrInvalidSets() {
    val rows = listOf(
      LiftSetRow("Deadlift", 0f, 5),     // no load
      LiftSetRow("Deadlift", 140f, 0)    // no reps
    )
    assertTrue(OneRepMaxEstimator.bestByLift(rows).isEmpty())
  }
}

package com.wildodds.gymtracker.data.cap3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CAP3 training-max rules. */
class Cap3ProgressionTest {

  // Bench: reference max 100, TM 90, so an AMRAP has to estimate past 100 to be a PR.
  private val config = Cap3FixtureTest.fixtureConfig()

  // ── Major effort ────────────────────────────────────────────────────────────

  @Test
  fun hittingTheMinimumWithoutAPrAddsOneStep() {
    // Week 3 bench ME, option 1: 72.5 × 6+. Six reps estimates 86 — well short of the stored 100.
    val c = Cap3Progression.evaluateMajorEffort(config, Cap3Slot.BENCH_1, 72.5, achievedReps = 6, requiredReps = 6)
    assertEquals(Cap3ProgressionOutcome.STEP_UP, c.outcome)
    assertEquals(92.5, c.newTm, 1e-9)
    assertEquals(2.5, c.delta, 1e-9)
    assertTrue(c.message.contains("92.5"))
  }

  @Test
  fun beatingTheStoredEstimatedMaxSetsTheTrainingMaxToItRoundedUp() {
    // 72.5 × 12 → floor(72.5 × 1.3996) = 101, past the stored 100 → TM becomes CEILING(101) = 102.5.
    val c = Cap3Progression.evaluateMajorEffort(config, Cap3Slot.BENCH_1, 72.5, achievedReps = 12, requiredReps = 6)
    assertEquals(Cap3ProgressionOutcome.NEW_EST_MAX, c.outcome)
    assertEquals(101.0, c.newEst1Rm!!, 1e-9)
    assertEquals(102.5, c.newTm, 1e-9)
    assertTrue(c.message.contains("PR"))

    val applied = Cap3Progression.apply(config, c)
    assertEquals(102.5, applied.lift(Cap3Slot.BENCH_1).trainingMaxKg, 1e-9)
    assertEquals(101.0, applied.lift(Cap3Slot.BENCH_1).bestEst1RmKg, 1e-9)
    // The next session's week-1 opener follows the new TM: CEILING(102.5 × 0.72) = 75.
    assertEquals(
      75.0,
      Cap3Engine.block(applied, Cap3Tag(1, Cap3DaySlot.BENCH_A, Cap3Role.T1))!!.sets.first().weight,
      1e-9
    )
  }

  @Test
  fun missingTheMinimumBacksTheTrainingMaxOff() {
    val c = Cap3Progression.evaluateMajorEffort(config, Cap3Slot.BENCH_1, 80.0, achievedReps = 1, requiredReps = 2)
    assertEquals(Cap3ProgressionOutcome.REDUCE, c.outcome)
    // 90 − 5% = 85.5, floored to the 2.5 step.
    assertEquals(85.0, c.newTm, 1e-9)
    assertTrue(c.delta < 0)
  }

  // ── Other AMRAPs ────────────────────────────────────────────────────────────

  @Test
  fun aRepPrOnAYellowAmrapSetsTheTrainingMaxToTheNewEstimatedMax() {
    // Overhead slot 1: TM 90, stored estimated max 100. 65 × 16 → floor(65 × 1.5328) = 99, no PR.
    val noPr = Cap3Progression.evaluateAmrap(config, Cap3Slot.OVERHEAD_1, 65.0, achievedReps = 16, requiredReps = 10)
    assertEquals(Cap3ProgressionOutcome.HOLD, noPr.outcome)
    assertEquals(noPr.currentTm, noPr.newTm, 1e-9)
    assertEquals(false, noPr.isChange)

    // 65 × 17 → floor(65 × 1.5661) = 101 → a PR.
    val pr = Cap3Progression.evaluateAmrap(config, Cap3Slot.OVERHEAD_1, 65.0, achievedReps = 17, requiredReps = 10)
    assertEquals(Cap3ProgressionOutcome.NEW_EST_MAX, pr.outcome)
    assertEquals(101.0, pr.newEst1Rm!!, 1e-9)
    assertEquals(102.5, pr.newTm, 1e-9)
  }

  @Test
  fun allRequiredRepsButNoPrBumpsOneStepAtTheEndOfTheCycle() {
    val c = Cap3Progression.evaluateAmrap(
      config, Cap3Slot.ROW_1, 65.0, achievedReps = 12, requiredReps = 10, endOfCycle = true
    )
    assertEquals(Cap3ProgressionOutcome.STEP_UP, c.outcome)
    assertEquals(92.5, c.newTm, 1e-9)
  }

  @Test
  fun missingRequiredRepsLowersTheTrainingMax() {
    val c = Cap3Progression.evaluateAmrap(config, Cap3Slot.ROW_1, 65.0, achievedReps = 7, requiredReps = 10)
    assertEquals(Cap3ProgressionOutcome.REDUCE, c.outcome)
    // 90 − 7.5% = 83.25, floored to the step.
    assertEquals(82.5, c.newTm, 1e-9)
  }

  // ── Applying ────────────────────────────────────────────────────────────────

  @Test
  fun aHoldChangesNothing() {
    val c = Cap3Progression.evaluateAmrap(config, Cap3Slot.ROW_1, 65.0, achievedReps = 12, requiredReps = 10)
    assertEquals(false, c.isChange)
    assertEquals(
      config.lift(Cap3Slot.ROW_1).trainingMaxKg,
      Cap3Progression.apply(config, c).lift(Cap3Slot.ROW_1).trainingMaxKg,
      1e-9
    )
  }

  @Test
  fun poundsChangesUseTheFivePoundStep() {
    val lb = Cap3FixtureTest.fixtureConfig(Cap3Units.LB)
    val c = Cap3Progression.evaluateMajorEffort(lb, Cap3Slot.BENCH_1, 160.0, achievedReps = 6, requiredReps = 6)
    assertEquals(Cap3ProgressionOutcome.STEP_UP, c.outcome)
    assertEquals(5.0, c.delta, 1e-9)
    // Applying keeps the stored kilograms consistent with the pound value shown.
    val applied = Cap3Progression.apply(lb, c)
    assertEquals(c.newTm, Cap3Units.LB.fromKg(applied.lift(Cap3Slot.BENCH_1).trainingMaxKg), 1e-6)
  }
}

package com.wildodds.gymtracker.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the progression rules engine and carry-forward adjustment math. */
class ProgressionEngineTest {

  private fun week(n: Int, w: Float, reps: Int, rpe: Float? = null) =
  WeekRecord(n, listOf(SetRecord(w, reps, rpe)))

  // ── Double progression (straight sets) ──────────────────────────────────────

  @Test
  fun doubleProgression_addsWeightAtTopOfRange() {
  val rec = ProgressionEngine.recommend(ProgressionInput(
  history = listOf(week(1, 60f, 12)),
  trackingMode = TrackingMode.STRAIGHT_SETS, repLow = 8, repHigh = 12
  ))
  assertEquals(ProgressionAction.ADD_WEIGHT, rec.action)
  assertEquals(2.5f, rec.weightDeltaKg, 0.001f)
  assertTrue(rec.rationale.isNotBlank())
  }

  @Test
  fun doubleProgression_addsRepBelowTopOfRange() {
  val rec = ProgressionEngine.recommend(ProgressionInput(
  history = listOf(week(1, 60f, 9)),
  trackingMode = TrackingMode.STRAIGHT_SETS, repLow = 8, repHigh = 12
  ))
  assertEquals(ProgressionAction.ADD_REPS, rec.action)
  assertEquals(1, rec.repDelta)
  }

  // ── RPE autoregulation ──────────────────────────────────────────────────────

  @Test
  fun rpe_belowTarget_addsLoad() {
  val rec = ProgressionEngine.recommend(ProgressionInput(
  history = listOf(week(1, 100f, 5, rpe = 6f)),
  trackingMode = TrackingMode.RPE, rpeTarget = 8f
  ))
  assertEquals(ProgressionAction.ADD_WEIGHT, rec.action)
  }

  @Test
  fun rpe_aboveTarget_holds() {
  val rec = ProgressionEngine.recommend(ProgressionInput(
  history = listOf(week(1, 100f, 5, rpe = 9.5f)),
  trackingMode = TrackingMode.RPE, rpeTarget = 8f
  ))
  assertEquals(ProgressionAction.HOLD, rec.action)
  }

  @Test
  fun rpe_atTarget_addsRep() {
  val rec = ProgressionEngine.recommend(ProgressionInput(
  history = listOf(week(1, 100f, 5, rpe = 8f)),
  trackingMode = TrackingMode.RPE, rpeTarget = 8f
  ))
  assertEquals(ProgressionAction.ADD_REPS, rec.action)
  }

  // ── %1RM ramp ───────────────────────────────────────────────────────────────

  @Test
  fun percent1rm_rampsLoad() {
  val rec = ProgressionEngine.recommend(ProgressionInput(
  history = listOf(week(1, 100f, 3)), trackingMode = TrackingMode.PERCENT_1RM
  ))
  assertEquals(ProgressionAction.ADD_WEIGHT, rec.action)
  }

  // ── Stall → deload ──────────────────────────────────────────────────────────

  @Test
  fun stall_overThreeWeeks_recommendsDeload() {
  val flat = listOf(week(1, 100f, 5), week(2, 100f, 5), week(3, 100f, 5), week(4, 100f, 5))
  val rec = ProgressionEngine.recommend(ProgressionInput(
  history = flat, trackingMode = TrackingMode.STRAIGHT_SETS, stallWeeks = 3
  ))
  assertEquals(ProgressionAction.DELOAD, rec.action)
  assertTrue("deload delta should be negative", rec.weightDeltaKg < 0f)
  assertTrue(rec.rationale.contains("No progress"))
  }

  @Test
  fun progressingHistoryIsNotStalled() {
  val rising = listOf(week(1, 100f, 5), week(2, 102.5f, 5), week(3, 105f, 5), week(4, 107.5f, 5))
  assertFalse(ProgressionEngine.isStalled(rising, 3))
  val rec = ProgressionEngine.recommend(ProgressionInput(history = rising, repLow = 5, repHigh = 8))
  assertTrue(rec.action != ProgressionAction.DELOAD)
  }

  @Test
  fun isStalled_needsEnoughHistory() {
  assertFalse(ProgressionEngine.isStalled(listOf(week(1, 100f, 5), week(2, 100f, 5)), 3))
  }

  // ── Explicit styles honoured ────────────────────────────────────────────────

  @Test
  fun explicitStylesAreHonouredWithConcreteDeltas() {
  val h = listOf(week(1, 80f, 8))
  fun rec(style: ProgressionStyle) = ProgressionEngine.recommend(ProgressionInput(history = h, style = style))
  assertEquals(ProgressionAction.ADD_WEIGHT, rec(ProgressionStyle.ADD_WEIGHT).action)
  assertEquals(1, rec(ProgressionStyle.ADD_REPS).repDelta)
  assertEquals(1, rec(ProgressionStyle.ADD_SET).setDelta)
  assertEquals(ProgressionAction.HOLD, rec(ProgressionStyle.HOLD).action)
  assertEquals(ProgressionAction.DELOAD, rec(ProgressionStyle.DELOAD).action)
  }

  @Test
  fun noUsableHistoryHolds() {
  val rec = ProgressionEngine.recommend(ProgressionInput(history = emptyList()))
  assertEquals(ProgressionAction.HOLD, rec.action)
  assertTrue(rec.rationale.isNotBlank())
  }

  @Test
  fun pickerKeyMapping() {
  assertEquals("MORE_WEIGHT", ProgressionEngine.pickerKeyFor(ProgressionAction.ADD_WEIGHT))
  assertEquals(ProgressionStyle.ADD_REPS, ProgressionEngine.styleFromPickerKey("MORE_REPS"))
  assertEquals(ProgressionStyle.HOLD, ProgressionEngine.styleFromPickerKey("BETTER_FORM"))
  assertEquals(ProgressionStyle.AUTO, ProgressionEngine.styleFromPickerKey(null))
  }

  // ── Carry-forward adjustment math ───────────────────────────────────────────

  private val prev = listOf(SetRecord(100f, 5), SetRecord(100f, 5), SetRecord(95f, 5))

  @Test
  fun carryForward_addWeightBumpsEverySet() {
  val rec = ProgressionRecommendation(ProgressionAction.ADD_WEIGHT, weightDeltaKg = 2.5f, rationale = "")
  val out = ProgressionEngine.applyToCarryForward(prev, rec)
  assertEquals(listOf(102.5f, 102.5f, 97.5f), out.map { it.weightKg })
  assertEquals(listOf(5, 5, 5), out.map { it.reps })
  }

  @Test
  fun carryForward_addRepsBumpsReps() {
  val rec = ProgressionRecommendation(ProgressionAction.ADD_REPS, repDelta = 1, rationale = "")
  val out = ProgressionEngine.applyToCarryForward(prev, rec)
  assertEquals(listOf(6, 6, 6), out.map { it.reps })
  }

  @Test
  fun carryForward_addSetAppendsCopies() {
  val rec = ProgressionRecommendation(ProgressionAction.ADD_SET, setDelta = 1, rationale = "")
  val out = ProgressionEngine.applyToCarryForward(prev, rec)
  assertEquals(4, out.size)
  assertEquals(95f, out.last().weightKg)  // copies the last set
  }

  @Test
  fun carryForward_deloadDropsWeightAndClampsAtZero() {
  val rec = ProgressionRecommendation(ProgressionAction.DELOAD, weightDeltaKg = -10f, rationale = "")
  val out = ProgressionEngine.applyToCarryForward(prev, rec)
  assertEquals(listOf(90f, 90f, 85f), out.map { it.weightKg })
  // never negative
  val heavyDrop = ProgressionEngine.applyToCarryForward(
  listOf(SetRecord(5f, 5)), rec).first().weightKg
  assertEquals(0f, heavyDrop)
  }

  @Test
  fun carryForward_holdLeavesEverythingUnchanged() {
  val rec = ProgressionRecommendation(ProgressionAction.HOLD, rationale = "")
  val out = ProgressionEngine.applyToCarryForward(prev, rec)
  assertEquals(prev.map { it.weightKg }, out.map { it.weightKg })
  assertEquals(prev.map { it.reps }, out.map { it.reps })
  }
}

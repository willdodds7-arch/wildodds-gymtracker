package com.wildodds.gymtracker.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the session-loop fixes:
 *  - BUG 2: [autofillWeights] propagation precedence (manual > carry-forward, all weeks).
 *  - GUARD 3: [shouldGuardExit] precedence.
 */
class SessionLogicTest {

  // ── BUG 2: weight autofill ──────────────────────────────────────────────────

  private fun blank(n: Int) = (1..n).map { SetRowState(setNumber = it) }
  private fun prefilled(n: Int, w: String) =
  (1..n).map { SetRowState(setNumber = it, weightKg = w, isPrefilled = true) }

  @Test
  fun week1_fillsBlankFollowingSets() {
  val result = autofillWeights(blank(3), "100")
  assertEquals(listOf("100", "100", "100"), result.map { it.weightKg })
  assertTrue(result[1].isWeightAutoFilled)
  assertTrue(result[2].isWeightAutoFilled)
  }

  @Test
  fun laterWeeks_newFirstSetOverwritesCarriedForwardPrefill() {
  // Week 3: every set carries forward 100 from the prior week.
  val carried = prefilled(3, "100")
  val result = autofillWeights(carried, "120")

  assertEquals(listOf("120", "120", "120"), result.map { it.weightKg })
  // The followers are now auto-filled, no longer carry-forward prefill.
  assertTrue(result[1].isWeightAutoFilled)
  assertFalse(result[1].isPrefilled)
  assertTrue(result[2].isWeightAutoFilled)
  assertFalse(result[2].isPrefilled)
  }

  @Test
  fun manuallyEditedLaterSetIsNeverClobbered() {
  // Set 3 was manually edited (not prefill, not autofill, has a value).
  val sets = listOf(
  SetRowState(setNumber = 1, weightKg = "100", isPrefilled = true),
  SetRowState(setNumber = 2, weightKg = "100", isPrefilled = true),
  SetRowState(setNumber = 3, weightKg = "90")  // manual
  )
  val result = autofillWeights(sets, "120")
  assertEquals("120", result[0].weightKg)
  assertEquals("120", result[1].weightKg)   // prefill → overwritten
  assertEquals("90", result[2].weightKg)    // manual → preserved
  assertFalse(result[2].isWeightAutoFilled)
  }

  @Test
  fun clearingFirstSetDoesNotNukeCarriedForwardPrefill() {
  val carried = prefilled(3, "100")
  val result = autofillWeights(carried, "")
  // Set 1 cleared; carry-forward prefills on later sets are left intact.
  assertEquals("", result[0].weightKg)
  assertEquals("100", result[1].weightKg)
  assertTrue(result[1].isPrefilled)
  }

  // ── GUARD 3: exit confirmation precedence ───────────────────────────────────

  @Test
  fun exitGuard_triggersOnlyWhenEnabledDirtyAndIncomplete() {
  assertTrue(shouldGuardExit(guardEnabled = true, hasLoggedThisVisit = true, isCompleted = false))
  // Any one of these defeats the guard:
  assertFalse(shouldGuardExit(guardEnabled = false, hasLoggedThisVisit = true,  isCompleted = false))
  assertFalse(shouldGuardExit(guardEnabled = true,  hasLoggedThisVisit = false, isCompleted = false))
  assertFalse(shouldGuardExit(guardEnabled = true,  hasLoggedThisVisit = true,  isCompleted = true))
  }

  // ── Phase 2A: duration ──────────────────────────────────────────────────────

  @Test
  fun durationIsNullWhenNothingLogged() {
  assertEquals(null, computeDurationSeconds(startMillis = null, endMillis = 10_000))
  }

  @Test
  fun durationIsWholeSecondsAndClampsBackwardsClock() {
  assertEquals(90L, computeDurationSeconds(startMillis = 1_000_000, endMillis = 1_090_400))
  assertEquals(null, computeDurationSeconds(startMillis = 2_000, endMillis = 1_000)) // clock went back
  }

  @Test
  fun durationFormatsReadably() {
  assertEquals("—", formatDuration(null))
  assertEquals("40s", formatDuration(40))
  assertEquals("2m 5s", formatDuration(125))
  assertEquals("1h 2m", formatDuration(3725))
  }
}

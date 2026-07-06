package com.wildodds.gymtracker.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plate-loading math, including the edge cases these calculators usually get wrong. */
class PlateMathTest {

  // A common kg set without 25s, so 100kg/20bar gives the canonical "2 × 20 per side".
  private val plates = listOf(20f, 15f, 10f, 5f, 2.5f, 1.25f)

  @Test
  fun hundredKgOnTwentyBarIsTwoTwentiesPerSide() {
  val r = PlateMath.computePerSide(100f, 20f, plates)
  assertEquals(listOf(20f, 20f), r.perSide)
  assertEquals(100f, r.achievedWeight, 0.001f)
  assertTrue(r.isExact)
  assertEquals(listOf(2 to 20f), PlateMath.groupForDisplay(r.perSide))
  }

  @Test
  fun oddButMakeableWeight() {
  // 102.5 → 41.25/side → 20 + 20 + 1.25
  val r = PlateMath.computePerSide(102.5f, 20f, plates)
  assertEquals(listOf(20f, 20f, 1.25f), r.perSide)
  assertEquals(102.5f, r.achievedWeight, 0.001f)
  assertTrue(r.isExact)
  }

  @Test
  fun unmakeableWeightFallsBackToClosestBelow() {
  // 101 → 40.5/side; smallest plate is 1.25, so 0.5/side is unloadable → closest is 100.
  val r = PlateMath.computePerSide(101f, 20f, plates)
  assertEquals(100f, r.achievedWeight, 0.001f)
  assertFalse(r.isExact)
  assertEquals(listOf(20f, 20f), r.perSide)
  }

  @Test
  fun targetBelowBarLoadsNothing() {
  val r = PlateMath.computePerSide(15f, 20f, plates)
  assertTrue(r.barOnly)
  assertEquals(20f, r.achievedWeight, 0.001f)
  assertFalse(r.isExact)  // can't actually make 15kg with a 20kg bar
  }

  @Test
  fun targetEqualsBarIsExactBarOnly() {
  val r = PlateMath.computePerSide(20f, 20f, plates)
  assertTrue(r.barOnly)
  assertTrue(r.isExact)
  }

  @Test
  fun usesLargestPlatesFirstWithDefaultSetIncluding25() {
  // Default set includes 25 → 140kg/20bar → 60/side → 25+25+10.
  val r = PlateMath.computePerSide(140f, 20f, PlateMath.DEFAULT_PLATES_KG)
  assertEquals(listOf(25f, 25f, 10f), r.perSide)
  assertEquals(140f, r.achievedWeight, 0.001f)
  assertTrue(r.isExact)
  }
}

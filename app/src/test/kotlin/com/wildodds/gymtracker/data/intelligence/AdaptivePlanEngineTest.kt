package com.wildodds.gymtracker.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the deterministic adaptive-plan engine. Same inputs → same plan, always. */
class AdaptivePlanEngineTest {

  @Test
  fun lowRecoveryProposesALighterSession() {
    val a = AdaptivePlanEngine.adjust(AdaptiveInput(readiness = ReadinessLevel.REST))
    assertEquals(PlanAdjustmentType.LIGHTER_SESSION, a.type)
    assertTrue("should drop load", a.loadScale < 1f)
    assertTrue("should trim volume", a.volumeScale < 1f)
    assertTrue(a.isActionable)
  }

  @Test
  fun stallProposesADeload() {
    val a = AdaptivePlanEngine.adjust(AdaptiveInput(readiness = ReadinessLevel.GOOD, programStalled = true))
    assertEquals(PlanAdjustmentType.INSERT_DELOAD, a.type)
    assertTrue(a.loadScale < 1f)
  }

  @Test
  fun lowRecoveryOutranksAStall() {
    // Under-recovered AND stalled → recovery wins (don't pile a deload onto a bad day).
    val a = AdaptivePlanEngine.adjust(AdaptiveInput(readiness = ReadinessLevel.REST, programStalled = true))
    assertEquals(PlanAdjustmentType.LIGHTER_SESSION, a.type)
  }

  @Test
  fun mildlyDownRecoveryTrimsVolumeButKeepsLoad() {
    val a = AdaptivePlanEngine.adjust(AdaptiveInput(readiness = ReadinessLevel.CAUTION))
    assertEquals(PlanAdjustmentType.SCALE_VOLUME, a.type)
    assertEquals(1f, a.loadScale)
    assertTrue(a.volumeScale < 1f)
  }

  @Test
  fun aLongLayoffEasesBackIn() {
    val a = AdaptivePlanEngine.adjust(AdaptiveInput(
      readiness = ReadinessLevel.GOOD, daysSinceLastSession = AdaptivePlanEngine.LONG_LAYOFF_DAYS))
    assertEquals(PlanAdjustmentType.EASE_IN, a.type)
  }

  @Test
  fun allGoodTrainsAsPlanned() {
    val a = AdaptivePlanEngine.adjust(AdaptiveInput(
      readiness = ReadinessLevel.GOOD, programStalled = false, daysSinceLastSession = 1))
    assertEquals(PlanAdjustmentType.PROCEED, a.type)
    assertEquals(1f, a.loadScale)
    assertEquals(1f, a.volumeScale)
    assertFalse("nothing to accept when proceeding", a.isActionable)
  }

  @Test
  fun worksWithNoRecoveryDataFromProgressAndScheduleAlone() {
    // readiness null (no wearable) but the program has stalled → still adapts.
    val a = AdaptivePlanEngine.adjust(AdaptiveInput(readiness = null, programStalled = true))
    assertEquals(PlanAdjustmentType.INSERT_DELOAD, a.type)
  }
}

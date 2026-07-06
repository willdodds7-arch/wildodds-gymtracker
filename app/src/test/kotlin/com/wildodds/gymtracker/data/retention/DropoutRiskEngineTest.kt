package com.wildodds.gymtracker.data.retention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DropoutRiskEngineTest {

  @Test
  fun consistentTrainingIsLowRisk() {
    val f = DropoutFeatures(
      daysSinceLastSession = 1, baselineSessionsPerWeek = 3f, recentSessionsPerWeek = 3f,
      baselineAvgGapDays = 2.3f, recentAvgGapDays = 2.3f, volumeTrendPct = 0.05f
    )
    assertEquals(DropoutRisk.LOW, DropoutRiskEngine.assess(f).risk)
  }

  @Test
  fun decliningCadenceRaisesRiskToHigh() {
    val f = DropoutFeatures(
      daysSinceLastSession = 12, baselineSessionsPerWeek = 4f, recentSessionsPerWeek = 1f,
      baselineAvgGapDays = 2f, recentAvgGapDays = 6f, volumeTrendPct = -0.5f
    )
    val a = DropoutRiskEngine.assess(f)
    assertEquals(DropoutRisk.HIGH, a.risk)
    assertTrue("a high-risk assessment must explain itself", a.reasons.isNotEmpty())
  }

  @Test
  fun aModerateDipIsMediumRisk() {
    val f = DropoutFeatures(
      daysSinceLastSession = 5, baselineSessionsPerWeek = 4f, recentSessionsPerWeek = 2f
    )
    assertEquals(DropoutRisk.MEDIUM, DropoutRiskEngine.assess(f).risk)
  }

  @Test
  fun riseIsMonotonicAsThingsGetWorse() {
    val ok = DropoutRiskEngine.assess(DropoutFeatures(daysSinceLastSession = 2, baselineSessionsPerWeek = 3f, recentSessionsPerWeek = 3f)).score
    val worse = DropoutRiskEngine.assess(DropoutFeatures(daysSinceLastSession = 8, baselineSessionsPerWeek = 3f, recentSessionsPerWeek = 1f)).score
    assertTrue("risk score should climb as cadence falls", worse > ok)
  }

  @Test
  fun noHistoryIsLowRiskWithoutAlarm() {
    val a = DropoutRiskEngine.assess(DropoutFeatures())
    assertEquals(DropoutRisk.LOW, a.risk)
    assertTrue(a.reasons.isEmpty())
  }
}

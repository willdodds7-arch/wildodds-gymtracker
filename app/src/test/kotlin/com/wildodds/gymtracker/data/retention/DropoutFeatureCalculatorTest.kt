package com.wildodds.gymtracker.data.retention

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DropoutFeatureCalculatorTest {

  private val zone = ZoneOffset.UTC
  private val today = LocalDate.of(2026, 6, 21)
  private fun ms(daysAgo: Long): Long = today.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()

  @Test
  fun decliningCadenceSurfacesInTheFeatures() {
    // Trained ~3x/week in the baseline window (days 15..55 ago), then almost stopped (one session 11 days ago).
    val baseline = (15L..55L step 2).map { ms(it) }   // dense baseline
    val recent = listOf(ms(11))                        // a single recent session, 11 days ago
    val sessions = baseline + recent

    val f = DropoutFeatureCalculator.compute(sessions, emptyList(), today, zone)

    assertTrue("days since last is large", (f.daysSinceLastSession ?: 0) >= 11)
    assertTrue("baseline frequency was computed", f.baselineSessionsPerWeek != null)
    assertTrue("recent frequency dropped below baseline",
      (f.recentSessionsPerWeek ?: 0f) < (f.baselineSessionsPerWeek ?: 0f))
  }

  @Test
  fun noSessionsYieldsEmptyFeatures() {
    val f = DropoutFeatureCalculator.compute(emptyList(), emptyList(), today, zone)
    assertTrue(f.daysSinceLastSession == null && f.baselineSessionsPerWeek == null)
  }
}

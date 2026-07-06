package com.wildodds.gymtracker.data.gamification

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreakCalculatorTest {

  private val d = LocalDate.of(2026, 6, 21)

  @Test
  fun longestStreakFindsTheBestRun() {
    val dates = listOf(d.minusDays(6), d.minusDays(5), d.minusDays(4), d.minusDays(2), d.minusDays(1), d)
    // a 3-run then a 3-run → longest is 3
    assertEquals(3, StreakCalculator.longestStreak(dates))
  }

  @Test
  fun longestStreakHandlesEmptyAndSingle() {
    assertEquals(0, StreakCalculator.longestStreak(emptyList()))
    assertEquals(1, StreakCalculator.longestStreak(listOf(d)))
  }

  @Test
  fun currentStreakCountsBackFromToday() {
    val dates = listOf(d.minusDays(2), d.minusDays(1), d)
    assertEquals(3, StreakCalculator.currentStreak(dates, today = d))
  }

  @Test
  fun currentStreakSurvivesNotTrainingYetToday() {
    // Trained through yesterday; haven't trained today yet → streak isn't broken.
    val dates = listOf(d.minusDays(2), d.minusDays(1))
    assertEquals(2, StreakCalculator.currentStreak(dates, today = d))
  }

  @Test
  fun currentStreakIsZeroAfterAMissedDay() {
    val dates = listOf(d.minusDays(3), d.minusDays(2))  // gap before today/yesterday
    assertEquals(0, StreakCalculator.currentStreak(dates, today = d))
  }
}

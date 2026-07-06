package com.wildodds.gymtracker.data.gamification

import java.time.LocalDate
import kotlin.math.max

/** Pure consecutive-day streak math over a set of dates (workout days, habit days, …). */
object StreakCalculator {

  /** The longest run of consecutive calendar days present in [dates]. */
  fun longestStreak(dates: Collection<LocalDate>): Int {
    if (dates.isEmpty()) return 0
    val sorted = dates.toSortedSet().toList()
    var longest = 1
    var run = 1
    for (i in 1 until sorted.size) {
      run = if (sorted[i] == sorted[i - 1].plusDays(1)) run + 1 else 1
      longest = max(longest, run)
    }
    return longest
  }

  /**
   * The current streak ending at [today] (or yesterday — a streak isn't broken until you miss a full
   * day, so not having trained *yet today* still counts the run through yesterday). 0 if neither
   * today nor yesterday is present.
   */
  fun currentStreak(dates: Collection<LocalDate>, today: LocalDate): Int {
    val set = dates.toSet()
    var day = when {
      today in set -> today
      today.minusDays(1) in set -> today.minusDays(1)
      else -> return 0
    }
    var count = 0
    while (day in set) {
      count++
      day = day.minusDays(1)
    }
    return count
  }
}

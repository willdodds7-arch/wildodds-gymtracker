package com.wildodds.gymtracker.data.retention

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure reduction of raw local rows into [DropoutFeatures]. Windows: "recent" = last 14 days, "baseline"
 * = the 6 weeks before that, so frequency/volume are always judged against the user's own prior norm.
 * No Android, no DB — testable with hand-built lists.
 */
object DropoutFeatureCalculator {

  private const val RECENT_DAYS = 14L
  private const val BASELINE_DAYS = 56L   // 8 weeks total window; baseline = days 14..56

  fun compute(
    sessionTimestamps: List<Long>,
    volumeEvents: List<Pair<Long, Double>>,   // (workout completedAt, set volume)
    today: LocalDate,
    zone: ZoneId
  ): DropoutFeatures {
    fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    val dates = sessionTimestamps.map { day(it) }.sorted()
    if (dates.isEmpty()) return DropoutFeatures()

    val daysSinceLast = ChronoUnit.DAYS.between(dates.last(), today).toInt().coerceAtLeast(0)

    val recentFrom = today.minusDays(RECENT_DAYS)
    val baselineFrom = today.minusDays(BASELINE_DAYS)

    val recentCount = dates.count { it > recentFrom }
    val baselineCount = dates.count { it > baselineFrom && it <= recentFrom }
    val recentPerWeek = recentCount / (RECENT_DAYS / 7f)
    val baselinePerWeek = if (baselineCount > 0) baselineCount / ((BASELINE_DAYS - RECENT_DAYS) / 7f) else null

    // Gaps between consecutive sessions (days).
    val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toFloat() }
    val recentGap = gaps.takeLast(3).ifEmpty { null }?.average()?.toFloat()
    val baselineGap = gaps.dropLast(3).ifEmpty { null }?.average()?.toFloat()

    // Weekly volume recent vs baseline.
    val recentVol = volumeEvents.filter { day(it.first) > recentFrom }.sumOf { it.second }
    val baselineVol = volumeEvents.filter { day(it.first) > baselineFrom && day(it.first) <= recentFrom }.sumOf { it.second }
    val recentVolPerWeek = recentVol / (RECENT_DAYS / 7.0)
    val baselineVolPerWeek = baselineVol / ((BASELINE_DAYS - RECENT_DAYS) / 7.0)
    val volumeTrend = if (baselineVolPerWeek > 0.0) ((recentVolPerWeek / baselineVolPerWeek) - 1.0).toFloat() else null

    return DropoutFeatures(
      daysSinceLastSession = daysSinceLast,
      baselineSessionsPerWeek = baselinePerWeek,
      recentSessionsPerWeek = if (baselinePerWeek != null) recentPerWeek else null,
      baselineAvgGapDays = baselineGap,
      recentAvgGapDays = recentGap,
      volumeTrendPct = volumeTrend
    )
  }
}

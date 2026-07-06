package com.wildodds.gymtracker.data.retention

import kotlin.math.roundToInt

/** Calm three-band churn signal. */
enum class DropoutRisk { LOW, MEDIUM, HIGH }

/**
 * The features the model reads. All relative to the USER'S OWN baseline (never population norms), all
 * optional so a new user with little history is never over-flagged. Documented here so the model is
 * auditable and tunable — this is deliberately rules-based, not a black box.
 *
 *  - [daysSinceLastSession]     absolute recency.
 *  - [recentSessionsPerWeek] vs [baselineSessionsPerWeek]  frequency drop vs their own norm.
 *  - [recentAvgGapDays]      vs [baselineAvgGapDays]        gaps lengthening.
 *  - [recentCompletionRate]  vs [baselineCompletionRate]    finishing fewer planned sessions.
 *  - [volumeTrendPct]        recent weekly volume vs baseline (e.g. -0.3 = down 30%).
 */
data class DropoutFeatures(
  val daysSinceLastSession: Int? = null,
  val baselineSessionsPerWeek: Float? = null,
  val recentSessionsPerWeek: Float? = null,
  val baselineAvgGapDays: Float? = null,
  val recentAvgGapDays: Float? = null,
  val baselineCompletionRate: Float? = null,
  val recentCompletionRate: Float? = null,
  val volumeTrendPct: Float? = null
)

data class DropoutAssessment(
  val risk: DropoutRisk,
  val score: Int,            // 0..100
  val reasons: List<String>  // contributing factors, most→least, for transparency
)

/**
 * Transparent, deterministic dropout-risk scoring. Each feature contributes points with a clear
 * reason; the band is a simple threshold on the sum. No HR/ML — just the user's own training trend.
 * Tunable: every weight/threshold is a named constant.
 */
object DropoutRiskEngine {

  // Band thresholds.
  const val MEDIUM_THRESHOLD = 30
  const val HIGH_THRESHOLD = 55

  private data class Factor(val points: Int, val reason: String)

  fun assess(f: DropoutFeatures): DropoutAssessment {
    // Not enough to judge → LOW, no alarm.
    if (f.daysSinceLastSession == null && f.baselineSessionsPerWeek == null) {
      return DropoutAssessment(DropoutRisk.LOW, 0, emptyList())
    }

    val factors = mutableListOf<Factor>()

    f.daysSinceLastSession?.let { d ->
      when {
        d > 10 -> factors += Factor(45, "It's been over $d days since your last session")
        d in 7..10 -> factors += Factor(30, "It's been $d days since your last session")
        d in 4..6 -> factors += Factor(15, "A few days since your last session")
      }
    }

    if (f.baselineSessionsPerWeek != null && f.recentSessionsPerWeek != null && f.baselineSessionsPerWeek >= 1f) {
      val ratio = f.recentSessionsPerWeek / f.baselineSessionsPerWeek
      when {
        ratio <= 0.34f -> factors += Factor(25, "You're training much less than usual")
        ratio <= 0.6f -> factors += Factor(15, "You're training less often than usual")
        ratio <= 0.8f -> factors += Factor(8, "Your training frequency has dipped")
      }
    }

    if (f.baselineAvgGapDays != null && f.recentAvgGapDays != null && f.baselineAvgGapDays > 0f) {
      val ratio = f.recentAvgGapDays / f.baselineAvgGapDays
      when {
        ratio >= 1.8f -> factors += Factor(12, "Gaps between sessions are getting longer")
        ratio >= 1.3f -> factors += Factor(6, "Slightly longer gaps between sessions")
      }
    }

    if (f.baselineCompletionRate != null && f.recentCompletionRate != null) {
      val drop = f.baselineCompletionRate - f.recentCompletionRate
      when {
        drop >= 0.3f -> factors += Factor(12, "You're finishing fewer of your planned sessions")
        drop >= 0.15f -> factors += Factor(6, "Completion rate has slipped a little")
      }
    }

    f.volumeTrendPct?.let { t ->
      when {
        t <= -0.4f -> factors += Factor(12, "Your training volume is well down")
        t <= -0.2f -> factors += Factor(6, "Your training volume is trending down")
      }
    }

    val score = factors.sumOf { it.points }.coerceIn(0, 100)
    val risk = when {
      score >= HIGH_THRESHOLD -> DropoutRisk.HIGH
      score >= MEDIUM_THRESHOLD -> DropoutRisk.MEDIUM
      else -> DropoutRisk.LOW
    }
    return DropoutAssessment(risk, score, factors.sortedByDescending { it.points }.map { it.reason })
  }

  /** A short, kind one-liner for the in-app card / notification (never guilt-trip). */
  fun encouragement(assessment: DropoutAssessment): String = when (assessment.risk) {
    DropoutRisk.HIGH -> "It's been a while — even 15 minutes counts. Want an easy session?"
    DropoutRisk.MEDIUM -> "You've been a little less active. A short session is a great restart."
    DropoutRisk.LOW -> "You're on track — nice work."
  }

  internal fun pctOf(part: Float, whole: Float): Int = if (whole == 0f) 0 else ((part / whole) * 100).roundToInt()
}

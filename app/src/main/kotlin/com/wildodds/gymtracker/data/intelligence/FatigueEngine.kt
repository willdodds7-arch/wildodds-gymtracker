package com.wildodds.gymtracker.data.intelligence

import kotlin.math.roundToInt

/** Calm in-session fatigue indicator. */
enum class FatigueLevel { FRESH, WORKING, FATIGUED }

/** One logged working set, for fatigue scoring. */
data class FatigueSet(
  val weightKg: Float?,
  val reps: Int?,
  val rpe: Float?,
  val restSeconds: Int? = null
)

data class FatigueResult(
  val level: FatigueLevel,
  val score: Int,            // 0..100
  val suggestion: String?    // non-null only when fatigue is high; always a suggestion, never forced
)

/**
 * Pure, transparent fatigue scoring. Combines within-session set-to-set decay (reps dropping vs
 * the first set as the "expected" baseline) and RPE drift upward, plus an optional heart-rate
 * contribution when a wearable supplies it. No HR → it degrades to reps + RPE only.
 *
 * Output is a suggestion-only signal: it never removes the user's planned work.
 */
object FatigueEngine {

  private const val W_REP_DECAY = 0.55
  private const val W_RPE_DRIFT = 0.35
  private const val W_HR = 0.10           // HR contributes the top 10% only when present
  private const val RPE_DRIFT_FULL = 3.0  // a 3-point RPE rise = full drift signal
  private const val WORKING_THRESHOLD = 25
  private const val FATIGUED_THRESHOLD = 50

  /**
   * @param exercises each inner list = one exercise's logged sets, in order.
   * @return null when there isn't enough logged data yet to show an indicator.
   */
  fun score(
  exercises: List<List<FatigueSet>>,
  avgHeartRate: Int? = null,
  peakHeartRate: Int? = null
  ): FatigueResult? {
  val repSeqs = exercises.map { it.mapNotNull { s -> s.reps } }.filter { it.size >= 2 }
  val rpeSeqs = exercises.map { it.mapNotNull { s -> s.rpe } }.filter { it.size >= 2 }
  if (repSeqs.isEmpty() && rpeSeqs.isEmpty() && avgHeartRate == null) return null

  val avgDecay = repSeqs.map { reps ->
  val first = reps.first().toDouble()
  if (first <= 0.0) 0.0 else ((first - reps.last()) / first).coerceIn(0.0, 1.0)
  }.avgOrZero()

  val avgRpeDrift = rpeSeqs.map { rpes ->
  ((rpes.last() - rpes.first()) / RPE_DRIFT_FULL).coerceIn(0.0, 1.0)
  }.avgOrZero()

  val hrScore = avgHeartRate?.let { ((it - 130).coerceIn(0, 50)) / 50.0 } ?: 0.0

  // Weights sum to 1.0; with HR absent the max is 0.9 (HR simply doesn't add its 10%).
  val raw = W_REP_DECAY * avgDecay + W_RPE_DRIFT * avgRpeDrift + W_HR * hrScore
  val score = (raw * 100).roundToInt().coerceIn(0, 100)

  val level = when {
  score >= FATIGUED_THRESHOLD -> FatigueLevel.FATIGUED
  score >= WORKING_THRESHOLD -> FatigueLevel.WORKING
  else -> FatigueLevel.FRESH
  }
  val suggestion = if (level == FatigueLevel.FATIGUED)
  "Fatigue's high — trimming a back-off set or easing load on what's left is fine." else null
  return FatigueResult(level, score, suggestion)
  }

  private fun List<Double>.avgOrZero(): Double = if (isEmpty()) 0.0 else average()
}

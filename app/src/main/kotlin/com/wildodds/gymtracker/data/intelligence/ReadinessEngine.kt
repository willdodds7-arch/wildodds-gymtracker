package com.wildodds.gymtracker.data.intelligence

import kotlin.math.roundToInt

/** Calm readiness verdict: train as planned, ease off, or rest. */
enum class ReadinessLevel { GOOD, CAUTION, REST }

/**
 * Everything the readiness score is computed from. Every field is optional so the engine degrades
 * gracefully: with no wearable it runs on training load alone; with nothing at all it returns null.
 *
 * Baselines are the user's own recent averages — readiness is relative to *you*, not population norms.
 */
data class ReadinessInput(
  val sleepMinutes: Int? = null,
  val restingHr: Int? = null,
  val restingHrBaseline: Float? = null,
  val hrvRmssd: Double? = null,
  val hrvBaseline: Double? = null,
  val recentStrain: Int? = null,          // 1..5, last completed session
  val recentFatigueScore: Int? = null,    // 0..100, last completed session
  val daysSinceLastSession: Int? = null   // schedule context
)

data class ReadinessResult(
  val level: ReadinessLevel,
  val score: Int,              // 0..100, higher = more ready
  val headline: String,        // "Good to train" / "Consider a lighter session" / "Rest recommended"
  val reason: String,          // one-line, the dominant driver
  val usedWearable: Boolean    // false → computed from training load only (show the fallback note)
)

/**
 * Pure, transparent readiness scoring. Starts from a "fully ready" 100 and subtracts evidence-based
 * penalties: short sleep, resting HR elevated vs the user's baseline, HRV suppressed vs baseline, and
 * a demanding recent session (which fades once the user has had a rest day). A long lay-off adds a
 * small "well rested" bonus. The biggest single penalty becomes the one-line reason.
 *
 * No Android deps → fully unit-testable and deterministic (same inputs → same verdict, every time).
 * This is the decision-maker; any LLM use is limited to rephrasing [reason], never to deciding.
 */
object ReadinessEngine {

  // Level thresholds on the 0..100 readiness score.
  const val GOOD_THRESHOLD = 70
  const val REST_THRESHOLD = 45   // below this → rest; [REST, GOOD) → caution

  // A penalty paired with the human reason it represents; the largest drives the headline reason.
  private data class Driver(val penalty: Int, val reason: String)

  fun score(input: ReadinessInput): ReadinessResult? {
    val usedWearable = input.sleepMinutes != null ||
      (input.restingHr != null && input.restingHrBaseline != null) ||
      (input.hrvRmssd != null && input.hrvBaseline != null)
    val hasLoad = input.recentStrain != null || input.recentFatigueScore != null

    // Nothing to go on at all → no card.
    if (!usedWearable && !hasLoad) return null

    val drivers = mutableListOf<Driver>()

    // ── Sleep ──
    input.sleepMinutes?.let { mins ->
      when {
        mins >= 420 -> {}
        mins >= 360 -> drivers += Driver(8, "You slept a little under 7 hours")
        mins >= 300 -> drivers += Driver(20, "You slept under 6 hours")
        else        -> drivers += Driver(35, "You slept under 5 hours")
      }
    }

    // ── Resting heart rate vs baseline (elevated = under-recovered) ──
    if (input.restingHr != null && input.restingHrBaseline != null) {
      val delta = input.restingHr - input.restingHrBaseline
      when {
        delta >= 7f -> drivers += Driver(25, "Resting heart rate is well above your baseline")
        delta >= 4f -> drivers += Driver(13, "Resting heart rate is up vs your baseline")
      }
    }

    // ── HRV vs baseline (suppressed = under-recovered) ──
    if (input.hrvRmssd != null && input.hrvBaseline != null && input.hrvBaseline > 0.0) {
      val ratio = input.hrvRmssd / input.hrvBaseline
      when {
        ratio <= 0.75 -> drivers += Driver(25, "HRV is well below your baseline")
        ratio <= 0.88 -> drivers += Driver(13, "HRV is below your baseline")
      }
    }

    // ── Recent training load — but only if you haven't already had a rest day ──
    val rested = (input.daysSinceLastSession ?: 0) >= 2
    if (!rested) {
      val veryHard = (input.recentFatigueScore ?: 0) >= 70 || input.recentStrain == 5
      val hard     = (input.recentFatigueScore ?: 0) in 50..69 || input.recentStrain == 4
      when {
        veryHard -> drivers += Driver(18, "Yesterday's session was very demanding")
        hard     -> drivers += Driver(9, "Yesterday's session was demanding")
      }
    }

    val penalties = drivers.sumOf { it.penalty }
    // A genuine lay-off means you're more recovered, not less.
    val bonus = if ((input.daysSinceLastSession ?: 0) >= 3) 10 else 0
    val score = (100 - penalties + bonus).coerceIn(0, 100)

    val level = when {
      score >= GOOD_THRESHOLD -> ReadinessLevel.GOOD
      score >= REST_THRESHOLD -> ReadinessLevel.CAUTION
      else -> ReadinessLevel.REST
    }

    val headline = when (level) {
      ReadinessLevel.GOOD -> "Good to train"
      ReadinessLevel.CAUTION -> "Consider a lighter session"
      ReadinessLevel.REST -> "Rest recommended"
    }

    val reason = drivers.maxByOrNull { it.penalty }?.reason
      ?: if (usedWearable) "Sleep and recovery markers look solid." else "Recent training load looks manageable."

    return ReadinessResult(level, score, headline, reason, usedWearable)
  }

  /** Convenience for callers/tests: a one-line strength label for the score. */
  fun describe(result: ReadinessResult): String =
    "${result.headline} (${result.score}/100) — ${result.reason}"

  internal fun Double.pct(): Int = (this * 100).roundToInt()
}

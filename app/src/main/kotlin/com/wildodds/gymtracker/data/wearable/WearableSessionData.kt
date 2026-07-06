package com.wildodds.gymtracker.data.wearable

import java.time.Instant

/** Heart-rate summary for a single session. [series] is a coarse sampling over the session. */
data class HrResult(
  val avgBpm: Int,
  val peakBpm: Int,
  val series: List<Int>
)

/** A single timestamped heart-rate reading, as flattened from a wearable source. */
data class HrSample(val time: Instant, val bpm: Int)

/** A night's sleep, summarised. Used by the Phase 4B recovery features. */
data class SleepResult(
  val totalMinutes: Int,
  val startedAt: Instant,
  val endedAt: Instant
)

/**
 * A recovery snapshot for "right now" (Phase 4B): last night's sleep, today's resting HR and HRV,
 * each alongside the user's own recent baseline so readiness can be judged relative to them. Any
 * field may be null when that signal isn't available/permitted; an all-null snapshot is reported as
 * null by the provider so callers fall back to training-load-only readiness.
 */
data class RecoverySnapshot(
  val sleepMinutes: Int? = null,
  val restingHr: Int? = null,
  val restingHrBaseline: Float? = null,
  val hrvRmssd: Double? = null,
  val hrvBaseline: Double? = null
) {
  val isEmpty: Boolean
    get() = sleepMinutes == null && restingHr == null && restingHrBaseline == null &&
      hrvRmssd == null && hrvBaseline == null
}

/**
 * Source of wearable-derived session data. Defined in Phase 2 so the post-session summary could
 * render heart rate the moment a real provider arrived; Phase 4 supplies the real Health Connect
 * implementation ([HealthConnectWearableSessionData]). [NoOpWearableSessionData] (and any source
 * without a given signal) returns null, so every caller degrades cleanly to "no HR" / "no recovery".
 *
 * The sleep / resting-HR / HRV readers exist now for Phase 4B (recovery & readiness); they default
 * to null here so providers that don't supply them — and the whole app when no wearable is present —
 * keep working untouched.
 */
interface WearableSessionData {
  /** Heart rate over [start]..[end], or null if no wearable data is available. */
  suspend fun heartRateForSession(start: Instant, end: Instant): HrResult?

  /** Total sleep overlapping [start]..[end] (typically the night before), or null. (Phase 4B) */
  suspend fun sleepForNight(start: Instant, end: Instant): SleepResult? = null

  /** Most recent resting heart rate in [start]..[end] in bpm, or null. (Phase 4B) */
  suspend fun restingHeartRate(start: Instant, end: Instant): Int? = null

  /** Most recent HRV (RMSSD, milliseconds) in [start]..[end], or null. (Phase 4B) */
  suspend fun hrvRmssdMillis(start: Instant, end: Instant): Double? = null

  /**
   * A recovery snapshot relative to [now] — last night's sleep plus today's resting HR / HRV against
   * the user's recent baseline. Null when nothing is available. (Phase 4B readiness) Default null so
   * sources without recovery data degrade cleanly.
   */
  suspend fun recoverySnapshot(now: Instant): RecoverySnapshot? = null

  /**
   * Write a completed workout back to the health platform so the user's wider ecosystem sees it
   * (Phase 5B). Permission-gated, optional, best-effort: returns false (not an error) when the
   * platform is unavailable or write permission isn't granted. Default false = no-op source.
   */
  suspend fun writeWorkoutSession(start: Instant, end: Instant, title: String, kcal: Double? = null): Boolean = false
}

/** The default, data-free provider. Always returns null for every signal. */
object NoOpWearableSessionData : WearableSessionData {
  override suspend fun heartRateForSession(start: Instant, end: Instant): HrResult? = null
}

/** Compact JSON encoding for the stored HR series (e.g. "[72,80,95]"). Empty list → null. */
fun encodeHrSeries(series: List<Int>): String? =
  if (series.isEmpty()) null else series.joinToString(",", "[", "]")

fun decodeHrSeries(json: String?): List<Int> =
  json?.trim()?.trim('[', ']')
  ?.split(',')
  ?.mapNotNull { it.trim().toIntOrNull() }
  ?: emptyList()

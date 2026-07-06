package com.wildodds.gymtracker.data.wearable

import java.time.Instant

/** Whether a usable Health Connect provider exists on this device. */
enum class WearableAvailability {
  /** Installed, supported, ready to read (subject to per-record permission). */
  AVAILABLE,
  /** Provider present but too old — the user needs to update Health Connect. */
  UPDATE_REQUIRED,
  /** Not installed or unsupported on this device/SDK. */
  NOT_AVAILABLE
}

/**
 * Health Connect read-permission strings. These are the exact tokens Health Connect returns from
 * `getGrantedPermissions()` and that the manifest declares, so the [HealthConnectWearableSessionData]
 * provider can permission-gate reads without importing any Health Connect class — keeping the
 * provider pure-JVM and unit-testable. Phase 4A uses heart rate; sleep/resting/HRV are read for 4B.
 */
object WearableHealthPermissions {
  const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
  const val READ_SLEEP = "android.permission.health.READ_SLEEP"
  const val READ_RESTING_HEART_RATE = "android.permission.health.READ_RESTING_HEART_RATE"
  const val READ_HRV = "android.permission.health.READ_HEART_RATE_VARIABILITY"

  // Phase 5B — write workouts back to Health Connect.
  const val WRITE_EXERCISE = "android.permission.health.WRITE_EXERCISE"
  const val WRITE_CALORIES = "android.permission.health.WRITE_TOTAL_CALORIES_BURNED"

  /** Everything we request in one grant flow (read + write), so a single connect covers all features. */
  val ALL: Set<String> = setOf(
    READ_HEART_RATE, READ_SLEEP, READ_RESTING_HEART_RATE, READ_HRV, WRITE_EXERCISE, WRITE_CALORIES
  )
}

/**
 * Thin, mockable seam over Health Connect I/O. The real implementation
 * ([RealHealthConnectGateway]) wraps `HealthConnectClient`; tests substitute a fake. Every reader
 * is nullable/empty-safe and must never throw — availability and permission are the caller's gate.
 */
interface HealthConnectGateway {
  /** Cheap, synchronous SDK-status check (safe even when nothing is installed). */
  fun availability(): WearableAvailability

  /** The read permissions currently granted to this app, or empty when none/unavailable. */
  suspend fun grantedHealthPermissions(): Set<String>

  suspend fun readHeartRateSamples(start: Instant, end: Instant): List<HrSample>
  suspend fun readSleep(start: Instant, end: Instant): SleepResult?
  suspend fun readRestingHeartRate(start: Instant, end: Instant): Int?
  suspend fun readHrvRmssd(start: Instant, end: Instant): Double?

  // Series readers (Phase 4B) — all values in the window, ascending by time, for baseline averages.
  // Defaulted so existing/fake gateways need no changes.
  suspend fun readRestingHeartRateSeries(start: Instant, end: Instant): List<Int> = emptyList()
  suspend fun readHrvSeries(start: Instant, end: Instant): List<Double> = emptyList()

  /** Write a strength-training session (Phase 5B). Returns true on success. Defaulted = unsupported. */
  suspend fun writeExerciseSession(start: Instant, end: Instant, title: String, kcal: Double?): Boolean = false
}

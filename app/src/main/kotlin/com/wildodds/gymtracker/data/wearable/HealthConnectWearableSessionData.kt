package com.wildodds.gymtracker.data.wearable

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * The real wearable source, backed by Health Connect via an injected [HealthConnectGateway].
 *
 * Graceful degradation is the contract: every read first checks availability and the specific
 * read permission, returning null/empty when either is missing — so a missing/denied Health
 * Connect is just "no data", never an error. All work runs off the main thread on [io].
 *
 * Privacy: this only reads the windows the user actually trained/slept in, and the results are
 * handed straight to the on-device summary/fatigue logic. Nothing is transmitted (there is no
 * backend); only the HR series the user generates is persisted, on their own SessionSummary.
 */
class HealthConnectWearableSessionData(
  private val gateway: HealthConnectGateway,
  private val io: CoroutineDispatcher = Dispatchers.IO
) : WearableSessionData {

  private suspend fun <T> gated(permission: String, read: suspend () -> T?): T? = withContext(io) {
    if (gateway.availability() != WearableAvailability.AVAILABLE) return@withContext null
    if (permission !in gateway.grantedHealthPermissions()) return@withContext null
    runCatching { read() }.getOrNull()
  }

  override suspend fun heartRateForSession(start: Instant, end: Instant): HrResult? =
    gated(WearableHealthPermissions.READ_HEART_RATE) {
      HrAggregator.aggregate(gateway.readHeartRateSamples(start, end))
    }

  override suspend fun sleepForNight(start: Instant, end: Instant): SleepResult? =
    gated(WearableHealthPermissions.READ_SLEEP) { gateway.readSleep(start, end) }

  override suspend fun restingHeartRate(start: Instant, end: Instant): Int? =
    gated(WearableHealthPermissions.READ_RESTING_HEART_RATE) { gateway.readRestingHeartRate(start, end) }

  override suspend fun hrvRmssdMillis(start: Instant, end: Instant): Double? =
    gated(WearableHealthPermissions.READ_HRV) { gateway.readHrvRmssd(start, end) }

  /**
   * Builds the recovery snapshot (Phase 4B). Each signal is read and permission-gated independently,
   * so a partly-granted setup still yields what it can. "Today" = the last ~36h; the baseline is the
   * user's own average over the trailing ~3 weeks (excluding the last two days, so it isn't dragged
   * by today). All off-main-thread; an all-null snapshot becomes null.
   */
  override suspend fun recoverySnapshot(now: Instant): RecoverySnapshot? = withContext(io) {
    if (gateway.availability() != WearableAvailability.AVAILABLE) return@withContext null
    val granted = runCatching { gateway.grantedHealthPermissions() }.getOrDefault(emptySet())

    val todayFrom = now.minus(Duration.ofHours(36))
    val baseFrom = now.minus(Duration.ofDays(21))
    val baseTo = now.minus(Duration.ofDays(2))

    suspend fun <T> ifGranted(perm: String, read: suspend () -> T?): T? =
      if (perm in granted) runCatching { read() }.getOrNull() else null

    val sleepMinutes = ifGranted(WearableHealthPermissions.READ_SLEEP) {
      gateway.readSleep(now.minus(Duration.ofHours(16)), now)?.totalMinutes
    }
    val restingHr = ifGranted(WearableHealthPermissions.READ_RESTING_HEART_RATE) {
      gateway.readRestingHeartRate(todayFrom, now)
    }
    val restingHrBaseline = ifGranted(WearableHealthPermissions.READ_RESTING_HEART_RATE) {
      gateway.readRestingHeartRateSeries(baseFrom, baseTo).takeIf { it.isNotEmpty() }?.average()?.toFloat()
    }
    val hrv = ifGranted(WearableHealthPermissions.READ_HRV) {
      gateway.readHrvRmssd(todayFrom, now)
    }
    val hrvBaseline = ifGranted(WearableHealthPermissions.READ_HRV) {
      gateway.readHrvSeries(baseFrom, baseTo).takeIf { it.isNotEmpty() }?.average()
    }

    val snapshot = RecoverySnapshot(sleepMinutes, restingHr, restingHrBaseline, hrv, hrvBaseline)
    if (snapshot.isEmpty) null else snapshot
  }

  override suspend fun writeWorkoutSession(start: Instant, end: Instant, title: String, kcal: Double?): Boolean =
    withContext(io) {
      if (gateway.availability() != WearableAvailability.AVAILABLE) return@withContext false
      if (WearableHealthPermissions.WRITE_EXERCISE !in gateway.grantedHealthPermissions()) return@withContext false
      runCatching { gateway.writeExerciseSession(start, end, title, kcal) }.getOrDefault(false)
    }
}

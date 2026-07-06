package com.wildodds.gymtracker.data.wearable

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The production [HealthConnectGateway]: wraps `HealthConnectClient`. This is the single file that
 * imports Health Connect record types — every other wearable class stays framework-free.
 *
 * Robustness over correctness-of-data: every method is wrapped so a thrown exception (provider
 * uninstalled mid-session, permission revoked, transient error) degrades to null/empty rather than
 * crashing. The provider above still gates on availability + permission first.
 */
class RealHealthConnectGateway(context: Context) : HealthConnectGateway {

  private val appContext = context.applicationContext

  override fun availability(): WearableAvailability =
    when (runCatching { HealthConnectClient.getSdkStatus(appContext) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)) {
      HealthConnectClient.SDK_AVAILABLE -> WearableAvailability.AVAILABLE
      HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> WearableAvailability.UPDATE_REQUIRED
      else -> WearableAvailability.NOT_AVAILABLE
    }

  // Built only when the provider is actually available; null otherwise so reads short-circuit.
  private val client: HealthConnectClient? by lazy {
    if (availability() == WearableAvailability.AVAILABLE)
      runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull()
    else null
  }

  override suspend fun grantedHealthPermissions(): Set<String> =
    runCatching { client?.permissionController?.getGrantedPermissions() ?: emptySet() }
      .getOrDefault(emptySet())

  override suspend fun readHeartRateSamples(start: Instant, end: Instant): List<HrSample> =
    runCatching {
      val c = client ?: return emptyList()
      c.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end)))
        .records
        .flatMap { record -> record.samples.map { HrSample(it.time, it.beatsPerMinute.toInt()) } }
    }.getOrDefault(emptyList())

  override suspend fun readSleep(start: Instant, end: Instant): SleepResult? =
    runCatching {
      val c = client ?: return null
      val sessions = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, end))).records
      if (sessions.isEmpty()) return null
      val totalMinutes = sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
      SleepResult(
        totalMinutes = totalMinutes,
        startedAt = sessions.minOf { it.startTime },
        endedAt = sessions.maxOf { it.endTime }
      )
    }.getOrNull()

  override suspend fun readRestingHeartRate(start: Instant, end: Instant): Int? =
    runCatching {
      val c = client ?: return null
      c.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(start, end)))
        .records.maxByOrNull { it.time }?.beatsPerMinute?.toInt()
    }.getOrNull()

  override suspend fun readHrvRmssd(start: Instant, end: Instant): Double? =
    runCatching {
      val c = client ?: return null
      c.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(start, end)))
        .records.maxByOrNull { it.time }?.heartRateVariabilityMillis
    }.getOrNull()

  override suspend fun readRestingHeartRateSeries(start: Instant, end: Instant): List<Int> =
    runCatching {
      val c = client ?: return emptyList()
      c.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(start, end)))
        .records.sortedBy { it.time }.map { it.beatsPerMinute.toInt() }
    }.getOrDefault(emptyList())

  override suspend fun readHrvSeries(start: Instant, end: Instant): List<Double> =
    runCatching {
      val c = client ?: return emptyList()
      c.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(start, end)))
        .records.sortedBy { it.time }.map { it.heartRateVariabilityMillis }
    }.getOrDefault(emptyList())

  override suspend fun writeExerciseSession(start: Instant, end: Instant, title: String, kcal: Double?): Boolean =
    runCatching {
      val c = client ?: return false
      val zone = ZoneId.systemDefault().rules
      val records = buildList {
        add(ExerciseSessionRecord(
          startTime = start,
          startZoneOffset = zone.getOffset(start),
          endTime = end,
          endZoneOffset = zone.getOffset(end),
          exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
          title = title,
          metadata = Metadata()
        ))
        if (kcal != null && kcal > 0.0) {
          add(TotalCaloriesBurnedRecord(
            startTime = start,
            startZoneOffset = zone.getOffset(start),
            endTime = end,
            endZoneOffset = zone.getOffset(end),
            energy = Energy.kilocalories(kcal),
            metadata = Metadata()
          ))
        }
      }
      c.insertRecords(records)
      true
    }.getOrDefault(false)
}

package com.wildodds.gymtracker.data.wearable

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Tests the Health-Connect-backed provider through a fake gateway: it parses a granted source,
 * stays silent (null) when the permission is denied or the provider is unavailable, and does its
 * work off the calling thread. No Android / Health Connect runtime needed.
 */
class HealthConnectWearableSessionDataTest {

  private val start = Instant.ofEpochSecond(1_000)
  private val end = Instant.ofEpochSecond(4_600)

  private fun samples(vararg bpm: Int): List<HrSample> =
    bpm.mapIndexed { i, v -> HrSample(Instant.ofEpochSecond(1_000L + i * 60), v) }

  private class FakeGateway(
    private val avail: WearableAvailability = WearableAvailability.AVAILABLE,
    private val granted: Set<String> = WearableHealthPermissions.ALL,
    private val hrSamples: List<HrSample> = emptyList()
  ) : HealthConnectGateway {
    @Volatile var hrRead = false
    @Volatile var readThread: Thread? = null
    override fun availability() = avail
    override suspend fun grantedHealthPermissions() = granted
    override suspend fun readHeartRateSamples(start: Instant, end: Instant): List<HrSample> {
      hrRead = true
      readThread = Thread.currentThread()
      return hrSamples
    }
    override suspend fun readSleep(start: Instant, end: Instant): SleepResult? = null
    override suspend fun readRestingHeartRate(start: Instant, end: Instant): Int? = null
    override suspend fun readHrvRmssd(start: Instant, end: Instant): Double? = null

    @Volatile var wroteTitle: String? = null
    override suspend fun writeExerciseSession(start: Instant, end: Instant, title: String, kcal: Double?): Boolean {
      wroteTitle = title
      return true
    }
  }

  @Test
  fun parsesAvgPeakSeriesWhenAvailableAndPermitted() = runTest {
    val gateway = FakeGateway(hrSamples = samples(60, 100, 140, 80))
    val provider = HealthConnectWearableSessionData(gateway)

    val hr = provider.heartRateForSession(start, end)
    assertNotNull(hr)
    assertEquals(95, hr!!.avgBpm)
    assertEquals(140, hr.peakBpm)
    assertEquals(listOf(60, 100, 140, 80), hr.series)
  }

  @Test
  fun permissionDeniedReturnsNullAndNeverReads() = runTest {
    val gateway = FakeGateway(granted = emptySet(), hrSamples = samples(60, 100, 140))
    val provider = HealthConnectWearableSessionData(gateway)

    assertNull(provider.heartRateForSession(start, end))
    assertEquals("must not query health data without permission", false, gateway.hrRead)
  }

  @Test
  fun unavailableProviderReturnsNull() = runTest {
    val gateway = FakeGateway(avail = WearableAvailability.NOT_AVAILABLE, hrSamples = samples(60, 100))
    val provider = HealthConnectWearableSessionData(gateway)

    assertNull(provider.heartRateForSession(start, end))
    assertEquals(false, gateway.hrRead)
  }

  @Test
  fun emptySourceReturnsNull() = runTest {
    val provider = HealthConnectWearableSessionData(FakeGateway(hrSamples = emptyList()))
    assertNull(provider.heartRateForSession(start, end))
  }

  @Test
  fun writesWorkoutWhenWritePermissionGranted() = runTest {
    val gateway = FakeGateway(granted = WearableHealthPermissions.ALL)
    val provider = HealthConnectWearableSessionData(gateway)
    val ok = provider.writeWorkoutSession(start, end, "Push day", kcal = 300.0)
    assertEquals(true, ok)
    assertEquals("Push day", gateway.wroteTitle)
  }

  @Test
  fun writeWorkoutDeniedWithoutWritePermission() = runTest {
    val gateway = FakeGateway(granted = setOf(WearableHealthPermissions.READ_HEART_RATE))
    val provider = HealthConnectWearableSessionData(gateway)
    val ok = provider.writeWorkoutSession(start, end, "Push day")
    assertEquals(false, ok)
    assertNull("must not write without permission", gateway.wroteTitle)
  }

  @Test
  fun readsOffTheCallingThread() = runTest {
    val gateway = FakeGateway(hrSamples = samples(70, 90))
    val provider = HealthConnectWearableSessionData(gateway)
    val callerThread = Thread.currentThread()

    provider.heartRateForSession(start, end)

    assertNotNull("gateway should have been queried", gateway.readThread)
    assertNotEquals(
      "Health Connect read must run off the calling thread (on Dispatchers.IO)",
      callerThread, gateway.readThread
    )
  }
}

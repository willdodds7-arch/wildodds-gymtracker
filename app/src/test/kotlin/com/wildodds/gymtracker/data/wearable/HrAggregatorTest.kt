package com.wildodds.gymtracker.data.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pure-JVM tests for the heart-rate aggregation/parsing core (no Android, no Health Connect). */
class HrAggregatorTest {

  private fun samples(vararg bpm: Int): List<HrSample> =
    bpm.mapIndexed { i, v -> HrSample(Instant.ofEpochSecond(100L * i), v) }

  @Test
  fun emptyInputYieldsNull() {
    assertNull(HrAggregator.aggregate(emptyList()))
  }

  @Test
  fun allNoiseYieldsNull() {
    // 0 and 300 are outside the plausible 20..240 band → dropped → nothing left.
    assertNull(HrAggregator.aggregate(samples(0, 300, 250, 5)))
  }

  @Test
  fun computesAvgPeakAndPreservesShortSeries() {
    val r = HrAggregator.aggregate(samples(60, 100, 140, 80))!!
    assertEquals(95, r.avgBpm)        // (60+100+140+80)/4
    assertEquals(140, r.peakBpm)
    assertEquals(listOf(60, 100, 140, 80), r.series)  // <= MAX_SERIES_POINTS → untouched
  }

  @Test
  fun dropsNoiseButKeepsValidReadings() {
    val r = HrAggregator.aggregate(samples(70, 300, 90))!!  // 300 is noise
    assertEquals(80, r.avgBpm)
    assertEquals(90, r.peakBpm)
    assertEquals(listOf(70, 90), r.series)
  }

  @Test
  fun sortsBySampleTimeBeforeBuildingSeries() {
    val unordered = listOf(
      HrSample(Instant.ofEpochSecond(30), 120),
      HrSample(Instant.ofEpochSecond(10), 80),
      HrSample(Instant.ofEpochSecond(20), 100)
    )
    assertEquals(listOf(80, 100, 120), HrAggregator.aggregate(unordered)!!.series)
  }

  @Test
  fun longSeriesIsDownsampledToTheCap() {
    val many = (1..1000).map { HrSample(Instant.ofEpochSecond(it.toLong()), 100 + (it % 40)) }
    val series = HrAggregator.aggregate(many)!!.series
    assertEquals(HrAggregator.MAX_SERIES_POINTS, series.size)
  }

  @Test
  fun downsampleHelperEdgeCases() {
    assertEquals(listOf(1, 2, 3), HrAggregator.downsample(listOf(1, 2, 3), 40)) // already small
    assertTrue(HrAggregator.downsample(listOf(1, 2, 3), 0).isEmpty())            // target 0
    assertEquals(2, HrAggregator.downsample(listOf(1, 2, 3, 4), 2).size)         // bucketed
  }
}

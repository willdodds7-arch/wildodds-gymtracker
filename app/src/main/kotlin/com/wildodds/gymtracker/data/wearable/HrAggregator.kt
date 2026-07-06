package com.wildodds.gymtracker.data.wearable

import kotlin.math.roundToInt

/**
 * Pure, Android-free reduction of raw heart-rate samples into the [HrResult] the summary stores.
 * Kept separate from any wearable I/O so the parsing/aggregation can be unit-tested directly and
 * reused by any future HR source. No framework dependencies → fast JVM tests.
 */
object HrAggregator {

  /** Upper bound on the stored series length — enough for a readable chart, small enough to persist. */
  const val MAX_SERIES_POINTS = 40

  /** Physiologically plausible band; values outside it are treated as sensor noise and dropped. */
  private val PLAUSIBLE_BPM = 20..240

  /**
   * @return avg/peak/down-sampled series over [samples], or null when there is no usable reading
   *         (empty input, or every sample outside the plausible band). Null → the summary shows no HR.
   */
  fun aggregate(samples: List<HrSample>): HrResult? {
    val bpms = samples.sortedBy { it.time }.map { it.bpm }.filter { it in PLAUSIBLE_BPM }
    if (bpms.isEmpty()) return null
    val avg = bpms.average().roundToInt()
    val peak = bpms.max()
    return HrResult(avgBpm = avg, peakBpm = peak, series = downsample(bpms, MAX_SERIES_POINTS))
  }

  /**
   * Bucket-average [values] down to at most [target] points, preserving overall shape. Returns the
   * input unchanged when it already fits. [target] <= 0 yields an empty series.
   */
  fun downsample(values: List<Int>, target: Int): List<Int> {
    if (target <= 0) return emptyList()
    if (values.size <= target) return values
    val bucketSize = values.size.toDouble() / target
    return (0 until target).map { i ->
      val from = (i * bucketSize).toInt()
      val to = ((i + 1) * bucketSize).toInt().coerceAtMost(values.size)
      val slice = values.subList(from, to.coerceAtLeast(from + 1))
      slice.average().roundToInt()
    }
  }
}

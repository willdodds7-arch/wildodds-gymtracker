package com.wildodds.gymtracker.data.analytics

import com.wildodds.gymtracker.data.ExerciseInfo
import com.wildodds.gymtracker.data.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendAggregatorTest {

  // Simple muscle lookup: Bench → chest (primary) + triceps (secondary); Row → lats.
  private val lookup: (String) -> ExerciseInfo? = { name ->
  when {
  name.contains("Bench", true) -> ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.TRICEPS))
  name.contains("Row", true) -> ExerciseInfo(listOf(MuscleGroup.LATS), emptyList())
  else -> null
  }
  }

  private fun row(week: Int, name: String, w: Float, reps: Int, at: Long) =
  TrendSetRow(week, name, 0, w, reps, at)

  // Day timestamps for filtering tests.
  private val w1 = 1_000_000L; private val w2 = 2_000_000L; private val w3 = 3_000_000L

  @Test
  fun estimated1rmPrDetectionFromSyntheticLog() {
  val rows = listOf(
  row(1, "Bench Press", 100f, 5, w1),
  row(2, "Bench Press", 102.5f, 5, w2),   // weight PR + e1rm PR
  row(3, "Bench Press", 102.5f, 5, w3),   // no PR (matched, not exceeded)
  row(4, "Bench Press", 100f, 8, 4_000_000L)  // rep PR + e1rm PR (8 reps), not a weight PR
  )
  val data = TrendAggregator.aggregate(rows, emptyList(), mapOf(1 to 1, 2 to 1, 3 to 1, 4 to 1), lookup)
  val series = data.prs.first { it.exerciseName == "Bench Press" }
  assertEquals(4, series.points.size)
  // week 1 is the first record → a PR baseline
  assertTrue(series.points[0].isWeightPr && series.points[0].isE1rmPr)
  // week 2: new weight PR
  assertTrue(series.points[1].isWeightPr)
  // week 3: no new PR
  assertFalse(series.points[2].isWeightPr)
  assertFalse(series.points[2].isE1rmPr)
  // week 4: rep PR (8 > 5) and e1rm PR, but NOT a weight PR (100 < 102.5)
  assertTrue(series.points[3].isRepPr)
  assertTrue(series.points[3].isE1rmPr)
  assertFalse(series.points[3].isWeightPr)
  }

  @Test
  fun volumeAggregationWeightsMusclesPrimary1xSecondaryHalf() {
  val rows = listOf(row(1, "Bench Press", 100f, 10, w1))  // volume 1000
  val data = TrendAggregator.aggregate(rows, emptyList(), mapOf(1 to 1), lookup)
  val v = data.volume.first()
  assertEquals(1000f, v.totalKg, 0.1f)
  assertEquals(1000f, v.perMuscle[MuscleGroup.CHEST]!!, 0.1f)       // primary 1.0×
  assertEquals(500f, v.perMuscle[MuscleGroup.TRICEPS]!!, 0.1f)      // secondary 0.5×
  }

  @Test
  fun balanceTrendsPushVsPull() {
  val rows = listOf(
  row(1, "Bench Press", 100f, 10, w1),   // push (chest+triceps)
  row(1, "Barbell Row", 80f, 10, w1)     // pull (lats)
  )
  val data = TrendAggregator.aggregate(rows, emptyList(), mapOf(1 to 2), lookup)
  val b = data.balance.first()
  assertTrue(b.pushVolume > 0f)
  assertTrue(b.pullVolume > 0f)
  }

  @Test
  fun consistencyStreaksAndAdherence() {
  val planned = mapOf(1 to 3, 2 to 3, 3 to 3)
  val comps = listOf(1 to w1, 1 to w1, 1 to w1, 2 to w2, 2 to w2, 2 to w2, 3 to w3) // 3,3,1
  val data = TrendAggregator.aggregate(emptyList(), comps, planned, lookup)
  val c = data.consistency
  assertEquals(listOf(3, 3, 1), c.perWeek.map { it.completed })
  assertEquals(2, c.longestStreak)         // weeks 1 & 2 fully done
  assertEquals(0, c.currentStreak)         // week 3 incomplete
  assertEquals(78, c.adherencePct)         // 7/9 → 78%
  }

  @Test
  fun dateRangeFilteringExcludesOutOfRangeData() {
  val rows = listOf(
  row(1, "Bench Press", 100f, 5, w1),
  row(2, "Bench Press", 110f, 5, w2),
  row(3, "Bench Press", 120f, 5, w3)
  )
  val comps = listOf(1 to w1, 2 to w2, 3 to w3)
  val planned = mapOf(1 to 1, 2 to 1, 3 to 1)
  // Keep only week 2.
  val data = TrendAggregator.aggregate(rows, comps, planned, lookup, DateRange(1_500_000L, 2_500_000L))
  assertEquals(listOf(2), data.volume.map { it.week })
  assertEquals(1, data.consistency.perWeek.sumOf { it.completed })  // only week 2's completion counted
  // Only one week of bench data left → no PR series (needs ≥2 weeks).
  assertTrue(data.prs.isEmpty())
  }
}

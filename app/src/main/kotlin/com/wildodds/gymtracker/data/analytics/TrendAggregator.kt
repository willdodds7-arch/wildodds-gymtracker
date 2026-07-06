package com.wildodds.gymtracker.data.analytics

import com.wildodds.gymtracker.data.ExerciseInfo
import com.wildodds.gymtracker.data.MuscleGroup
import kotlin.math.roundToInt

/** One logged set, flattened with its week + timestamp (for trend aggregation). */
data class TrendSetRow(
  val week: Int,
  val exerciseName: String,
  val orderIndex: Int,
  val weightKg: Float?,
  val reps: Int?,
  val completedAt: Long
)

data class DateRange(val startMillis: Long, val endMillis: Long)

data class WeekVolume(val week: Int, val totalKg: Float, val perMuscle: Map<MuscleGroup, Float>)

data class LiftPrPoint(
  val week: Int, val est1rm: Float,
  val isE1rmPr: Boolean, val isWeightPr: Boolean, val isRepPr: Boolean
)
data class LiftPrSeries(val exerciseName: String, val points: List<LiftPrPoint>)

data class WeekConsistency(val week: Int, val completed: Int, val planned: Int)
data class ConsistencyStats(
  val perWeek: List<WeekConsistency>,
  val currentStreak: Int, val longestStreak: Int, val adherencePct: Int
)

data class BalancePoint(val week: Int, val pushVolume: Float, val pullVolume: Float)

data class TrendData(
  val volume: List<WeekVolume>,
  val prs: List<LiftPrSeries>,
  val consistency: ConsistencyStats,
  val balance: List<BalancePoint>
)

/**
 * Pure trend aggregation (no Android, no IO). The repository loads the flat rows on IO and calls
 * this; keeping it pure makes the math unit-testable. Muscle attribution is injected via
 * [muscleLookup] (ExerciseLibrary), and a [DateRange] filters by each set's completion time.
 */
object TrendAggregator {

  /** Epley estimated 1RM. */
  fun epley(weightKg: Float, reps: Int): Float = weightKg * (1f + reps / 30f)

  fun aggregate(
  setRows: List<TrendSetRow>,
  completions: List<Pair<Int, Long>>,      // (week, completedAtMillis)
  plannedPerWeek: Map<Int, Int>,
  muscleLookup: (String) -> ExerciseInfo?,
  range: DateRange? = null
  ): TrendData {
  val rows = setRows.filter { it.weightKg != null && it.reps != null && inRange(it.completedAt, range) }
  val comps = completions.filter { inRange(it.second, range) }
  val volume = volumeByWeek(rows, muscleLookup)
  return TrendData(
  volume = volume,
  prs = prsByExercise(rows),
  consistency = consistency(comps, plannedPerWeek),
  balance = volume.map { wv -> BalancePoint(wv.week, categorySum(wv.perMuscle, "Push"), categorySum(wv.perMuscle, "Pull")) }
  )
  }

  private fun inRange(t: Long, range: DateRange?) = range == null || (t in range.startMillis..range.endMillis)

  private fun categorySum(perMuscle: Map<MuscleGroup, Float>, category: String): Float =
  perMuscle.entries.filter { it.key.category == category }.sumOf { it.value.toDouble() }.toFloat()

  private fun volumeByWeek(rows: List<TrendSetRow>, muscleLookup: (String) -> ExerciseInfo?): List<WeekVolume> =
  rows.groupBy { it.week }.toSortedMap().map { (week, weekRows) ->
  var total = 0f
  val perMuscle = mutableMapOf<MuscleGroup, Float>()
  for (r in weekRows) {
  val vol = (r.weightKg ?: 0f) * (r.reps ?: 0)
  total += vol
  val info = muscleLookup(r.exerciseName) ?: continue
  info.primary.forEach { m -> perMuscle[m] = (perMuscle[m] ?: 0f) + vol }
  info.secondary.forEach { m -> perMuscle[m] = (perMuscle[m] ?: 0f) + vol * 0.5f }
  }
  WeekVolume(week, total, perMuscle)
  }

  private fun prsByExercise(rows: List<TrendSetRow>): List<LiftPrSeries> =
  rows.groupBy { it.exerciseName }
  .filter { it.value.map { r -> r.week }.distinct().size >= 2 }   // need a trend
  .map { (name, exRows) ->
  var maxE1rm = -1f; var maxWeight = -1f; var maxReps = -1
  val points = exRows.groupBy { it.week }.toSortedMap().map { (week, sets) ->
  val bestE1rm = sets.maxOf { epley(it.weightKg!!, it.reps!!) }
  val bestWeight = sets.maxOf { it.weightKg!! }
  val bestReps = sets.maxOf { it.reps!! }
  val e1rmPr = bestE1rm > maxE1rm; val wPr = bestWeight > maxWeight; val rPr = bestReps > maxReps
  if (e1rmPr) maxE1rm = bestE1rm
  if (wPr) maxWeight = bestWeight
  if (rPr) maxReps = bestReps
  LiftPrPoint(week, bestE1rm, e1rmPr, wPr, rPr)
  }
  LiftPrSeries(name, points)
  }
  .sortedBy { it.exerciseName }

  private fun consistency(comps: List<Pair<Int, Long>>, plannedPerWeek: Map<Int, Int>): ConsistencyStats {
  val completedByWeek = comps.groupingBy { it.first }.eachCount()
  val weeks = plannedPerWeek.keys.sorted()
  val perWeek = weeks.map { WeekConsistency(it, completedByWeek[it] ?: 0, plannedPerWeek[it] ?: 0) }

  val lastActive = comps.maxOfOrNull { it.first } ?: 0
  val considered = weeks.filter { it <= lastActive }
  val totalCompleted = considered.sumOf { completedByWeek[it] ?: 0 }
  val totalPlanned = considered.sumOf { plannedPerWeek[it] ?: 0 }
  val adherence = if (totalPlanned > 0) (100.0 * totalCompleted / totalPlanned).roundToInt() else 0

  val hits = considered.map { w ->
  val planned = plannedPerWeek[w] ?: 0
  planned > 0 && (completedByWeek[w] ?: 0) >= planned
  }
  var longest = 0; var run = 0
  for (h in hits) { if (h) { run++; longest = maxOf(longest, run) } else run = 0 }
  var current = 0
  for (h in hits.asReversed()) { if (h) current++ else break }

  return ConsistencyStats(perWeek, current, longest, adherence)
  }
}

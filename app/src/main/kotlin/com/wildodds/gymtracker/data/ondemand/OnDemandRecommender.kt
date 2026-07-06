package com.wildodds.gymtracker.data.ondemand

import com.wildodds.gymtracker.data.MuscleGroup
import com.wildodds.gymtracker.data.intelligence.Equipment

/**
 * Pure, context-aware ranking + filtering for the on-demand library. Ranks sessions that
 * COMPLEMENT the current block (top up under-worked muscle groups) above those that duplicate
 * already well-covered training. No Android deps → unit-testable.
 */
object OnDemandRecommender {

  /** Minimum effective weekly sets per muscle (MEV) used to judge "under-worked". */
  val MEV: Map<MuscleGroup, Float> = mapOf(
  MuscleGroup.CHEST to 10f, MuscleGroup.FRONT_DELT to 8f, MuscleGroup.SIDE_DELT to 12f,
  MuscleGroup.REAR_DELT to 10f, MuscleGroup.LATS to 10f, MuscleGroup.UPPER_BACK to 10f,
  MuscleGroup.LOWER_BACK to 6f, MuscleGroup.TRAPS to 10f, MuscleGroup.BICEPS to 10f,
  MuscleGroup.TRICEPS to 10f, MuscleGroup.QUADS to 10f, MuscleGroup.HAMSTRINGS to 10f,
  MuscleGroup.GLUTES to 8f, MuscleGroup.CALVES to 12f, MuscleGroup.ABS to 10f
  )

  /** Per-muscle normalized deficit (0 = at/above MEV, 1 = no work at all). */
  fun deficits(weeklySetsByMuscle: Map<MuscleGroup, Float>, mev: Map<MuscleGroup, Float> = MEV): Map<MuscleGroup, Float> =
  mev.mapValues { (muscle, min) ->
  val have = weeklySetsByMuscle[muscle] ?: 0f
  ((min - have) / min).coerceIn(0f, 1f)
  }

  /**
   * Ranks [sessions] so those targeting the most under-worked groups come first, and attaches a
   * reason naming that group. Sessions that don't complement keep a null reason and sort last.
   */
  fun rank(
  sessions: List<OnDemandSession>,
  weeklySetsByMuscle: Map<MuscleGroup, Float>,
  mev: Map<MuscleGroup, Float> = MEV
  ): List<OnDemandSession> {
  val deficit = deficits(weeklySetsByMuscle, mev)
  return sessions
  .map { s ->
  val focusDeficits = s.muscleFocus.associateWith { deficit[it] ?: 0f }
  val score = focusDeficits.values.sum()
  val topMuscle = focusDeficits.maxByOrNull { it.value }?.takeIf { it.value > 0f }?.key
  val reason = topMuscle?.let { "Your week is light on ${it.display} — this tops it up." }
  Scored(s.copy(reason = reason), score)
  }
  .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.session.title })
  .map { it.session }
  }

  fun filter(sessions: List<OnDemandSession>, f: OnDemandFilter): List<OnDemandSession> =
  sessions.filter { s ->
  (f.maxDurationMin == null || s.durationMin <= f.maxDurationMin) &&
  (f.muscle == null || f.muscle in s.muscleFocus) &&
  (f.equipment == null || s.equipment.all { it == f.equipment || it == Equipment.BODYWEIGHT })
  }

  private data class Scored(val session: OnDemandSession, val score: Float)
}

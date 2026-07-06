package com.wildodds.gymtracker.data.tools

import kotlin.math.abs

/** Result of loading a barbell: the plates to put on ONE side, largest-first. */
data class PlateResult(
  val perSide: List<Float>,    // plates on each side, e.g. [20, 20] for 100kg on a 20kg bar
  val achievedWeight: Float,   // bar + 2 × sum(perSide) — the closest weight that can be loaded ≤ target
  val isExact: Boolean         // true when achievedWeight == target
) {
  /** True when only the empty bar can be loaded (target ≤ bar, or no plate fits). */
  val barOnly: Boolean get() = perSide.isEmpty()
}

/**
 * Barbell plate-loading math. Pure and unit-agnostic (kg today; a lb plate set + display
 * conversion is a later toggle — the math doesn't change).
 */
object PlateMath {

  private const val EPS = 1e-4f

  /** Standard kg plate set, largest first. */
  val DEFAULT_PLATES_KG = listOf(25f, 20f, 15f, 10f, 5f, 2.5f, 1.25f)

  const val DEFAULT_BAR_KG = 20f

  /**
   * Greedy per-side breakdown for [targetWeight] on a [barWeight] bar using [plates] (any order,
   * unlimited count of each). Returns the closest loadable weight not exceeding the target.
   *
   * Edge cases:
   *  - target ≤ bar → empty (only the bar); exact iff target == bar.
   *  - unmakeable target (no combination hits it) → the largest achievable weight below it,
   *    with isExact = false.
   */
  fun computePerSide(
  targetWeight: Float,
  barWeight: Float = DEFAULT_BAR_KG,
  plates: List<Float> = DEFAULT_PLATES_KG
  ): PlateResult {
  if (targetWeight <= barWeight + EPS) {
  return PlateResult(emptyList(), barWeight, isExact = abs(targetWeight - barWeight) < EPS)
  }

  var remainingPerSide = (targetWeight - barWeight) / 2f
  val used = mutableListOf<Float>()
  for (plate in plates.sortedDescending()) {
  while (remainingPerSide >= plate - EPS) {
  used.add(plate)
  remainingPerSide -= plate
  }
  }

  val achieved = barWeight + 2f * used.sum()
  return PlateResult(used, achieved, isExact = abs(targetWeight - achieved) < EPS)
  }

  /** Collapses a per-side list into "count × plate" groups, largest first, for display. */
  fun groupForDisplay(perSide: List<Float>): List<Pair<Int, Float>> =
  perSide.groupingBy { it }.eachCount()
  .toList()
  .sortedByDescending { it.first }
  .map { (plate, count) -> count to plate }
}

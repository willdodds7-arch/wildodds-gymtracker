package com.wildodds.gymtracker.data.profile

import kotlin.math.roundToInt

/**
 * Pure %1RM-prescription helpers (unit-tested; no Android/DB). A program exercise's
 * `pct1rmTarget` can be:
 *  - a single value      — "80%"            → every set at 80%
 *  - a range             — "70-80%"         → every set at the midpoint (75%)
 *  - a per-set slash-list — "58.5/67.5/76.5%" → set 1 at 58.5%, set 2 at 67.5%, set 3 at 76.5%
 *    (extra sets repeat the last entry) — how 5/3/1 waves three different percentages inside
 *    one exercise.
 */
object PctPrefill {

  /**
   * Per-set prefill weights for [setCount] sets, from the user's configured 1RMs. Null when the
   * exercise has no %1RM target, doesn't map to a tracked main lift, or that 1RM isn't set.
   * Weights are rounded to 2.5 kg.
   */
  fun weights(
    exerciseName: String,
    pctTarget: String,
    setCount: Int,
    oneRms: Map<MainLift, Float>
  ): List<Float>? {
    if (pctTarget.isBlank()) return null
    val lift = MainLift.forExercise(exerciseName) ?: return null
    val oneRm = oneRms[lift]?.takeIf { it > 0f } ?: return null
    val fracs = fractions(pctTarget) ?: return null
    return (0 until setCount).map { i ->
      val frac = fracs.getOrElse(i) { fracs.last() }
      roundToPlate(oneRm * frac)
    }
  }

  /** Parse a pct-target string to per-set fractions (see class doc). Null if nothing usable. */
  fun fractions(s: String): List<Float>? {
    val num = Regex("\\d+(?:\\.\\d+)?")
    if (s.contains("/")) {
      val fracs = s.split("/")
        .mapNotNull { seg -> num.find(seg)?.value?.toFloatOrNull() }
        .map { it / 100f }
        .filter { it in 0.2f..1.2f }
      return fracs.takeIf { it.isNotEmpty() }
    }
    val nums = num.findAll(s).map { it.value.toFloat() }.toList()
    if (nums.isEmpty()) return null
    val pct = if (nums.size >= 2) (nums[0] + nums[1]) / 2f else nums[0]
    return listOf(pct / 100f).takeIf { (pct / 100f) in 0.2f..1.2f }
  }

  /**
   * The main lifts a program's %1RM prescriptions depend on — i.e. the 1RMs the user must have
   * configured before starting it. Input is (exerciseName, pct1rmTarget) pairs so this doesn't
   * couple to any specific program model.
   */
  fun requiredLifts(exercises: List<Pair<String, String>>): Set<MainLift> =
    exercises
      .filter { (_, pct) -> pct.isNotBlank() }
      .mapNotNull { (name, _) -> MainLift.forExercise(name) }
      .toSet()

  fun roundToPlate(kg: Float): Float = (kg / 2.5f).roundToInt() * 2.5f
}

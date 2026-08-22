package com.wildodds.gymtracker.data.builder

/**
 * EVOLVING SETS
 *
 * Most exercises aren't "3 × 8 at one weight". They ramp: a couple of warm-ups, then working sets,
 * maybe a heavy top set and a lighter back-off. Typing that out set by set is tedious to prescribe
 * and easy to get wrong, so a [SetShape] generates the whole ladder from two numbers — how many
 * working sets, and the rep target.
 *
 * The key idea is that generated loads are stored as a PERCENTAGE of the exercise's top working
 * set ([PlanSet.loadPercent]), never as fixed kilos. That is what makes them *evolve*:
 *
 *  - the prescription survives the user getting stronger — change the top set and the whole ramp
 *    recomputes, rather than every row needing a manual edit
 *  - it is unit-agnostic, so it reads correctly in kg or lb without conversion
 *  - during a workout it resolves against what the user is actually lifting that day
 *
 * A percentage is only ever a suggestion for the prefill. Nothing here writes to a log, and a user
 * who types over a suggested weight simply keeps their own number.
 */
enum class SetShape(val display: String) {

  /** Every working set the same. The default, and right for most accessory work. */
  STRAIGHT("Straight sets"),

  /** Two warm-ups ramping in, then the working sets. The most common "real" prescription. */
  WARMUP_RAMP("Warm-up + working"),

  /** Each set heavier than the last. */
  ASCENDING("Ascending"),

  /** Up to a peak, then back down. */
  PYRAMID("Pyramid"),

  /** Heaviest first while fresh, then lighter. */
  REVERSE_PYRAMID("Reverse pyramid"),

  /** One heavy top set, then lighter back-off volume. */
  TOP_SET_BACKOFF("Top set + back-off"),

  /** Working sets, then immediate lighter drops with no rest. */
  DROP_SET("Drop set");

  /** One line explaining what this shape does, shown under the picker. */
  val explanation: String
    get() = when (this) {
      STRAIGHT -> "Every set at the same weight and reps."
      WARMUP_RAMP -> "Two lighter warm-up sets, then your working sets."
      ASCENDING -> "Start lighter and add weight each set."
      PYRAMID -> "Build up to your heaviest set, then come back down."
      REVERSE_PYRAMID -> "Heaviest set first while you're fresh, then lighter."
      TOP_SET_BACKOFF -> "One heavy set, then lighter sets for volume."
      DROP_SET -> "Working sets, then drop the weight and go again with no rest."
    }

  /**
   * Build the ladder.
   *
   * @param workingSets how many sets that count toward volume (warm-ups are added on top)
   * @param reps        the rep target for the working sets
   * @param restSeconds rest for the working sets; warm-ups always get a short rest
   */
  fun build(workingSets: Int, reps: String, restSeconds: Int = 90): List<PlanSet> {
    val n = workingSets.coerceIn(1, 12)
    return when (this) {
      STRAIGHT -> working(n, reps, restSeconds, percents = List(n) { 100 })

      WARMUP_RAMP -> warmups(listOf(50, 70), reps) +
        working(n, reps, restSeconds, percents = List(n) { 100 })

      // Ramp the last quarter of the load across the working sets: 90, 95, 100 for three.
      ASCENDING -> working(n, reps, restSeconds, percents = ramp(n, from = 85, to = 100))

      PYRAMID -> {
        val up = ramp((n + 1) / 2, from = 85, to = 100)
        val down = ramp(n - up.size, from = 95, to = 90).reversed()
        working(n, reps, restSeconds, percents = up + down)
      }

      REVERSE_PYRAMID -> working(n, reps, restSeconds, percents = ramp(n, from = 100, to = 85))

      TOP_SET_BACKOFF -> warmups(listOf(60, 80), reps) +
        listOf(planSet(SetType.WORKING, reps, restSeconds, 100)) +
        working(n - 1, reps, restSeconds, percents = List((n - 1).coerceAtLeast(0)) { 85 })

      // Drops follow immediately — that's the whole point, so rest is zero.
      DROP_SET -> working(n, reps, restSeconds, percents = List(n) { 100 }) +
        listOf(
          planSet(SetType.DROP, "AMRAP", 0, 75),
          planSet(SetType.DROP, "AMRAP", 0, 50)
        )
    }
  }

  private fun warmups(percents: List<Int>, reps: String) = percents.map {
    // Warm-ups use a fixed easy rep count, not the working target — nobody warms up for 5 × 3.
    planSet(SetType.WARMUP, if (it <= 55) "8" else "5", restSeconds = 60, percent = it)
  }

  private fun working(n: Int, reps: String, rest: Int, percents: List<Int>) =
    (0 until n.coerceAtLeast(0)).map { i ->
      planSet(SetType.WORKING, reps, rest, percents.getOrElse(i) { 100 })
    }

  private fun planSet(type: SetType, reps: String, restSeconds: Int, percent: Int) =
    PlanSet(type = type, reps = reps, restSeconds = restSeconds, loadPercent = percent)

  /** [n] evenly-spaced whole percentages from [from] to [to], inclusive. */
  private fun ramp(n: Int, from: Int, to: Int): List<Int> {
    if (n <= 0) return emptyList()
    if (n == 1) return listOf(to)
    val step = (to - from).toFloat() / (n - 1)
    return (0 until n).map { Math.round(from + step * it) }
  }

  companion object {
    /**
     * Best guess at which shape a hand-built list of sets already represents, so the builder can
     * show the right thing selected instead of always claiming "Straight sets".
     */
    fun detect(sets: List<PlanSet>): SetShape {
      if (sets.isEmpty()) return STRAIGHT
      val warmups = sets.count { it.type == SetType.WARMUP }
      val drops = sets.count { it.type == SetType.DROP }
      val working = sets.filter { it.type.countsAsVolume && it.type != SetType.DROP }
      val percents = working.mapNotNull { it.loadPercent }

      if (drops > 0) return DROP_SET
      if (percents.size < 2) return if (warmups > 0) WARMUP_RAMP else STRAIGHT

      val allSame = percents.distinct().size == 1
      if (allSame) return if (warmups > 0) WARMUP_RAMP else STRAIGHT

      val rising = percents.zipWithNext().all { (a, b) -> b >= a }
      val falling = percents.zipWithNext().all { (a, b) -> b <= a }
      val peakIndex = percents.indexOf(percents.max())

      return when {
        // A single heavy set followed by clearly lighter ones is a back-off, not a plain descent.
        peakIndex == 0 && percents.drop(1).distinct().size == 1 && percents.size > 2 -> TOP_SET_BACKOFF
        rising -> ASCENDING
        falling -> REVERSE_PYRAMID
        peakIndex in 1 until percents.lastIndex -> PYRAMID
        else -> STRAIGHT
      }
    }

    /**
     * Resolve a percentage against a top-set load. Returns null when there is nothing to work
     * from, so the UI shows the percentage rather than inventing a weight.
     *
     * Rounded to the nearest [rounding] (2.5 by default — the smallest plate pair most gyms have)
     * because "62.4 kg" is not a loadable number.
     */
    fun resolveLoad(topLoad: Float?, percent: Int?, rounding: Float = 2.5f): Float? {
      if (topLoad == null || topLoad <= 0f || percent == null) return null
      if (rounding <= 0f) return topLoad * percent / 100f
      val raw = topLoad * percent / 100f
      return Math.round(raw / rounding) * rounding
    }

    /** "60 kg" / "60%" / "" — what a set row shows when the top load may or may not be known. */
    fun describeLoad(topLoad: Float?, percent: Int?, literal: String, unit: String = "kg"): String {
      if (literal.isNotBlank()) return literal
      val resolved = resolveLoad(topLoad, percent)
      return when {
        resolved != null -> "${resolved.tidy()} $unit"
        percent != null -> "$percent%"
        else -> ""
      }
    }
  }
}

/** "60" not "60.0", "62.5" not "62.500". */
internal fun Float.tidy(): String =
  if (this % 1f == 0f) toInt().toString() else toString().trimEnd('0').trimEnd('.')

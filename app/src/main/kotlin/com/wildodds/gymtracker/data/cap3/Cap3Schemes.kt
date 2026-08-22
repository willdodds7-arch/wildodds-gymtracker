package com.wildodds.gymtracker.data.cap3

/**
 * The CAP3 week-by-week numbers, transcribed from the source spreadsheet.
 *
 * Nothing here is derived at runtime — the percentages *and* the per-set rounding direction both
 * come from the sheet, because the sheet rounds some top sets down and some up. `Cap3EngineTest`
 * pins the whole table against the published 3-week fixture, so any edit here that drifts from the
 * sheet fails loudly.
 */

/** A run of identical sets: [sets] × [reps] at [pct] of TM, snapped with [rounding]. */
data class Cap3SetGroup(
  val pct: Double,
  val rounding: Cap3Rounding,
  val reps: Int,
  val sets: Int,
  val type: Cap3SetType = Cap3SetType.FIXED
)

/** The Major-Effort test: warm up, then pick one of three AMRAP options. */
object Cap3MajorEffort {
  /** Every ME option is CEILING(TM × pct) — the same three percentages for all three lifts. */
  val options = listOf(0.795 to 6, 0.835 to 4, 0.885 to 2)
}

/**
 * Lift 2's prescription for a day.
 *
 * Weights come from the inverse-Epley solve in [Cap3Engine.secondaryWeight]; [tier] selects the rep
 * value fed to it. The tier normally equals the EMOM fail threshold, but the sheet nudges two days
 * a tier off their labels (week 2 day 13 a tier heavier, week 3 day 20 a tier lighter), so it is
 * stated explicitly on every row rather than derived.
 */
sealed interface Cap3SecondaryScheme {
  val tier: Int

  /** The normal flow: top set → MRS → EMOM until you cannot hit [emomFailReps]. */
  data class Amrap(
    override val tier: Int,
    val topReps: Int,
    val mrsReps: Int,
    val emomFailReps: Int
  ) : Cap3SecondaryScheme

  /** A de-emphasis week: a rep-range top set, then a fixed backoff instead of the EMOM. */
  data class Backoff(
    override val tier: Int,
    val repRange: String,
    val backoffSets: Int,
    val backoffReps: Int
  ) : Cap3SecondaryScheme
}

object Cap3Schemes {

  /** Which day slot holds the Major-Effort test each week: W1 deadlift, W2 squat, W3 bench. */
  val majorEffortSlot: Map<Int, Cap3DaySlot> = mapOf(
    1 to Cap3DaySlot.DEADLIFT_B,
    2 to Cap3DaySlot.SQUAT_A,
    3 to Cap3DaySlot.BENCH_A
  )

  fun majorEffortLift(week: Int): Cap3LiftCategory =
    majorEffortSlot.getValue(week).primary.category

  private val F = Cap3Rounding.FLOOR
  private val C = Cap3Rounding.CEILING
  private val AMRAP = Cap3SetType.AMRAP

  /**
   * Lift 1 for each (week, day slot). Absent entries are the week's ME test.
   * The final group of every scheme is the yellow AMRAP top set.
   */
  private val primary: Map<Pair<Int, Cap3DaySlot>, List<Cap3SetGroup>> = mapOf(
    // ── Bench ────────────────────────────────────────────────────────────────
    (1 to Cap3DaySlot.BENCH_A) to listOf(
      Cap3SetGroup(0.72, C, 4, 4), Cap3SetGroup(0.78, F, 4, 2), Cap3SetGroup(0.81, F, 4, 1, AMRAP)
    ),
    (1 to Cap3DaySlot.BENCH_B) to listOf(
      Cap3SetGroup(0.67, F, 6, 5), Cap3SetGroup(0.75, F, 5, 2), Cap3SetGroup(0.78, F, 5, 1, AMRAP)
    ),
    (2 to Cap3DaySlot.BENCH_A) to listOf(
      Cap3SetGroup(0.75, F, 3, 4), Cap3SetGroup(0.78, F, 3, 1), Cap3SetGroup(0.81, F, 3, 2),
      Cap3SetGroup(0.83, C, 3, 1, AMRAP)
    ),
    (2 to Cap3DaySlot.BENCH_B) to listOf(
      Cap3SetGroup(0.72, C, 5, 4), Cap3SetGroup(0.78, F, 4, 4), Cap3SetGroup(0.83, C, 4, 1, AMRAP)
    ),
    // week 3 BENCH_A is the ME test
    (3 to Cap3DaySlot.BENCH_B) to listOf(
      Cap3SetGroup(0.61, C, 8, 4), Cap3SetGroup(0.67, F, 6, 2), Cap3SetGroup(0.72, C, 6, 1, AMRAP)
    ),

    // ── Deadlift ─────────────────────────────────────────────────────────────
    (1 to Cap3DaySlot.DEADLIFT_A) to listOf(
      Cap3SetGroup(0.72, C, 5, 4), Cap3SetGroup(0.75, F, 4, 4), Cap3SetGroup(0.78, F, 4, 1, AMRAP)
    ),
    // week 1 DEADLIFT_B is the ME test
    (2 to Cap3DaySlot.DEADLIFT_A) to listOf(
      Cap3SetGroup(0.61, C, 8, 4), Cap3SetGroup(0.64, F, 6, 2), Cap3SetGroup(0.68, F, 6, 1, AMRAP)
    ),
    (2 to Cap3DaySlot.DEADLIFT_B) to listOf(
      Cap3SetGroup(0.75, F, 4, 4), Cap3SetGroup(0.77, F, 4, 2), Cap3SetGroup(0.79, F, 4, 1, AMRAP)
    ),
    (3 to Cap3DaySlot.DEADLIFT_A) to listOf(
      Cap3SetGroup(0.68, F, 6, 5), Cap3SetGroup(0.69, C, 5, 2), Cap3SetGroup(0.72, C, 5, 1, AMRAP)
    ),
    (3 to Cap3DaySlot.DEADLIFT_B) to listOf(
      Cap3SetGroup(0.79, F, 3, 4), Cap3SetGroup(0.81, F, 3, 3), Cap3SetGroup(0.82, C, 3, 1, AMRAP)
    ),

    // ── Squat ────────────────────────────────────────────────────────────────
    (1 to Cap3DaySlot.SQUAT_A) to listOf(
      Cap3SetGroup(0.78, F, 3, 4), Cap3SetGroup(0.82, F, 3, 3), Cap3SetGroup(0.85, F, 3, 1, AMRAP)
    ),
    (1 to Cap3DaySlot.SQUAT_B) to listOf(
      Cap3SetGroup(0.72, C, 5, 4), Cap3SetGroup(0.75, F, 4, 4), Cap3SetGroup(0.79, C, 4, 1, AMRAP)
    ),
    // week 2 SQUAT_A is the ME test
    (2 to Cap3DaySlot.SQUAT_B) to listOf(
      Cap3SetGroup(0.61, C, 8, 4), Cap3SetGroup(0.64, F, 6, 2), Cap3SetGroup(0.68, C, 6, 1, AMRAP)
    ),
    (3 to Cap3DaySlot.SQUAT_A) to listOf(
      Cap3SetGroup(0.75, F, 4, 3), Cap3SetGroup(0.79, C, 4, 3), Cap3SetGroup(0.82, F, 4, 1, AMRAP)
    ),
    (3 to Cap3DaySlot.SQUAT_B) to listOf(
      Cap3SetGroup(0.67, F, 6, 5), Cap3SetGroup(0.69, C, 5, 2), Cap3SetGroup(0.74, F, 5, 1, AMRAP)
    )
  )

  /** Lift 2 for each (week, day slot). Every day has one. */
  private val secondary: Map<Pair<Int, Cap3DaySlot>, Cap3SecondaryScheme> = mapOf(
    (1 to Cap3DaySlot.BENCH_A) to Cap3SecondaryScheme.Amrap(tier = 5, topReps = 10, mrsReps = 8, emomFailReps = 5),
    (1 to Cap3DaySlot.DEADLIFT_A) to Cap3SecondaryScheme.Amrap(tier = 3, topReps = 6, mrsReps = 4, emomFailReps = 3),
    (1 to Cap3DaySlot.SQUAT_A) to Cap3SecondaryScheme.Amrap(tier = 4, topReps = 8, mrsReps = 6, emomFailReps = 4),
    (1 to Cap3DaySlot.BENCH_B) to Cap3SecondaryScheme.Amrap(tier = 4, topReps = 8, mrsReps = 6, emomFailReps = 4),
    (1 to Cap3DaySlot.DEADLIFT_B) to Cap3SecondaryScheme.Backoff(tier = 6, repRange = "8-12", backoffSets = 3, backoffReps = 10),
    (1 to Cap3DaySlot.SQUAT_B) to Cap3SecondaryScheme.Amrap(tier = 3, topReps = 6, mrsReps = 5, emomFailReps = 3),

    (2 to Cap3DaySlot.BENCH_A) to Cap3SecondaryScheme.Amrap(tier = 4, topReps = 8, mrsReps = 6, emomFailReps = 4),
    (2 to Cap3DaySlot.DEADLIFT_A) to Cap3SecondaryScheme.Amrap(tier = 5, topReps = 10, mrsReps = 6, emomFailReps = 5),
    (2 to Cap3DaySlot.SQUAT_A) to Cap3SecondaryScheme.Amrap(tier = 6, topReps = 12, mrsReps = 10, emomFailReps = 6),
    (2 to Cap3DaySlot.BENCH_B) to Cap3SecondaryScheme.Backoff(tier = 3, repRange = "5-8", backoffSets = 3, backoffReps = 6),
    // Sheet quirk: labels read as tier 5 but the loads are the tier-4 row.
    (2 to Cap3DaySlot.DEADLIFT_B) to Cap3SecondaryScheme.Amrap(tier = 4, topReps = 10, mrsReps = 8, emomFailReps = 5),
    (2 to Cap3DaySlot.SQUAT_B) to Cap3SecondaryScheme.Backoff(tier = 4, repRange = "6-10", backoffSets = 3, backoffReps = 5),

    (3 to Cap3DaySlot.BENCH_A) to Cap3SecondaryScheme.Amrap(tier = 6, topReps = 12, mrsReps = 10, emomFailReps = 6),
    (3 to Cap3DaySlot.DEADLIFT_A) to Cap3SecondaryScheme.Amrap(tier = 4, topReps = 8, mrsReps = 5, emomFailReps = 4),
    (3 to Cap3DaySlot.SQUAT_A) to Cap3SecondaryScheme.Amrap(tier = 5, topReps = 10, mrsReps = 8, emomFailReps = 5),
    (3 to Cap3DaySlot.BENCH_B) to Cap3SecondaryScheme.Amrap(tier = 5, topReps = 10, mrsReps = 10, emomFailReps = 5),
    // Sheet quirk: labels read as tier 4 but the loads are the tier-5 row.
    (3 to Cap3DaySlot.DEADLIFT_B) to Cap3SecondaryScheme.Amrap(tier = 5, topReps = 8, mrsReps = 6, emomFailReps = 4),
    (3 to Cap3DaySlot.SQUAT_B) to Cap3SecondaryScheme.Amrap(tier = 5, topReps = 10, mrsReps = 8, emomFailReps = 5)
  )

  /** Null when this (week, slot) is the Major-Effort test rather than a percentage scheme. */
  fun primaryScheme(week: Int, day: Cap3DaySlot): List<Cap3SetGroup>? = primary[week to day]

  fun secondaryScheme(week: Int, day: Cap3DaySlot): Cap3SecondaryScheme = secondary.getValue(week to day)

  fun isMajorEffort(week: Int, day: Cap3DaySlot): Boolean = majorEffortSlot[week] == day
}

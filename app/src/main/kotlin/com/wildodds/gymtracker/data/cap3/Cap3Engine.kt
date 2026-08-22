package com.wildodds.gymtracker.data.cap3

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Prices an nSuns CAP3 lift from the user's current training max.
 *
 * Everything is a pure function of the config, so weights are never stored: change a training max
 * or the display unit and the next render follows. The session screen calls [block] for each
 * CAP3-tagged exercise.
 */
object Cap3Engine {

  /** The sheet's Epley coefficient. Deliberately the literal 0.0333, not 1/30. */
  const val EPLEY_COEFFICIENT = 0.0333

  /** The constant in the sheet's inverse-Epley solve for secondary loads. */
  private const val INVERSE_EPLEY_BASE = 0.995

  /**
   * Rep value fed to the inverse-Epley solve for each secondary tier. Calibrated against the
   * published fixture: a top set uses its own tier, its MRS uses the next tier up (which is why the
   * table runs to 7 even though no scheme is tier 7). The step from 6 to 7 is bigger than the rest
   * — that is what the sheet does, not a typo; it is what puts the tier-6 MRS at 55 rather than
   * 57.5 on the two week-2/3 squat-A and bench-A days.
   */
  private val TIER_REPS = mapOf(3 to 8, 4 to 10, 5 to 12, 6 to 14, 7 to 18)

  // ── Core formulas ───────────────────────────────────────────────────────────

  /** Snap a weight to the unit's step. Rounds the quotient first so 202.5/2.5 is 81, not 80.9999. */
  fun snap(value: Double, step: Double, rounding: Cap3Rounding): Double {
    val quotient = Math.round((value / step) * 1_000_000.0) / 1_000_000.0
    val steps = if (rounding == Cap3Rounding.FLOOR) floor(quotient) else ceil(quotient)
    return steps * step
  }

  /** Estimated 1RM, Epley, truncated to a whole unit: FLOOR(weight × (0.0333 × reps + 1), 1). */
  fun estimatedOneRepMax(weight: Double, reps: Int): Double =
    floor(weight * (EPLEY_COEFFICIENT * reps + 1))

  /** Training max from the entered reference max. */
  fun trainingMaxFrom(referenceMax: Double): Double = referenceMax * CAP3_TM_FACTOR

  /**
   * Inverse Epley: the load that equals [trainingMax] at [reps] reps. Solves the Epley curve for
   * weight rather than for a max, which is how the sheet sets every secondary-lift weight.
   */
  fun secondaryWeight(
    trainingMax: Double,
    reps: Int,
    step: Double,
    rounding: Cap3Rounding
  ): Double = snap(trainingMax / (INVERSE_EPLEY_BASE + EPLEY_COEFFICIENT * reps), step, rounding)

  private fun tierWeight(trainingMax: Double, tier: Int, step: Double, rounding: Cap3Rounding): Double =
    secondaryWeight(trainingMax, TIER_REPS.getValue(tier), step, rounding)

  // ── Blocks ──────────────────────────────────────────────────────────────────

  /**
   * The prescription for one CAP3-tagged exercise, priced from the config's current training maxes.
   * Returns null when the tag's slot has no max set yet.
   */
  fun block(config: Cap3Config, tag: Cap3Tag): Cap3LiftBlock? = when (tag.role) {
    Cap3Role.T1 -> primaryBlock(config, tag)
    Cap3Role.T2 -> secondaryBlock(config, tag, includeEmom = false)
    Cap3Role.EMOM -> secondaryBlock(config, tag, includeEmom = true)
  }

  /**
   * Every training day of a week, in order, with both main lifts priced. There are no rest days.
   * The generated program is what the app actually runs; this is the schedule view the fixture
   * test asserts against, and the readable answer to "what does week N look like".
   */
  fun week(config: Cap3Config, week: Int): List<Cap3Day> =
    Cap3DaySlot.entries.map { slot ->
      Cap3Day(
        week = week,
        dayNumber = slot.index + 1,
        title = slot.title,
        daySlot = slot,
        isMajorEffort = Cap3Schemes.isMajorEffort(week, slot),
        lift1 = primaryBlock(config, Cap3Tag(week, slot, Cap3Role.T1)),
        lift2 = secondaryBlock(config, Cap3Tag(week, slot, Cap3Role.T2), includeEmom = false)
      )
    }

  // ── Lift 1 ──────────────────────────────────────────────────────────────────

  private fun primaryBlock(config: Cap3Config, tag: Cap3Tag): Cap3LiftBlock? {
    val lift = config.lift(tag.daySlot.primary)
    if (!lift.isConfigured) return null
    val tm = config.units.fromKg(lift.trainingMaxKg)
    val step = config.units.step
    val scheme = Cap3Schemes.primaryScheme(tag.week, tag.daySlot)

    val sets = if (scheme == null) {
      // The Major-Effort test: warm up, then pick one of three options and take it for reps.
      Cap3MajorEffort.options.mapIndexed { index, (pct, reps) ->
        Cap3Set(
          label = "Option ${index + 1}",
          weight = snap(tm * pct, step, Cap3Rounding.CEILING),
          repsTarget = reps,
          repsDisplay = "$reps+",
          type = Cap3SetType.AMRAP
        )
      }
    } else {
      var number = 0
      scheme.flatMap { group ->
        val weight = snap(tm * group.pct, step, group.rounding)
        List(group.sets) {
          number++
          Cap3Set(
            label = "Set $number",
            weight = weight,
            repsTarget = group.reps,
            repsDisplay = if (group.type.isAmrapCapable) "${group.reps}+" else "${group.reps}",
            type = group.type
          )
        }
      }
    }

    return Cap3LiftBlock(
      role = Cap3Role.T1,
      slot = tag.daySlot.primary,
      exercise = lift.exercise,
      trainingMax = tm,
      units = config.units,
      sets = sets,
      isMajorEffort = scheme == null
    )
  }

  // ── Lift 2 ──────────────────────────────────────────────────────────────────

  private fun secondaryBlock(config: Cap3Config, tag: Cap3Tag, includeEmom: Boolean): Cap3LiftBlock? {
    val slotKey = config.secondarySlot(tag.daySlot)
    val lift = config.lift(slotKey)
    if (!lift.isConfigured) return null
    val tm = config.units.fromKg(lift.trainingMaxKg)
    val step = config.units.step
    val scheme = Cap3Schemes.secondaryScheme(tag.week, tag.daySlot)

    // Top set always rounds up, the lighter set below it always rounds down.
    val topWeight = tierWeight(tm, scheme.tier, step, Cap3Rounding.CEILING)
    val lowerWeight = tierWeight(tm, scheme.tier + 1, step, Cap3Rounding.FLOOR)

    val sets: List<Cap3Set>
    var emom: Cap3Emom? = null
    when (scheme) {
      is Cap3SecondaryScheme.Amrap -> {
        sets = listOf(
          Cap3Set("Top set", topWeight, scheme.topReps, "${scheme.topReps}+", Cap3SetType.MIN_THEN_OPTIONAL_AMRAP),
          Cap3Set("MRS", lowerWeight, scheme.mrsReps, "${scheme.mrsReps}+", Cap3SetType.MIN_THEN_OPTIONAL_AMRAP)
        )
        emom = Cap3Emom(weight = lowerWeight, failBelowReps = scheme.emomFailReps)
      }
      is Cap3SecondaryScheme.Backoff -> {
        // A de-emphasis week: a rep-range top set and a fixed backoff instead of the EMOM. The
        // sheet writes the range as "x 5-8"; its "x" is the multiplication sign the UI draws.
        val minReps = scheme.repRange.substringBefore('-').trim().toIntOrNull() ?: 0
        sets = listOf(
          Cap3Set("Top set", topWeight, minReps, scheme.repRange, Cap3SetType.MIN_THEN_OPTIONAL_AMRAP)
        ) + (1..scheme.backoffSets).map { index ->
          Cap3Set("Backoff $index", lowerWeight, scheme.backoffReps, "${scheme.backoffReps}", Cap3SetType.FIXED)
        }
      }
    }

    // The EMOM lives on its own page; the T2 page shows only the top set and MRS.
    if (includeEmom && emom == null) return null

    return Cap3LiftBlock(
      role = if (includeEmom) Cap3Role.EMOM else Cap3Role.T2,
      slot = slotKey,
      exercise = lift.exercise,
      trainingMax = tm,
      units = config.units,
      sets = if (includeEmom) emptyList() else sets,
      emom = if (includeEmom) emom else null
    )
  }

  /** True when this (week, day) runs an EMOM, i.e. it earns its own page. */
  fun hasEmom(week: Int, slot: Cap3DaySlot): Boolean =
    Cap3Schemes.secondaryScheme(week, slot) is Cap3SecondaryScheme.Amrap

  /** Daily secondary volume = Σ(weight × reps), the number the user chases a PR on. */
  fun secondaryVolume(sets: List<Pair<Double, Int>>): Double =
    sets.sumOf { (weight, reps) -> weight * reps }
}

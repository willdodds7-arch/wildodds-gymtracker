package com.wildodds.gymtracker.data.builder

import kotlin.math.roundToInt

/**
 * The 12-week linear progression: one exercise grows through four 3-week phases, then loops.
 *
 * ```
 * Phase  Week  Sets×Reps  Load   Focus
 * P1     1-3   3/4/5 × 5  base   add a set each week, same weight and reps
 * P2     4-6   5 × 6/7/8  base   keep week 3's weight, add a rep each week
 * P3     7-9   5/4/3 × 5  +5/10/15%   add weight, drop a set each week
 * P4     10-12 3/4/5 × 5  +15%   rebuild the sets at the new weight
 * ```
 * After week 12 it loops back to phase 1, starting from week 12's weight — so each 12-week round
 * lifts the working weight about 15%.
 *
 * The athlete chooses their own weight. When a starting weight is known the weekly target is
 * worked out and written into that week's coach note; otherwise the note gives the jump as a
 * percentage. Nothing here forces a weight onto the bar.
 *
 * Pure and Android-free: the builder ([PlanMapper]) and the session screen (via the repository)
 * both write their weeks from [weekPlan], so the two can never disagree.
 */
object TwelveWeekLinear {

  /** Tag on every stored exercise row that is part of this progression (see `Exercise.modifiers`). */
  const val MODIFIER = "linear_12wk"

  /** Every coach-note line this writes starts with this, so it can be replaced or removed cleanly. */
  const val NOTE_PREFIX = "12-week linear progression"

  const val CYCLE_WEEKS = 12

  /** One week of the cycle. [loadPercent] is relative to the round's starting weight. */
  data class Step(val week: Int, val phase: Int, val sets: Int, val reps: Int, val loadPercent: Int)

  val CYCLE: List<Step> = listOf(
    Step(1, 1, 3, 5, 100), Step(2, 1, 4, 5, 100), Step(3, 1, 5, 5, 100),
    Step(4, 2, 5, 6, 100), Step(5, 2, 5, 7, 100), Step(6, 2, 5, 8, 100),
    Step(7, 3, 5, 5, 105), Step(8, 3, 4, 5, 110), Step(9, 3, 3, 5, 115),
    Step(10, 4, 3, 5, 115), Step(11, 4, 4, 5, 115), Step(12, 4, 5, 5, 115)
  )

  fun phaseFocus(phase: Int): String = when (phase) {
    1 -> "add a set each week, same weight and reps"
    2 -> "keep week 3's weight, add a rep each week"
    3 -> "add weight, drop a set each week"
    else -> "rebuild the sets at the new weight"
  }

  /** What one program week asks for. [cycle] is 0 for the first 12 weeks, 1 for the next 12… */
  data class WeekPlan(
    val step: Step,
    val cycle: Int,
    /** Suggested weight, when a starting weight is known. */
    val load: Float?,
    val previousLoad: Float?,
    val note: String
  ) {
    val sets: Int get() = step.sets
    val reps: Int get() = step.reps
  }

  /**
   * The plan for the week [weekOffset] weeks after the progression started (0 = its first week).
   * [startLoad] is the athlete's week-1 weight, or null when they haven't given one.
   */
  fun weekPlan(weekOffset: Int, startLoad: Float?): WeekPlan {
    val off = weekOffset.coerceAtLeast(0)
    val cycle = off / CYCLE_WEEKS
    val step = CYCLE[off % CYCLE_WEEKS]
    val load = startLoad?.takeIf { it > 0f }?.let { loadFor(it, cycle, step) }
    val previous = if (off == 0) null else startLoad?.takeIf { it > 0f }?.let {
      loadFor(it, (off - 1) / CYCLE_WEEKS, CYCLE[(off - 1) % CYCLE_WEEKS])
    }
    return WeekPlan(step, cycle, load, previous, noteFor(off, step, cycle, load, previous))
  }

  /** The starting weight of round [cycle]: each round begins where the last one finished (+15%). */
  fun cycleBase(start: Float, cycle: Int): Float {
    var base = start
    repeat(cycle) { base = roundLoad(base * 1.15f) }
    return base
  }

  fun loadFor(start: Float, cycle: Int, step: Step): Float {
    val base = cycleBase(start, cycle)
    return if (step.loadPercent == 100) base else roundLoad(base * step.loadPercent / 100f)
  }

  /** Nearest loadable weight: 2.5 kg steps for barbell-sized loads, 0.5 kg for light ones. */
  fun roundLoad(v: Float): Float {
    val inc = if (v >= 40f) 2.5f else 0.5f
    return (v / inc).roundToInt() * inc
  }

  private fun kg(v: Float): String =
    if (v % 1f == 0f) "${v.toInt()} kg" else "${"%.1f".format(v).trimEnd('0').trimEnd('.')} kg"

  private fun noteFor(off: Int, step: Step, cycle: Int, load: Float?, previous: Float?): String {
    val round = if (cycle > 0) " (round ${cycle + 1})" else ""
    val head = "$NOTE_PREFIX · Week ${step.week} of 12$round · Phase ${step.phase}: ${phaseFocus(step.phase)}."
    val prescription = "${step.sets} × ${step.reps}"

    val weight = if (load != null) {
      val change = when {
        previous == null -> "your starting weight"
        load > previous -> "+${kg(load - previous).removeSuffix(" kg")} kg on last week"
        else -> "same weight as last week"
      }
      "Today $prescription at about ${kg(load)} ($change)."
    } else {
      val prevStep = if (off == 0) null else CYCLE[(off - 1) % CYCLE_WEEKS]
      val change = when {
        prevStep == null -> "use your starting weight"
        step.week == 1 -> "keep last week's weight"
        step.loadPercent > prevStep.loadPercent -> "add about 5% of your starting weight to last week's"
        else -> "same weight as last week"
      }
      "Today $prescription — $change."
    }

    val loop = if (step.week == CYCLE_WEEKS) " Next week loops back to Phase 1 (3 × 5) at this weight." else ""
    return "$head $weight$loop"
  }

  /** [notes] with any line this progression wrote removed — the coach's own notes are kept. */
  fun stripNote(notes: String): String =
    notes.lines().filterNot { it.trimStart().startsWith(NOTE_PREFIX) }.joinToString("\n").trim()

  /** The week's progression line first, then whatever else the coach wrote. */
  fun mergeNotes(existing: String, progressionNote: String): String =
    listOf(progressionNote, stripNote(existing)).filter { it.isNotBlank() }.joinToString("\n")
}

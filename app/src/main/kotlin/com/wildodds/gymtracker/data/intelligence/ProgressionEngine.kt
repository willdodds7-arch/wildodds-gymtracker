package com.wildodds.gymtracker.data.intelligence

import kotlin.math.roundToInt

/**
 * Pure-Kotlin progression rules engine. Given an exercise's recent week-over-week history, the
 * program's tracking mode, and the user's chosen progression style, it proposes a concrete,
 * conservative recommendation for next week with a plain-language rationale.
 *
 * No Android deps → fully unit-testable. It never mutates logged data; callers use the output
 * only to annotate the picker and to adjust (still-editable) carry-forward prefill.
 */

enum class TrackingMode { STRAIGHT_SETS, RPE, PERCENT_1RM }

/** What the user picked (or AUTO = let the engine choose the smart default). */
enum class ProgressionStyle { AUTO, ADD_WEIGHT, ADD_REPS, ADD_SET, HOLD, DELOAD }

enum class ProgressionAction { ADD_WEIGHT, ADD_REPS, ADD_SET, HOLD, DELOAD }

data class SetRecord(val weightKg: Float?, val reps: Int?, val rpe: Float? = null)

data class WeekRecord(val weekNumber: Int, val sets: List<SetRecord>) {
  /** The "working" set — heaviest fully-logged set, by weight then reps. */
  val topSet: SetRecord?
  get() = sets.filter { it.weightKg != null && it.reps != null }
  .maxWithOrNull(compareBy({ it.weightKg }, { it.reps }))

  /** Epley estimated 1RM of the top set, or null. */
  val est1rm: Float?
  get() = topSet?.let { ts ->
  val w = ts.weightKg ?: return null
  val r = ts.reps ?: return null
  w * (1f + r / 30f)
  }
}

data class ProgressionInput(
  val history: List<WeekRecord>,        // chronological ascending by week
  val trackingMode: TrackingMode = TrackingMode.STRAIGHT_SETS,
  val repLow: Int? = null,
  val repHigh: Int? = null,
  val rpeTarget: Float? = null,
  val style: ProgressionStyle = ProgressionStyle.AUTO,
  val loadIncrementKg: Float = 2.5f,
  val stallWeeks: Int = 3
)

data class ProgressionRecommendation(
  val action: ProgressionAction,
  val weightDeltaKg: Float = 0f,
  val repDelta: Int = 0,
  val setDelta: Int = 0,
  val rationale: String
)

object ProgressionEngine {

  private const val DELOAD_FACTOR = 0.10f  // 10% off on a stall/deload

  /** Maps an engine action to the existing progression-picker key (or null if none matches). */
  fun pickerKeyFor(action: ProgressionAction): String? = when (action) {
  ProgressionAction.ADD_WEIGHT -> "MORE_WEIGHT"
  ProgressionAction.ADD_REPS -> "MORE_REPS"
  ProgressionAction.ADD_SET -> "MORE_SETS"
  ProgressionAction.HOLD -> "NO_CHANGE"
  ProgressionAction.DELOAD -> null  // no deload pill; surfaced as a rationale chip instead
  }

  /** Maps a progression-picker key to an explicit style (unknown/null → AUTO). */
  fun styleFromPickerKey(key: String?): ProgressionStyle = when (key) {
  "MORE_WEIGHT" -> ProgressionStyle.ADD_WEIGHT
  "MORE_REPS" -> ProgressionStyle.ADD_REPS
  "MORE_SETS" -> ProgressionStyle.ADD_SET
  "BETTER_FORM", "NO_CHANGE" -> ProgressionStyle.HOLD
  else -> ProgressionStyle.AUTO
  }

  fun recommend(input: ProgressionInput): ProgressionRecommendation {
  val inc = input.loadIncrementKg
  val last = input.history.lastOrNull { it.topSet != null }
  ?: return ProgressionRecommendation(ProgressionAction.HOLD,
  rationale = "Log a few sets and a suggestion will appear next week.")

  // Explicit user choice → honour it, but compute the concrete deltas + rationale.
  when (input.style) {
  ProgressionStyle.ADD_WEIGHT -> return ProgressionRecommendation(
  ProgressionAction.ADD_WEIGHT, weightDeltaKg = inc,
  rationale = "You chose more weight — try +${fmt(inc)} kg next week.")
  ProgressionStyle.ADD_REPS -> return ProgressionRecommendation(
  ProgressionAction.ADD_REPS, repDelta = 1,
  rationale = "You chose more reps — add 1 rep per set.")
  ProgressionStyle.ADD_SET -> return ProgressionRecommendation(
  ProgressionAction.ADD_SET, setDelta = 1,
  rationale = "You chose more sets — add 1 working set.")
  ProgressionStyle.HOLD -> return ProgressionRecommendation(
  ProgressionAction.HOLD, rationale = "Holding load to consolidate before progressing.")
  ProgressionStyle.DELOAD -> return deload(last, "You chose to deload")
  ProgressionStyle.AUTO -> { /* fall through to smart rules */ }
  }

  // Smart default. Stall takes priority over everything.
  if (isStalled(input.history, input.stallWeeks)) {
  return deload(last, "No progress in ${input.stallWeeks} weeks")
  }

  return when (input.trackingMode) {
  TrackingMode.RPE -> rpeRule(last, input, inc) ?: doubleProgression(last, input, inc)
  TrackingMode.PERCENT_1RM -> ProgressionRecommendation(
  ProgressionAction.ADD_WEIGHT, weightDeltaKg = inc,
  rationale = "Ramp toward your %1RM targets — add ~${fmt(inc)} kg.")
  TrackingMode.STRAIGHT_SETS -> doubleProgression(last, input, inc)
  }
  }

  private fun rpeRule(last: WeekRecord, input: ProgressionInput, inc: Float): ProgressionRecommendation? {
  val target = input.rpeTarget ?: return null
  val rpe = last.topSet?.rpe ?: return null
  return when {
  rpe <= target - 1f -> ProgressionRecommendation(
  ProgressionAction.ADD_WEIGHT, weightDeltaKg = inc,
  rationale = "Last set RPE ${fmt(rpe)} is below your ${fmt(target)} target — add ${fmt(inc)} kg.")
  rpe > target + 0.5f -> ProgressionRecommendation(
  ProgressionAction.HOLD,
  rationale = "RPE ${fmt(rpe)} is above your ${fmt(target)} target — hold and aim for cleaner reps.")
  else -> ProgressionRecommendation(
  ProgressionAction.ADD_REPS, repDelta = 1,
  rationale = "Right at your RPE target — add a rep before adding load.")
  }
  }

  private fun doubleProgression(last: WeekRecord, input: ProgressionInput, inc: Float): ProgressionRecommendation {
  val topReps = last.topSet?.reps
  val high = input.repHigh
  return if (high != null && topReps != null && topReps >= high) {
  val low = input.repLow ?: high
  ProgressionRecommendation(
  ProgressionAction.ADD_WEIGHT, weightDeltaKg = inc,
  rationale = "Hit the top of your ${low}-${high} range — add ${fmt(inc)} kg and reset toward ${low} reps.")
  } else {
  ProgressionRecommendation(
  ProgressionAction.ADD_REPS, repDelta = 1,
  rationale = "Add a rep toward the top of your range, then add load.")
  }
  }

  private fun deload(last: WeekRecord, prefix: String): ProgressionRecommendation {
  val topWeight = last.topSet?.weightKg ?: 0f
  val drop = roundToIncrement(topWeight * DELOAD_FACTOR, 1.25f)
  return ProgressionRecommendation(
  ProgressionAction.DELOAD, weightDeltaKg = -drop,
  rationale = "$prefix — drop ~${(DELOAD_FACTOR * 100).roundToInt()}% (${fmt(drop)} kg) and rebuild.")
  }

  /**
   * Stalled = enough history and estimated 1RM has not improved over the last [n] weeks.
   * Needs n+1 data points; returns false when there isn't enough history (never a false stall).
   */
  fun isStalled(history: List<WeekRecord>, n: Int): Boolean {
  val points = history.mapNotNull { it.est1rm }
  if (points.size < n + 1) return false
  val window = points.takeLast(n + 1)
  val baseline = window.first()
  return window.drop(1).none { it > baseline * 1.001f }
  }

  // ── Carry-forward adjustment (pure) ───────────────────────────────────────────

  data class AdjustedSet(val weightKg: Float?, val reps: Int?)

  /**
   * Produces next week's prefill from [prevSets] adjusted by [rec]. This only ever feeds editable
   * prefill — it never writes logged data. Reversible: the user can edit any cell.
   */
  fun applyToCarryForward(prevSets: List<SetRecord>, rec: ProgressionRecommendation): List<AdjustedSet> {
  val base = prevSets.map { AdjustedSet(it.weightKg, it.reps) }
  return when (rec.action) {
  ProgressionAction.ADD_WEIGHT, ProgressionAction.DELOAD ->
  base.map { it.copy(weightKg = it.weightKg?.let { w -> (w + rec.weightDeltaKg).coerceAtLeast(0f) }) }
  ProgressionAction.ADD_REPS ->
  base.map { it.copy(reps = it.reps?.let { r -> (r + rec.repDelta).coerceAtLeast(0) }) }
  ProgressionAction.ADD_SET ->
  base + List(rec.setDelta.coerceAtLeast(0)) {
  AdjustedSet(base.lastOrNull()?.weightKg, base.lastOrNull()?.reps)
  }
  ProgressionAction.HOLD -> base
  }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private fun roundToIncrement(value: Float, increment: Float): Float =
  (value / increment).roundToInt() * increment

  private fun fmt(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()
}

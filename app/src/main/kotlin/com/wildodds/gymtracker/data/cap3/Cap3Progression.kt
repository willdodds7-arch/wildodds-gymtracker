package com.wildodds.gymtracker.data.cap3

/**
 * CAP3's training-max rules.
 *
 * A logged AMRAP is evaluated the moment it is entered and, when it earns a change, the new
 * training max is applied straight away and the user is told what happened — they never have to
 * confirm arithmetic they didn't ask to do.
 */
enum class Cap3ProgressionOutcome {
  /** The AMRAP beat the stored estimated max: the training max becomes that max, rounded up. */
  NEW_EST_MAX,
  /** Required reps met but no PR: one step on the training max (+2.5 kg / +5 lb). */
  STEP_UP,
  /** Required reps missed: back the training max off and push the AMRAPs next cycle. */
  REDUCE,
  /** Reps landed but nothing has changed yet. */
  HOLD
}

/**
 * The result of evaluating one AMRAP. [currentTm]/[newTm] are in the config's display unit for
 * showing to the user; [newTmKg] is what gets written.
 */
data class Cap3TmChange(
  val slot: Cap3Slot,
  val exercise: String,
  val outcome: Cap3ProgressionOutcome,
  val units: Cap3Units,
  val currentTm: Double,
  val newTm: Double,
  val newTmKg: Double,
  val newEst1Rm: Double? = null,
  val newEst1RmKg: Double? = null,
  val message: String
) {
  val isChange: Boolean get() = outcome != Cap3ProgressionOutcome.HOLD
  val delta: Double get() = newTm - currentTm
}

object Cap3Progression {

  /** A missed Major-Effort test drops the training max by this much. */
  const val MAJOR_MISS_REDUCTION = 0.05

  /** A missed secondary AMRAP drops it by this much — the middle of the sheet's "5–10%". */
  const val SECONDARY_MISS_REDUCTION = 0.075

  /**
   * The Major-Effort test. [requiredReps] is the minimum on the option the user picked (6, 4 or 2);
   * [weight] and [achievedReps] are what they actually did, in display units.
   */
  fun evaluateMajorEffort(
    config: Cap3Config,
    slot: Cap3Slot,
    weight: Double,
    achievedReps: Int,
    requiredReps: Int
  ): Cap3TmChange = evaluate(
    config, slot, weight, achievedReps, requiredReps,
    missReduction = MAJOR_MISS_REDUCTION,
    stepUpOnNoPr = true
  )

  /**
   * A yellow AMRAP on any other set. Set [endOfCycle] on the last day of week 3 so a clean cycle
   * with no rep PR still earns its one-step bump.
   */
  fun evaluateAmrap(
    config: Cap3Config,
    slot: Cap3Slot,
    weight: Double,
    achievedReps: Int,
    requiredReps: Int,
    endOfCycle: Boolean = false
  ): Cap3TmChange = evaluate(
    config, slot, weight, achievedReps, requiredReps,
    missReduction = SECONDARY_MISS_REDUCTION,
    stepUpOnNoPr = endOfCycle
  )

  private fun evaluate(
    config: Cap3Config,
    slot: Cap3Slot,
    weight: Double,
    achievedReps: Int,
    requiredReps: Int,
    missReduction: Double,
    stepUpOnNoPr: Boolean
  ): Cap3TmChange {
    val units = config.units
    val step = units.step
    val lift = config.lift(slot)
    val currentTm = units.fromKg(lift.trainingMaxKg)
    val bestEst = units.fromKg(lift.bestEst1RmKg)

    fun change(
      outcome: Cap3ProgressionOutcome,
      newTm: Double,
      message: String,
      est: Double? = null
    ) = Cap3TmChange(
      slot = slot,
      exercise = lift.exercise,
      outcome = outcome,
      units = units,
      currentTm = currentTm,
      newTm = newTm,
      newTmKg = units.toKg(newTm),
      newEst1Rm = est,
      newEst1RmKg = est?.let { units.toKg(it) },
      message = message
    )

    if (achievedReps < requiredReps) {
      val reduced = Cap3Engine.snap(currentTm * (1 - missReduction), step, Cap3Rounding.FLOOR)
      return change(
        Cap3ProgressionOutcome.REDUCE,
        reduced,
        "${lift.exercise}: missed the $requiredReps-rep target, so the training max eased back to " +
          "${fmt(reduced)} ${units.label}."
      )
    }

    val est = Cap3Engine.estimatedOneRepMax(weight, achievedReps)
    if (est > bestEst) {
      val newTm = Cap3Engine.snap(est, step, Cap3Rounding.CEILING)
      return change(
        outcome = Cap3ProgressionOutcome.NEW_EST_MAX,
        newTm = newTm,
        message = "${lift.exercise} PR: ${fmt(weight)} × $achievedReps estimates ${fmt(est)} " +
          "${units.label}. Training max is now ${fmt(newTm)} ${units.label}.",
        est = est
      )
    }

    return if (stepUpOnNoPr) {
      change(
        Cap3ProgressionOutcome.STEP_UP,
        currentTm + step,
        "${lift.exercise}: reps hit, no PR. Training max up to ${fmt(currentTm + step)} ${units.label}."
      )
    } else {
      change(Cap3ProgressionOutcome.HOLD, currentTm, "")
    }
  }

  /** Apply a change to the config. A PR also raises the max to beat next time. */
  fun apply(config: Cap3Config, change: Cap3TmChange): Cap3Config =
    if (!change.isChange) config
    else config.withTrainingMax(change.slot, change.newTmKg, change.newEst1RmKg)

  private fun fmt(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)
}

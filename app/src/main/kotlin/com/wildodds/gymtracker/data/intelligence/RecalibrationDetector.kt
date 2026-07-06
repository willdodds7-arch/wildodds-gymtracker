package com.wildodds.gymtracker.data.intelligence

/** Why a re-calibration prompt should appear. */
enum class RecalibrationTrigger { NONE, PROGRAM_COMPLETE, STALLED }

/**
 * Pure detection of when to offer goal re-calibration: program completion, or a program-wide
 * multi-week stall. Pure Kotlin so it's unit-testable and reusable.
 */
object RecalibrationDetector {

  /** True when every session in the program has been completed at least once. */
  fun isProgramComplete(totalSessions: Int, completedSessions: Int): Boolean =
  totalSessions > 0 && completedSessions >= totalSessions

  /**
   * Program-wide stall: at least [minStalledExercises] key exercises are individually stalled and
   * they make up at least [ratioThreshold] of those evaluated.
   */
  fun isProgramStalled(
  perExerciseStalled: List<Boolean>,
  minStalledExercises: Int = 2,
  ratioThreshold: Float = 0.5f
  ): Boolean {
  if (perExerciseStalled.isEmpty()) return false
  val stalled = perExerciseStalled.count { it }
  return stalled >= minStalledExercises &&
  stalled.toFloat() / perExerciseStalled.size >= ratioThreshold
  }

  /** Completion takes priority over a stall. */
  fun detect(
  totalSessions: Int,
  completedSessions: Int,
  perExerciseStalled: List<Boolean>
  ): RecalibrationTrigger = when {
  isProgramComplete(totalSessions, completedSessions) -> RecalibrationTrigger.PROGRAM_COMPLETE
  isProgramStalled(perExerciseStalled) -> RecalibrationTrigger.STALLED
  else -> RecalibrationTrigger.NONE
  }
}

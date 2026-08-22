package com.wildodds.gymtracker.data.builder

/**
 * Progression methods offered by the builder.
 *
 * Every method here only ever produces a *proposal*. Nothing in this file writes to the database or
 * mutates a saved program — [ProgressionAdvisor.propose] returns suggestions the user accepts or
 * ignores, per the "no silent mutation" principle.
 */
enum class ProgressionMethod(val display: String) {
  MANUAL("Manual"),
  DOUBLE("Double progression"),
  LINEAR_LOAD("Linear load"),
  RIR("RIR-based"),
  PERCENTAGE("Percentage-based");

  /**
   * Plain-language explanation shown under the picker, so the user never has to already know the
   * jargon to choose. Written as a single sentence in the app's voice.
   */
  fun explain(s: ProgressionSettings): String = when (this) {
    MANUAL ->
      "You choose when to change weight or reps. The app never suggests anything."
    DOUBLE ->
      "Add reps until every working set reaches the top of the rep range " +
        "(${s.repRangeLow}–${s.repRangeHigh}), then add ${s.loadIncrement.trimZeros()} and drop " +
        "back to ${s.repRangeLow}."
    LINEAR_LOAD ->
      "Add ${s.loadIncrement.trimZeros()} every session where you hit all " +
        "${s.requiredSuccessfulSets} target sets."
    RIR ->
      "Add load when a set finishes at RIR ${s.rirTarget} or easier — " +
        "that is, with ${s.rirTarget} or more reps still in the tank."
    PERCENTAGE ->
      "Work from a percentage of your training max. Load moves when the max does, not " +
        "session to session."
  }
}

data class ProgressionSettings(
  val method: ProgressionMethod = ProgressionMethod.MANUAL,
  val repRangeLow: Int = 8,
  val repRangeHigh: Int = 12,
  /** In the user's own unit — the builder never converts behind their back. */
  val loadIncrement: Float = 2.5f,
  val requiredSuccessfulSets: Int = 3,
  val rirTarget: Int = 2,
  /** Whether the app may surface suggestions at all. */
  val autoRecommend: Boolean = true,
  /**
   * Kept for clarity even though it is always true today: a suggestion is never applied without
   * the user accepting it. Exposed so the setting reads honestly rather than implying otherwise.
   */
  val requireApproval: Boolean = true,
  /** 0 = never. Deloads are proposed on the same accept-or-ignore basis. */
  val deloadEveryWeeks: Int = 0
)

/** A single non-binding suggestion. */
data class ProgressionProposal(
  val exerciseName: String,
  val summary: String,
  val detail: String
)

object ProgressionAdvisor {

  /**
   * Look at the last session's result for one exercise and propose a change, or return null when
   * the method has nothing to say. Pure: same inputs always give the same proposal.
   *
   * @param achievedReps reps hit on each working set last time
   * @param topOfRange   whether every set reached the top of the prescribed range
   */
  fun propose(
    settings: ProgressionSettings,
    exerciseName: String,
    achievedReps: List<Int>,
    lastLoad: Float,
    lastRir: Int? = null
  ): ProgressionProposal? {
    if (!settings.autoRecommend) return null
    if (achievedReps.isEmpty()) return null
    val inc = settings.loadIncrement

    return when (settings.method) {
      ProgressionMethod.MANUAL -> null

      ProgressionMethod.DOUBLE -> {
        val allAtTop = achievedReps.all { it >= settings.repRangeHigh }
        if (!allAtTop) null else ProgressionProposal(
          exerciseName,
          "Add ${inc.trimZeros()} → ${(lastLoad + inc).trimZeros()}",
          "Every working set reached ${settings.repRangeHigh} reps. Add the increment and " +
            "restart at ${settings.repRangeLow}."
        )
      }

      ProgressionMethod.LINEAR_LOAD -> {
        val hit = achievedReps.count { it >= settings.repRangeLow }
        if (hit < settings.requiredSuccessfulSets) null else ProgressionProposal(
          exerciseName,
          "Add ${inc.trimZeros()} → ${(lastLoad + inc).trimZeros()}",
          "$hit of ${achievedReps.size} sets met the target."
        )
      }

      ProgressionMethod.RIR -> {
        if (lastRir == null || lastRir < settings.rirTarget) null else ProgressionProposal(
          exerciseName,
          "Add ${inc.trimZeros()} → ${(lastLoad + inc).trimZeros()}",
          "Finished at RIR $lastRir, at or above your target of ${settings.rirTarget}."
        )
      }

      // Percentage-based load follows the training max, so there is nothing to propose per session.
      ProgressionMethod.PERCENTAGE -> null
    }
  }

  /** True when [weekNumber] lands on a deload under these settings. Week 1 is never a deload. */
  fun isDeloadWeek(settings: ProgressionSettings, weekNumber: Int): Boolean {
    val every = settings.deloadEveryWeeks
    return every > 0 && weekNumber > 1 && weekNumber % every == 0
  }
}

/** "2.5" not "2.5000", "5" not "5.0" — load increments read as a human would write them. */
internal fun Float.trimZeros(): String =
  if (this % 1f == 0f) toInt().toString() else toString().trimEnd('0').trimEnd('.')

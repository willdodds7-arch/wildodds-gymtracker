package com.wildodds.gymtracker.data.intelligence

/**
 * A non-destructive "injury accommodation" overlay on the active program: contraindicated lifts
 * (those carrying an avoided joint-stress tag) are swapped for tolerated alternatives via the 3A
 * engine, and region-appropriate prehab is inserted as guidance. Pure — never mutates the program.
 */
data class InjuryOverlayData(
  val region: BodyRegion,
  val avoidTags: Set<String>,
  /** Per-position overlay (same indexing as the session). isSubstituted = a contraindicated swap. */
  val swaps: List<TranslatedExercise>,
  val prehab: List<PrehabExercise>
) {
  val swapCount: Int get() = swaps.count { it.isSubstituted }
}

object InjuryAccommodator {

  /**
   * Builds the accommodation overlay. Only exercises tagged with one of [avoidTags] are touched;
   * everything else is kept. If a contraindicated lift has no tolerated alternative it is flagged
   * (kept, with advice) rather than swapped — never silently dropped.
   */
  fun build(
  planned: List<PlannedExercise>,
  engine: ExerciseGraphEngine,
  region: BodyRegion,
  avoidTags: Set<String>,
  prehab: List<PrehabExercise>
  ): InjuryOverlayData {
  val swaps = planned.map { p ->
  val node = engine.node(p.name)
  val contraindicated = node != null && node.jointStress.any { it in avoidTags }
  if (!contraindicated) {
  keep(p, "")
  } else {
  val sub = engine.substituteFor(p.name, SubstitutionConstraints(avoidTags = avoidTags)).firstOrNull()
  if (sub != null) {
  TranslatedExercise(
  original = p, name = sub.node.name, sets = p.sets, repsTarget = p.repsTarget,
  reason = "Easier on your ${region.display.lowercase()} — ${sub.reason}",
  isSubstituted = true, adjusted = false
  )
  } else {
  keep(p, "No tolerated alternative found — reduce load, shorten range, or skip while it settles.")
  }
  }
  }
  return InjuryOverlayData(region, avoidTags, swaps, prehab)
  }

  private fun keep(p: PlannedExercise, reason: String) = TranslatedExercise(
  original = p, name = p.name, sets = p.sets, repsTarget = p.repsTarget,
  reason = reason, isSubstituted = false, adjusted = reason.isNotBlank()
  )
}

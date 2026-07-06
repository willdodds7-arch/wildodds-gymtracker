package com.wildodds.gymtracker.data.intelligence

/**
 * Pure-Kotlin engine over an [ExerciseNode] graph. No Android dependencies.
 *
 * Lookups normalize names and fall back to a substring match; an unknown exercise degrades
 * gracefully (empty list / null) and notifies [logMiss] — it never throws.
 */
class ExerciseGraphEngine(
  val nodes: List<ExerciseNode>,
  private val logMiss: (String) -> Unit = {}
) {

  private val byKey: Map<String, ExerciseNode> = nodes.associateBy { it.key }

  /** Resolve an exercise name to a node (exact, then substring), or null + log on a miss. */
  fun node(name: String): ExerciseNode? {
  val key = normalizeExerciseName(name)
  byKey[key]?.let { return it }
  val fuzzy = nodes
  .filter { key.contains(it.key) || it.key.contains(key) }
  .maxByOrNull { it.key.length }
  if (fuzzy == null) logMiss(name)
  return fuzzy
  }

  /** Exercises sharing the same movement pattern, ranked by muscle overlap. */
  fun findSimilar(name: String): List<ScoredExercise> {
  val src = node(name) ?: return emptyList()
  if (src.pattern == MovementPattern.UNKNOWN) return emptyList()
  return nodes
  .filter { it.key != src.key && it.pattern == src.pattern }
  .map { ScoredExercise(it, similarity(src, it), similarReason(src, it)) }
  .sortedByDescending { it.score }
  }

  /** The closest easier same-pattern variation (highest difficulty still below the source), or null. */
  fun regress(name: String): ScoredExercise? {
  val src = node(name) ?: return null
  val pick = nodes
  .filter { it.key != src.key && it.pattern == src.pattern && it.difficulty < src.difficulty }
  .sortedWith(compareByDescending<ExerciseNode> { it.difficulty }.thenByDescending { muscleOverlap(src, it) })
  .firstOrNull() ?: return null
  return ScoredExercise(pick, similarity(src, pick),
  "Easier ${src.pattern.display} variation (${equipmentLabel(pick)})")
  }

  /** The closest harder same-pattern progression (lowest difficulty still above the source), or null. */
  fun progress(name: String): ScoredExercise? {
  val src = node(name) ?: return null
  val pick = nodes
  .filter { it.key != src.key && it.pattern == src.pattern && it.difficulty > src.difficulty }
  .sortedWith(compareBy<ExerciseNode> { it.difficulty }.thenByDescending { muscleOverlap(src, it) })
  .firstOrNull() ?: return null
  return ScoredExercise(pick, similarity(src, pick),
  "Harder ${src.pattern.display} progression (${equipmentLabel(pick)})")
  }

  /**
   * Same-movement substitutions filtered by [constraints]: only exercises loadable with the
   * available equipment, free of any avoided joint-stress tag, and within the difficulty cap.
   * Ranked by similarity. Empty when nothing qualifies (or the source is unknown).
   */
  fun substituteFor(name: String, constraints: SubstitutionConstraints): List<ScoredExercise> {
  val src = node(name) ?: return emptyList()
  if (src.pattern == MovementPattern.UNKNOWN) return emptyList()
  return nodes
  .filter { cand ->
  cand.key != src.key &&
  cand.pattern == src.pattern &&
  isDoable(cand, constraints.availableEquipment) &&
  cand.jointStress.none { it in constraints.avoidTags } &&
  (constraints.maxDifficulty == null || cand.difficulty <= constraints.maxDifficulty)
  }
  .map { ScoredExercise(it, similarity(src, it), substituteReason(src, it)) }
  .sortedByDescending { it.score }
  }

  // ── Scoring & reasons ─────────────────────────────────────────────────────────

  private fun similarity(a: ExerciseNode, b: ExerciseNode): Double {
  val patternMatch = if (a.pattern == b.pattern) 1.0 else 0.0
  return 0.65 * patternMatch + 0.35 * muscleOverlap(a, b)
  }

  /** Jaccard overlap of the two exercises' muscle sets (0..1). */
  private fun muscleOverlap(a: ExerciseNode, b: ExerciseNode): Double {
  val union = a.allMuscles + b.allMuscles
  if (union.isEmpty()) return 0.0
  val intersect = a.allMuscles.intersect(b.allMuscles)
  return intersect.size.toDouble() / union.size
  }

  private fun isDoable(node: ExerciseNode, available: Set<Equipment>): Boolean {
  val required = node.equipment.filter { it != Equipment.BODYWEIGHT && it != Equipment.NONE }
  return required.all { it in available }
  }

  private fun equipmentLabel(node: ExerciseNode): String =
  node.equipment.filter { it != Equipment.NONE }.joinToString("/") { it.display }
  .ifBlank { Equipment.BODYWEIGHT.display }

  private fun similarReason(src: ExerciseNode, cand: ExerciseNode): String {
  val muscles = cand.primaryMuscles.joinToString(", ") { it.display }
  val base = "Same ${src.pattern.display} pattern"
  return if (muscles.isNotBlank()) "$base, hits $muscles" else base
  }

  private fun substituteReason(src: ExerciseNode, cand: ExerciseNode): String {
  val base = "Same ${src.pattern.display} pattern"
  return if (cand.equipment != src.equipment)
  "$base, ${equipmentLabel(cand)} instead of ${equipmentLabel(src)}"
  else "$base, similar stimulus"
  }
}

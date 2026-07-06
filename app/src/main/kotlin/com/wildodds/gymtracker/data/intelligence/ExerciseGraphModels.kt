package com.wildodds.gymtracker.data.intelligence

import com.wildodds.gymtracker.data.MuscleGroup

/**
 * Pure-Kotlin exercise-intelligence model. No Android dependencies, so the engine that operates
 * on it is fully unit-testable and reusable by swaps, regressions/progressions, travel mode and
 * injury triage (Phases 3C–3E, 7A) without four separate implementations.
 */

/** Biomechanical movement pattern. [display] is used in human-readable reason strings. */
enum class MovementPattern(val display: String) {
  HORIZONTAL_PUSH("horizontal-push"),
  VERTICAL_PUSH("vertical-push"),
  HORIZONTAL_PULL("horizontal-pull"),
  VERTICAL_PULL("vertical-pull"),
  SQUAT("squat"),
  HIP_HINGE("hip-hinge"),
  LUNGE("lunge"),
  KNEE_EXTENSION("knee-extension"),
  KNEE_FLEXION("knee-flexion"),
  CALF_RAISE("calf-raise"),
  LATERAL_RAISE("lateral-raise"),
  FRONT_RAISE("front-raise"),
  REAR_DELT_RAISE("rear-delt"),
  ELBOW_FLEXION("elbow-flexion"),
  ELBOW_EXTENSION("elbow-extension"),
  SHRUG("shrug"),
  CORE_FLEXION("core-flexion"),
  CORE_ROTATION("core-rotation"),
  CORE_ANTI_EXTENSION("anti-extension"),
  UNKNOWN("unknown")
}

/** Equipment an exercise requires. BODYWEIGHT/NONE are always "available". */
enum class Equipment(val display: String) {
  BARBELL("barbell"),
  DUMBBELL("dumbbell"),
  MACHINE("machine"),
  CABLE("cable"),
  BODYWEIGHT("bodyweight"),
  BANDS("bands"),
  NONE("none")
}

/**
 * Controlled vocabulary for joint-stress tags, seeded here so injury triage (Prompt 3E) can avoid
 * exercises by tag without re-deriving them. Stored as plain strings in the asset so the dataset
 * stays extendable without code changes; these constants document the canonical set.
 */
object JointStressTags {
  const val LUMBAR_LOADING = "lumbar-loading"
  const val SPINAL_COMPRESSION = "spinal-compression"
  const val DEEP_KNEE_FLEXION = "deep-knee-flexion"
  const val LOADED_KNEE_FLEXION = "loaded-knee-flexion"
  const val OVERHEAD = "overhead"
  const val SHOULDER_LOAD = "shoulder-load"
  const val ELBOW_LOAD = "elbow-load"
  const val WRIST_LOAD = "wrist-load"
  const val HIP_FLEXION = "hip-flexion"
}

/** A node in the exercise graph. Muscle lists are reused from ExerciseLibrary at load time. */
data class ExerciseNode(
  val name: String,             // display name, e.g. "Bench Press"
  val key: String,              // normalized lookup key, e.g. "bench press"
  val pattern: MovementPattern,
  val equipment: Set<Equipment>,
  val unilateral: Boolean,
  val difficulty: Int,          // 1 (easiest) .. 10 (hardest)
  val jointStress: Set<String>,
  val primaryMuscles: Set<MuscleGroup>,
  val secondaryMuscles: Set<MuscleGroup>
) {
  val allMuscles: Set<MuscleGroup> get() = primaryMuscles + secondaryMuscles
}

/** A graph result with a relevance [score] (0..1) and a human-readable [reason]. */
data class ScoredExercise(
  val node: ExerciseNode,
  val score: Double,
  val reason: String
)

/** Constraints for [ExerciseGraphEngine.substituteFor]. Defaults = no constraints. */
data class SubstitutionConstraints(
  val availableEquipment: Set<Equipment> = Equipment.entries.toSet(),
  val avoidTags: Set<String> = emptySet(),
  val maxDifficulty: Int? = null
)

/** Normalized lookup key for an exercise name — lowercase, trimmed, punctuation-light. */
fun normalizeExerciseName(name: String): String =
  name.lowercase().trim()
  .replace(Regex("[^a-z0-9 -]"), "")
  .replace(Regex("\\s+"), " ")
  .trim()

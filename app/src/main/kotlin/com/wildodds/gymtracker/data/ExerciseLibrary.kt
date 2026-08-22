package com.wildodds.gymtracker.data

import com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex

/**
 * Exercise -> muscle group, for every movement the app knows.
 *
 * [primary]  - muscles doing the lion's share of the work (count fully toward weekly sets)
 * [secondary] - muscles that assist significantly (count at 0.5x toward weekly sets)
 */
data class ExerciseInfo(
  val primary: List<MuscleGroup>,
  val secondary: List<MuscleGroup> = emptyList()
)

enum class MuscleGroup(val display: String, val category: String) {
  CHEST  ("Chest",  "Push"),
  FRONT_DELT  ("Front Delts",  "Push"),
  SIDE_DELT  ("Side Delts",  "Push"),
  REAR_DELT  ("Rear Delts",  "Pull"),
  UPPER_BACK  ("Upper Back",  "Pull"),
  LATS  ("Lats",  "Pull"),
  LOWER_BACK  ("Lower Back",  "Pull"),
  BICEPS  ("Biceps",  "Pull"),
  TRICEPS  ("Triceps",  "Push"),
  FOREARMS  ("Forearms",  "Arms"),
  QUADS  ("Quads",  "Legs"),
  HAMSTRINGS  ("Hamstrings",  "Legs"),
  GLUTES  ("Glutes",  "Legs"),
  CALVES  ("Calves",  "Legs"),
  ABS  ("Abs",  "Core"),
  OBLIQUES  ("Obliques",  "Core"),
  TRAPS  ("Traps",  "Pull"),
  NECK  ("Neck",  "Other"),
  HIP_FLEXORS  ("Hip Flexors",  "Legs"),
  ADDUCTORS  ("Adductors",  "Legs"),
  ABDUCTORS  ("Abductors",  "Legs")
}

/**
 * A view over [ExerciseIndex], which is the single source of truth for what an exercise trains.
 *
 * This used to be a 63-entry map with a hand-written if-ladder in front of it, living alongside the
 * 952-movement index and disagreeing with it. Two answers to the same question is one too many:
 * the map called a Bulgarian split squat quads-and-glutes while the index called it quads, and
 * which one you got depended on which code path you came in through. The muscle data now lives in
 * the index, generated per base movement by `tools/build_exercise_index.py` and inherited by every
 * variant, so a movement added there arrives everywhere at once.
 *
 * The visible effect is that the app knows 952 movements instead of 63 — the exercise picker, the
 * weekly-volume maths and the gap-session generator all widened by the same change.
 */
object ExerciseLibrary {

  /**
   * Muscle groups for a name, or null when the movement is not known.
   *
   * Tries the index's exact and alias keys first, then falls back to the most specific movement
   * whose every word appears in the text — which is what lets "3x8 Incline Bench Press" resolve
   * while keeping "Bench" from beating "Bench Press".
   */
  fun lookup(name: String): ExerciseInfo? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    val entry = ExerciseIndex.find(trimmed) ?: ExerciseIndex.mentionedIn(trimmed) ?: return null
    return infoFor(entry)
  }

  /** Every known movement, keyed by its lowercase canonical name. */
  val LIBRARY: Map<String, ExerciseInfo> by lazy {
    LinkedHashMap<String, ExerciseInfo>(ExerciseIndex.entries.size * 2).apply {
      for (e in ExerciseIndex.entries) putIfAbsent(e.canonical.lowercase(), infoFor(e))
    }
  }

  private fun infoFor(e: ExerciseIndex.Entry): ExerciseInfo =
    ExerciseInfo(e.primaryMuscles.mapNotNull(::muscle), e.secondaryMuscles.mapNotNull(::muscle))

  private val BY_NAME: Map<String, MuscleGroup> = MuscleGroup.entries.associateBy { it.name }

  /** Unknown muscle names are dropped rather than crashing: the index is data, and data drifts. */
  private fun muscle(name: String): MuscleGroup? = BY_NAME[name]

  val allMuscleGroups: List<MuscleGroup> = MuscleGroup.entries.sortedBy { it.display }
}

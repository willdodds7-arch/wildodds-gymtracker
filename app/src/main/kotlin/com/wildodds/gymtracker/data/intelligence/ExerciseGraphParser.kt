package com.wildodds.gymtracker.data.intelligence

import com.google.gson.Gson
import com.wildodds.gymtracker.data.ExerciseInfo

/**
 * Pure JSON → [ExerciseNode] parser (Gson is plain-JVM, no Android). Muscle lists are injected via
 * [muscleLookup] so the parser stays decoupled from ExerciseLibrary and unit-testable in isolation.
 * Unknown enum strings degrade to UNKNOWN/ignored rather than throwing, so a typo in the asset
 * never crashes the graph.
 */
object ExerciseGraphParser {

  private data class RawDataset(val exercises: List<RawExercise> = emptyList())
  private data class RawExercise(
  val name: String = "",
  val pattern: String? = null,
  val equipment: List<String> = emptyList(),
  val unilateral: Boolean = false,
  val difficulty: Int = 5,
  val jointStress: List<String> = emptyList()
  )

  fun parse(json: String, muscleLookup: (String) -> ExerciseInfo?): List<ExerciseNode> {
  val dataset = runCatching { Gson().fromJson(json, RawDataset::class.java) }.getOrNull()
  ?: return emptyList()
  return dataset.exercises
  .filter { it.name.isNotBlank() }
  .map { raw ->
  val info = muscleLookup(raw.name)
  ExerciseNode(
  name = raw.name,
  key = normalizeExerciseName(raw.name),
  pattern = enumOrNull<MovementPattern>(raw.pattern) ?: MovementPattern.UNKNOWN,
  equipment = raw.equipment.mapNotNull { enumOrNull<Equipment>(it) }.toSet(),
  unilateral = raw.unilateral,
  difficulty = raw.difficulty.coerceIn(1, 10),
  jointStress = raw.jointStress.map { it.trim() }.filter { it.isNotBlank() }.toSet(),
  primaryMuscles = info?.primary?.toSet() ?: emptySet(),
  secondaryMuscles = info?.secondary?.toSet() ?: emptySet()
  )
  }
  }

  private inline fun <reified T : Enum<T>> enumOrNull(s: String?): T? =
  s?.let { runCatching { enumValueOf<T>(it.trim().uppercase().replace('-', '_').replace(' ', '_')) }.getOrNull() }
}

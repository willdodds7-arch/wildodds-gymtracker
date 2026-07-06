package com.wildodds.gymtracker.data.intelligence

/** What the user declares they have while travelling. Bodyweight is always implied. */
enum class TravelEquipment { BANDS, DUMBBELLS, HOTEL_GYM }

/** A planned exercise to translate (a thin view of the program's Exercise — never mutated). */
data class PlannedExercise(val name: String, val sets: Int, val repsTarget: String)

/**
 * Result of translating one [original] exercise for travel. [sets] always equals the original's
 * set count — Travel Mode is a display overlay, so it never changes the logged structure.
 */
data class TranslatedExercise(
  val original: PlannedExercise,
  val name: String,
  val sets: Int,
  val repsTarget: String,
  val reason: String,
  val isSubstituted: Boolean,
  val adjusted: Boolean
)

/**
 * Pure translator: maps a program's exercises to the closest minimal-equipment equivalents via the
 * 3A engine, preserving movement pattern + target muscles, and matching difficulty as closely as
 * possible. Non-destructive — it returns a new overlay and never touches the inputs.
 */
object TravelTranslator {

  /** Equipment available given the declared chips (bodyweight always included). */
  fun equipmentSetFor(selected: Set<TravelEquipment>): Set<Equipment> {
  val set = mutableSetOf(Equipment.BODYWEIGHT)
  if (TravelEquipment.BANDS in selected) set += Equipment.BANDS
  if (TravelEquipment.DUMBBELLS in selected) set += Equipment.DUMBBELL
  if (TravelEquipment.HOTEL_GYM in selected) set += setOf(Equipment.DUMBBELL, Equipment.MACHINE, Equipment.CABLE)
  return set
  }

  fun translate(
  planned: List<PlannedExercise>,
  engine: ExerciseGraphEngine,
  equipment: Set<Equipment>
  ): List<TranslatedExercise> = planned.map { p -> translateOne(p, engine, equipment) }

  private fun translateOne(
  p: PlannedExercise, engine: ExerciseGraphEngine, equipment: Set<Equipment>
  ): TranslatedExercise {
  val node = engine.node(p.name)
  ?: return keep(p, "Not in the exercise library — keep it as planned or improvise.", adjusted = false)

  if (isDoable(node, equipment)) {
  return keep(p, "No equipment needed — do it as planned.", adjusted = false)
  }

  val best = engine.substituteFor(p.name, SubstitutionConstraints(availableEquipment = equipment)).firstOrNull()
  ?: return keep(p, "No equipment-matched swap — hold an isometric or add band tension instead.", adjusted = true)

  val sub = best.node
  val bodyweightStandIn = sub.equipment == setOf(Equipment.BODYWEIGHT) &&
  node.equipment != setOf(Equipment.BODYWEIGHT)
  return if (bodyweightStandIn) {
  TranslatedExercise(
  original = p, name = sub.name, sets = p.sets,
  repsTarget = adjustRepsForBodyweight(p.repsTarget),
  reason = "${best.reason} — higher reps & slow tempo to match the loaded lift.",
  isSubstituted = true, adjusted = true
  )
  } else {
  TranslatedExercise(
  original = p, name = sub.name, sets = p.sets, repsTarget = p.repsTarget,
  reason = best.reason, isSubstituted = true, adjusted = false
  )
  }
  }

  private fun keep(p: PlannedExercise, reason: String, adjusted: Boolean) = TranslatedExercise(
  original = p, name = p.name, sets = p.sets, repsTarget = p.repsTarget,
  reason = reason, isSubstituted = false, adjusted = adjusted
  )

  private fun isDoable(node: ExerciseNode, available: Set<Equipment>): Boolean {
  val required = node.equipment.filter { it != Equipment.BODYWEIGHT && it != Equipment.NONE }
  return required.all { it in available }
  }

  /** Loaded → bodyweight: roughly double the reps (or AMRAP) to keep the stimulus meaningful. */
  private fun adjustRepsForBodyweight(originalReps: String): String {
  val nums = Regex("\\d+").findAll(originalReps).map { it.value.toInt() }.toList()
  return when {
  nums.isEmpty() -> "AMRAP"
  nums.size == 1 -> "${nums[0] * 2}+"
  else -> "${nums.min() * 2}-${nums.max() * 2}"
  }
  }
}

package com.wildodds.gymtracker.data.intelligence

import com.wildodds.gymtracker.data.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for Travel Mode translation under each equipment constraint, and non-mutation. */
class TravelTranslatorTest {

  private fun node(name: String, pattern: MovementPattern, equip: Equipment, diff: Int,
  muscles: Set<MuscleGroup> = setOf(MuscleGroup.QUADS)) = ExerciseNode(
  name = name, key = normalizeExerciseName(name), pattern = pattern, equipment = setOf(equip),
  unilateral = false, difficulty = diff, jointStress = emptySet(),
  primaryMuscles = muscles, secondaryMuscles = emptySet()
  )

  // A small graph covering the squat pattern across equipment + a bodyweight push.
  private val engine = ExerciseGraphEngine(listOf(
  node("Back Squat", MovementPattern.SQUAT, Equipment.BARBELL, 8),
  node("Hack Squat", MovementPattern.SQUAT, Equipment.MACHINE, 5),
  node("Goblet Squat", MovementPattern.SQUAT, Equipment.DUMBBELL, 4),
  node("Band Squat", MovementPattern.SQUAT, Equipment.BANDS, 3),
  node("Bodyweight Squat", MovementPattern.SQUAT, Equipment.BODYWEIGHT, 2),
  node("Bench Press", MovementPattern.HORIZONTAL_PUSH, Equipment.BARBELL, 6, setOf(MuscleGroup.CHEST)),
  node("Push-up", MovementPattern.HORIZONTAL_PUSH, Equipment.BODYWEIGHT, 4, setOf(MuscleGroup.CHEST))
  ))

  private val squat = PlannedExercise("Back Squat", sets = 5, repsTarget = "5")

  private fun translateSquat(equipment: Set<Equipment>) =
  TravelTranslator.translate(listOf(squat), engine, equipment).first()

  @Test
  fun nothing_givesBodyweightStandInWithAdjustedReps() {
  val t = translateSquat(TravelTranslator.equipmentSetFor(emptySet()))
  assertEquals("Bodyweight Squat", t.name)
  assertTrue(t.isSubstituted)
  assertTrue("loaded→bodyweight must adjust reps", t.adjusted)
  assertEquals(5, t.sets)                 // set count preserved (overlay never changes structure)
  assertTrue(t.repsTarget != "5")         // reps bumped for bodyweight
  assertTrue(t.reason.isNotBlank())
  }

  @Test
  fun bands_useBandSquat() {
  val t = translateSquat(TravelTranslator.equipmentSetFor(setOf(TravelEquipment.BANDS)))
  assertTrue(t.isSubstituted)
  assertTrue(Equipment.BANDS in engine.node(t.name)!!.equipment ||
  Equipment.BODYWEIGHT in engine.node(t.name)!!.equipment)
  // The band squat is loadable and a closer match than pure bodyweight.
  assertEquals("Band Squat", t.name)
  assertFalse("bands preserve reps", t.adjusted)
  assertEquals("5", t.repsTarget)
  }

  @Test
  fun dumbbells_useGobletSquatPreservingReps() {
  val t = translateSquat(TravelTranslator.equipmentSetFor(setOf(TravelEquipment.DUMBBELLS)))
  assertEquals("Goblet Squat", t.name)
  assertFalse(t.adjusted)
  assertEquals("5", t.repsTarget)
  }

  @Test
  fun hotelGym_useMachineOrDumbbell_neverBarbell() {
  val t = translateSquat(TravelTranslator.equipmentSetFor(setOf(TravelEquipment.HOTEL_GYM)))
  assertTrue(t.isSubstituted)
  assertFalse("must not require a barbell while travelling",
  Equipment.BARBELL in engine.node(t.name)!!.equipment)
  }

  @Test
  fun alreadyDoableExerciseIsKept() {
  val t = TravelTranslator.translate(
  listOf(PlannedExercise("Push-up", 3, "12")), engine, TravelTranslator.equipmentSetFor(emptySet())
  ).first()
  assertEquals("Push-up", t.name)
  assertFalse(t.isSubstituted)
  }

  @Test
  fun unknownExerciseIsKeptGracefully() {
  val t = TravelTranslator.translate(
  listOf(PlannedExercise("Atlas Stone Carry", 3, "5")), engine, setOf(Equipment.BODYWEIGHT)
  ).first()
  assertEquals("Atlas Stone Carry", t.name)
  assertFalse(t.isSubstituted)
  assertTrue(t.reason.isNotBlank())
  }

  @Test
  fun translationNeverMutatesTheOriginalPlan() {
  val plan = listOf(squat, PlannedExercise("Bench Press", 4, "8"))
  val result = TravelTranslator.translate(plan, engine, setOf(Equipment.BODYWEIGHT))
  // Inputs untouched; each result still references its untouched original.
  assertEquals(listOf("Back Squat", "Bench Press"), plan.map { it.name })
  assertEquals("Back Squat", result[0].original.name)
  assertEquals(5, result[0].original.sets)
  assertEquals("5", result[0].original.repsTarget)
  }

  @Test
  fun equipmentSetMapping() {
  assertEquals(setOf(Equipment.BODYWEIGHT), TravelTranslator.equipmentSetFor(emptySet()))
  assertTrue(Equipment.BANDS in TravelTranslator.equipmentSetFor(setOf(TravelEquipment.BANDS)))
  assertTrue(Equipment.DUMBBELL in TravelTranslator.equipmentSetFor(setOf(TravelEquipment.DUMBBELLS)))
  val hotel = TravelTranslator.equipmentSetFor(setOf(TravelEquipment.HOTEL_GYM))
  assertTrue(hotel.containsAll(setOf(Equipment.DUMBBELL, Equipment.MACHINE, Equipment.CABLE)))
  }
}

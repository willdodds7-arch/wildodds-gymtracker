package com.wildodds.gymtracker.data.intelligence

import com.wildodds.gymtracker.data.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Amber/green modifications: contraindicated lifts swapped, others kept, prehab inserted, no mutation. */
class InjuryAccommodatorTest {

  private fun node(name: String, pattern: MovementPattern, equip: Equipment, diff: Int, tags: Set<String>) =
  ExerciseNode(name, normalizeExerciseName(name), pattern, setOf(equip), false, diff, tags,
  setOf(MuscleGroup.QUADS), emptySet())

  // Knee-friendly graph: deep squats carry deep-knee-flexion; leg press is tolerated.
  private val engine = ExerciseGraphEngine(listOf(
  node("Back Squat", MovementPattern.SQUAT, Equipment.BARBELL, 8, setOf("deep-knee-flexion", "lumbar-loading")),
  node("Leg Press", MovementPattern.SQUAT, Equipment.MACHINE, 4, setOf("loaded-knee-flexion")),
  node("Hip Thrust", MovementPattern.HIP_HINGE, Equipment.BARBELL, 4, emptySet()),
  node("Bench Press", MovementPattern.HORIZONTAL_PUSH, Equipment.BARBELL, 6, setOf("shoulder-load"))
  ))

  private val prehab = listOf(PrehabExercise("Spanish Squat", 3, "30s", "pain-free"))

  @Test
  fun contraindicatedLiftsAreSwappedAndOthersKept() {
  val planned = listOf(
  PlannedExercise("Back Squat", 5, "5"),     // deep-knee-flexion → contraindicated for knee
  PlannedExercise("Hip Thrust", 3, "10"),    // no avoided tag → kept
  PlannedExercise("Bench Press", 4, "8")     // shoulder-load, not a knee tag → kept
  )
  val overlay = InjuryAccommodator.build(
  planned, engine, BodyRegion.KNEE,
  avoidTags = setOf("deep-knee-flexion", "loaded-knee-flexion"), prehab = prehab
  )

  // Back Squat swapped to a tolerated SQUAT-pattern lift that lacks the avoided tags... but
  // Leg Press carries loaded-knee-flexion (also avoided), so there is NO tolerated squat → flagged.
  assertFalse("no tolerated squat exists here → must be flagged, not swapped", overlay.swaps[0].isSubstituted)
  assertTrue(overlay.swaps[0].reason.contains("No tolerated alternative"))
  // Untouched lifts stay as-is.
  assertFalse(overlay.swaps[1].isSubstituted)
  assertEquals("Hip Thrust", overlay.swaps[1].name)
  assertFalse(overlay.swaps[2].isSubstituted)
  // Prehab inserted.
  assertEquals(1, overlay.prehab.size)
  }

  @Test
  fun aTolerableAlternativeIsSwappedIn() {
  // Avoid only deep-knee-flexion → Leg Press (loaded-knee-flexion only) becomes a valid swap.
  val overlay = InjuryAccommodator.build(
  listOf(PlannedExercise("Back Squat", 5, "5")), engine, BodyRegion.KNEE,
  avoidTags = setOf("deep-knee-flexion"), prehab = prehab
  )
  assertTrue(overlay.swaps[0].isSubstituted)
  assertEquals("Leg Press", overlay.swaps[0].name)
  assertTrue(overlay.swaps[0].reason.isNotBlank())
  assertEquals(1, overlay.swapCount)
  }

  @Test
  fun buildNeverMutatesThePlan() {
  val plan = listOf(PlannedExercise("Back Squat", 5, "5"))
  InjuryAccommodator.build(plan, engine, BodyRegion.KNEE, setOf("deep-knee-flexion"), prehab)
  assertEquals("Back Squat", plan[0].name)
  assertEquals(5, plan[0].sets)
  assertEquals("5", plan[0].repsTarget)
  }

  @Test
  fun prehabParserReadsRegionsAndIgnoresUnknown() {
  val json = """
  { "prehab": [
    { "region": "KNEE", "exercises": [ { "name": "Spanish Squat", "sets": 3, "reps": "30s", "note": "x" } ] },
    { "region": "NOT_A_REGION", "exercises": [ { "name": "Bogus", "sets": 1, "reps": "1", "note": "" } ] }
  ] }
  """.trimIndent()
  val parsed = PrehabParser.parse(json)
  assertEquals(1, parsed.size)
  assertEquals("Spanish Squat", parsed[BodyRegion.KNEE]!!.first().name)
  }
}

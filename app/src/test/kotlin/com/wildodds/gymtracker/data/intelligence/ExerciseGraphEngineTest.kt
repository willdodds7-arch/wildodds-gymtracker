package com.wildodds.gymtracker.data.intelligence

import com.wildodds.gymtracker.data.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the exercise graph engine — no Android, no asset loading. */
class ExerciseGraphEngineTest {

  private fun node(
  name: String, pattern: MovementPattern, equip: Equipment, diff: Int,
  tags: Set<String> = emptySet(), primary: Set<MuscleGroup> = emptySet()
  ) = ExerciseNode(
  name = name, key = normalizeExerciseName(name), pattern = pattern,
  equipment = setOf(equip), unilateral = false, difficulty = diff,
  jointStress = tags, primaryMuscles = primary, secondaryMuscles = emptySet()
  )

  private val chest = setOf(MuscleGroup.CHEST)
  private val quads = setOf(MuscleGroup.QUADS)

  private val graph = listOf(
  // Horizontal push, varied difficulty + equipment
  node("Bench Press", MovementPattern.HORIZONTAL_PUSH, Equipment.BARBELL, 6, setOf("shoulder-load"), chest),
  node("Incline Bench Press", MovementPattern.HORIZONTAL_PUSH, Equipment.BARBELL, 6, setOf("shoulder-load"), chest),
  node("Dumbbell Bench Press", MovementPattern.HORIZONTAL_PUSH, Equipment.DUMBBELL, 5, setOf("shoulder-load"), chest),
  node("Machine Chest Press", MovementPattern.HORIZONTAL_PUSH, Equipment.MACHINE, 3, emptySet(), chest),
  node("Push-up", MovementPattern.HORIZONTAL_PUSH, Equipment.BODYWEIGHT, 4, emptySet(), chest),
  // Squat, with joint-stress tags for triage filtering
  node("Squat", MovementPattern.SQUAT, Equipment.BARBELL, 8, setOf("deep-knee-flexion", "lumbar-loading"), quads),
  node("Hack Squat", MovementPattern.SQUAT, Equipment.MACHINE, 5, setOf("deep-knee-flexion"), quads),
  node("Leg Press", MovementPattern.SQUAT, Equipment.MACHINE, 4, setOf("loaded-knee-flexion"), quads),
  // Isolation + an UNKNOWN-pattern node
  node("Barbell Curl", MovementPattern.ELBOW_FLEXION, Equipment.BARBELL, 3, emptySet(), setOf(MuscleGroup.BICEPS)),
  node("Wrist Curl", MovementPattern.UNKNOWN, Equipment.DUMBBELL, 1)
  )

  private fun engine(onMiss: (String) -> Unit = {}) = ExerciseGraphEngine(graph, onMiss)

  @Test
  fun findSimilarReturnsSamePatternExcludingSelfWithReasons() {
  val similar = engine().findSimilar("Bench Press")
  assertEquals(4, similar.size)  // the 4 other horizontal-push exercises
  assertTrue(similar.all { it.node.pattern == MovementPattern.HORIZONTAL_PUSH })
  assertFalse(similar.any { it.node.key == "bench press" })
  assertTrue(similar.all { it.reason.isNotBlank() })
  assertTrue(similar.first().reason.contains("horizontal-push"))
  }

  @Test
  fun regressPicksClosestEasierSamePattern() {
  val r = engine().regress("Bench Press")  // diff 6 → closest below is Dumbbell Bench (5)
  assertNotNull(r)
  assertEquals("Dumbbell Bench Press", r!!.node.name)
  assertTrue(r.reason.contains("Easier"))
  assertTrue(r.reason.contains("horizontal-push"))
  }

  @Test
  fun progressPicksClosestHarderSamePattern() {
  val p = engine().progress("Machine Chest Press")  // diff 3 → closest above is Push-up (4)
  assertNotNull(p)
  assertEquals("Push-up", p!!.node.name)
  assertTrue(p.reason.contains("Harder"))
  }

  @Test
  fun progressReturnsNullWhenNothingHarderExists() {
  // Bench/Incline are the joint-hardest horizontal push (6); neither has a strictly-harder peer.
  assertNull(engine().progress("Bench Press"))
  }

  @Test
  fun substituteRespectsAvailableEquipment() {
  val subs = engine().substituteFor(
  "Bench Press",
  SubstitutionConstraints(availableEquipment = setOf(Equipment.DUMBBELL, Equipment.MACHINE))
  )
  // No barbell exercises (Incline Bench Press requires a barbell) survive.
  assertTrue(subs.none { Equipment.BARBELL in it.node.equipment })
  assertFalse(subs.any { it.node.name == "Incline Bench Press" })
  // Reachable alternatives are present, with an equipment-swap reason.
  assertTrue(subs.any { it.node.name == "Machine Chest Press" })
  assertTrue(subs.any { it.node.name == "Dumbbell Bench Press" })
  assertTrue(subs.all { it.reason.isNotBlank() })
  assertTrue(subs.first { it.node.name == "Machine Chest Press" }.reason.contains("instead of"))
  }

  @Test
  fun substituteRespectsAvoidTags() {
  val subs = engine().substituteFor(
  "Squat",
  SubstitutionConstraints(avoidTags = setOf("deep-knee-flexion"))
  )
  // Hack Squat (deep-knee-flexion) excluded; Leg Press (loaded-knee-flexion) survives.
  assertFalse(subs.any { it.node.name == "Hack Squat" })
  assertTrue(subs.any { it.node.name == "Leg Press" })
  }

  @Test
  fun substituteRespectsMaxDifficulty() {
  val subs = engine().substituteFor("Squat", SubstitutionConstraints(maxDifficulty = 4))
  assertTrue(subs.all { it.node.difficulty <= 4 })
  assertTrue(subs.any { it.node.name == "Leg Press" })   // diff 4
  assertFalse(subs.any { it.node.name == "Hack Squat" }) // diff 5
  }

  @Test
  fun unknownExerciseDegradesGracefullyAndLogs() {
  var missed: String? = null
  val eng = engine { missed = it }
  assertTrue(eng.findSimilar("Jefferson Curl Machine 9000").isEmpty())
  assertNull(eng.regress("Jefferson Curl Machine 9000"))
  assertNull(eng.progress("Jefferson Curl Machine 9000"))
  assertTrue(eng.substituteFor("Jefferson Curl Machine 9000", SubstitutionConstraints()).isEmpty())
  assertEquals("Jefferson Curl Machine 9000", missed)
  }

  @Test
  fun unknownPatternHasNoSimilarOrSubstitutes() {
  assertTrue(engine().findSimilar("Wrist Curl").isEmpty())
  assertTrue(engine().substituteFor("Wrist Curl", SubstitutionConstraints()).isEmpty())
  }

  @Test
  fun fuzzyNameResolutionMatchesDecoratedNames() {
  // "Barbell Bench Press" should resolve to the "Bench Press" node by substring.
  assertTrue(engine().findSimilar("Barbell Bench Press").isNotEmpty())
  }
}

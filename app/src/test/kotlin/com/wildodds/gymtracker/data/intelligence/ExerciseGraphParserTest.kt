package com.wildodds.gymtracker.data.intelligence

import com.wildodds.gymtracker.data.ExerciseInfo
import com.wildodds.gymtracker.data.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the JSON → graph parser (Gson is plain JVM). */
class ExerciseGraphParserTest {

  private val muscleLookup: (String) -> ExerciseInfo? = { name ->
  if (name == "Bench Press") ExerciseInfo(listOf(MuscleGroup.CHEST), listOf(MuscleGroup.TRICEPS)) else null
  }

  @Test
  fun parsesNodesAndInjectsMuscles() {
  val json = """
  { "exercises": [
    { "name": "Bench Press", "pattern": "HORIZONTAL_PUSH", "equipment": ["BARBELL"],
      "unilateral": false, "difficulty": 6, "jointStress": ["shoulder-load"] }
  ] }
  """.trimIndent()
  val nodes = ExerciseGraphParser.parse(json, muscleLookup)
  assertEquals(1, nodes.size)
  val bench = nodes.first()
  assertEquals("bench press", bench.key)
  assertEquals(MovementPattern.HORIZONTAL_PUSH, bench.pattern)
  assertEquals(setOf(Equipment.BARBELL), bench.equipment)
  assertEquals(6, bench.difficulty)
  assertEquals(setOf("shoulder-load"), bench.jointStress)
  assertEquals(setOf(MuscleGroup.CHEST), bench.primaryMuscles)
  assertEquals(setOf(MuscleGroup.TRICEPS), bench.secondaryMuscles)
  }

  @Test
  fun unknownEnumsDegradeAndDifficultyClamps() {
  val json = """
  { "exercises": [
    { "name": "Mystery Move", "pattern": "NOT_A_PATTERN", "equipment": ["BARBELL", "ALIEN"],
      "difficulty": 15, "jointStress": [] }
  ] }
  """.trimIndent()
  val node = ExerciseGraphParser.parse(json, muscleLookup).first()
  assertEquals(MovementPattern.UNKNOWN, node.pattern)   // bad pattern → UNKNOWN, not a crash
  assertEquals(setOf(Equipment.BARBELL), node.equipment) // ALIEN dropped
  assertEquals(10, node.difficulty)                      // clamped into 1..10
  assertTrue(node.primaryMuscles.isEmpty())              // unknown to ExerciseLibrary
  }

  @Test
  fun malformedJsonReturnsEmptyNeverThrows() {
  assertTrue(ExerciseGraphParser.parse("this is not json", muscleLookup).isEmpty())
  assertTrue(ExerciseGraphParser.parse("", muscleLookup).isEmpty())
  }

  @Test
  fun acceptsHyphenatedEnumSpellings() {
  // "horizontal-push" should normalize to HORIZONTAL_PUSH.
  val json = """{ "exercises": [ { "name": "X", "pattern": "horizontal-push", "equipment": ["dumbbell"], "difficulty": 4 } ] }"""
  val node = ExerciseGraphParser.parse(json, muscleLookup).first()
  assertEquals(MovementPattern.HORIZONTAL_PUSH, node.pattern)
  assertEquals(setOf(Equipment.DUMBBELL), node.equipment)
  }
}

package com.wildodds.gymtracker.data.intelligence

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Validates the shipped exercise_graph.json asset loads, caches, and resolves real exercises. */
@RunWith(RobolectricTestRunner::class)
class ExerciseGraphAssetTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun shippedAssetLoadsAndCaches() {
  val engine = ExerciseGraph.engine(context)
  assertTrue("expected a populated graph, got ${engine.nodes.size}", engine.nodes.size >= 50)
  // Cached — the second call returns the very same instance.
  assertSame(engine, ExerciseGraph.engine(context))
  }

  @Test
  fun realExercisesResolveWithMusclesAndPattern() {
  val engine = ExerciseGraph.engine(context)
  val bench = engine.node("Bench Press")
  assertNotNull(bench)
  assertTrue(bench!!.pattern == MovementPattern.HORIZONTAL_PUSH)
  assertTrue("muscles should come from ExerciseLibrary", bench.primaryMuscles.isNotEmpty())
  }

  @Test
  fun substituteOnRealDataRespectsEquipment() {
  val engine = ExerciseGraph.engine(context)
  // No barbell available → squat substitutes must avoid barbell exercises.
  val subs = engine.substituteFor(
  "Squat",
  SubstitutionConstraints(availableEquipment = setOf(Equipment.MACHINE, Equipment.DUMBBELL, Equipment.BODYWEIGHT))
  )
  assertTrue(subs.isNotEmpty())
  assertTrue(subs.none { Equipment.BARBELL in it.node.equipment })
  assertTrue(subs.all { it.reason.isNotBlank() })
  }

  @Test
  fun regressAndProgressMoveInTheRightDirection() {
  val engine = ExerciseGraph.engine(context)
  val squat = engine.node("Squat")!!
  engine.regress("Squat")?.let { assertTrue(it.node.difficulty < squat.difficulty) }
  val legPress = engine.node("Leg Press")!!
  engine.progress("Leg Press")?.let { assertTrue(it.node.difficulty > legPress.difficulty) }
  }
}

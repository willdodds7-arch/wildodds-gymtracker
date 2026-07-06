package com.wildodds.gymtracker.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.wildodds.gymtracker.data.MuscleGroup
import com.wildodds.gymtracker.data.intelligence.Equipment
import com.wildodds.gymtracker.data.intelligence.ExerciseGraphEngine
import com.wildodds.gymtracker.data.intelligence.ExerciseNode
import com.wildodds.gymtracker.data.intelligence.MovementPattern
import com.wildodds.gymtracker.data.intelligence.normalizeExerciseName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwapExerciseSheetTest {

  @get:Rule
  val compose = createComposeRule()

  private fun node(name: String, equip: Equipment) = ExerciseNode(
  name = name, key = normalizeExerciseName(name), pattern = MovementPattern.HORIZONTAL_PUSH,
  equipment = setOf(equip), unilateral = false, difficulty = 5, jointStress = emptySet(),
  primaryMuscles = setOf(MuscleGroup.CHEST), secondaryMuscles = emptySet()
  )

  private val engine = ExerciseGraphEngine(listOf(
  node("Bench Press", Equipment.BARBELL),
  node("Dumbbell Bench Press", Equipment.DUMBBELL),
  node("Machine Chest Press", Equipment.MACHINE)
  ))

  @Test
  fun listsEngineAlternativesAndApplyingOneReportsTheSwap() {
  var swappedTo: String? = null
  var swappedFuture: Boolean? = null

  compose.setContent {
  MaterialTheme {
  SwapExerciseSheetContent(
  exerciseName = "Bench Press",
  engine = engine,
  initialEquipment = setOf(Equipment.BARBELL, Equipment.DUMBBELL, Equipment.MACHINE),
  onSwap = { name, future -> swappedTo = name; swappedFuture = future }
  )
  }
  }

  // Engine alternatives are listed (the name also appears under "Similar movements").
  compose.onAllNodesWithText("Dumbbell Bench Press").onFirst().assertExists()
  compose.onAllNodesWithText("Machine Chest Press").onFirst().assertExists()

  // Tapping one reports the swap (this is the hook the session uses to update the exercise).
  compose.onAllNodesWithTag("swap_option_Dumbbell Bench Press").onFirst()
  .performScrollTo().performClick()
  compose.waitForIdle()
  assertEquals("Dumbbell Bench Press", swappedTo)
  assertEquals(false, swappedFuture)
  }
}

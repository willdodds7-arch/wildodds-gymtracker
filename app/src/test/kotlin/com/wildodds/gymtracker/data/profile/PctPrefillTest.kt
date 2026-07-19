package com.wildodds.gymtracker.data.profile

import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession
import com.wildodds.gymtracker.data.parser.swappedToLift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PctPrefillTest {

  private val rms = mapOf(
    MainLift.SQUAT to 140f, MainLift.BENCH to 100f, MainLift.DEADLIFT to 180f, MainLift.OHP to 60f
  )

  // ── weights() ────────────────────────────────────────────────────────────────

  @Test
  fun slashList_prescribesADifferentWeightPerSet() {
    // 5/3/1 week 1 on a 100 kg bench: 58.5 → 57.5, 67.5 → 67.5, 76.5 → 77.5 (2.5 kg rounding).
    val w = PctPrefill.weights("Bench Press", "58.5/67.5/76.5%", 3, rms)
    assertEquals(listOf(57.5f, 67.5f, 77.5f), w)
  }

  @Test
  fun extraSetsRepeatTheLastSlashEntry() {
    val w = PctPrefill.weights("Bench Press", "60/70%", 4, rms)
    assertEquals(listOf(60f, 70f, 70f, 70f), w)
  }

  @Test
  fun singlePercentAppliesToEverySet() {
    // Russian: 80% of a 140 kg squat = 112 → 112.5, for all 6 sets.
    val w = PctPrefill.weights("Back Squat", "80%", 6, rms)
    assertEquals(List(6) { 112.5f }, w)
  }

  @Test
  fun overOneHundredPercentWorks_forMaxAttemptSessions() {
    // Russian session 18: 105% of 140 = 147 → 147.5.
    assertEquals(listOf(147.5f), PctPrefill.weights("Back Squat", "105%", 1, rms))
  }

  @Test
  fun rangeUsesTheMidpoint() {
    assertEquals(listOf(75f), PctPrefill.weights("Bench Press", "70-80%", 1, rms))
  }

  @Test
  fun missingOneRm_orUnmatchedExercise_givesNull() {
    assertNull(PctPrefill.weights("Bench Press", "80%", 3, mapOf(MainLift.BENCH to 0f)))
    assertNull(PctPrefill.weights("Bicep Curl", "80%", 3, rms))
    assertNull(PctPrefill.weights("Bench Press", "", 3, rms))
  }

  @Test
  fun variationsMapToTheirParentLift() {
    // Calgary's pause work runs off the competition lift's 1RM.
    assertEquals(listOf(87.5f), PctPrefill.weights("Pause Squat", "62%", 1, rms))
    assertEquals(listOf(115f), PctPrefill.weights("Pause Deadlift", "64%", 1, rms))
  }

  // ── requiredLifts() ──────────────────────────────────────────────────────────

  @Test
  fun requiredLifts_onlyCountsPercentPrescribedExercises() {
    val req = PctPrefill.requiredLifts(
      listOf(
        "Back Squat" to "80%",
        "Bench Press" to "",          // no % → not required
        "Barbell Row" to "",          // unmatched anyway
        "Overhead Press" to "70%"
      )
    )
    assertEquals(setOf(MainLift.SQUAT, MainLift.OHP), req)
  }

  // ── swappedToLift() ──────────────────────────────────────────────────────────

  private fun russianLike(): ParsedProgram {
    val sessions = (1..3).map { d ->
      ParsedSession(
        weekNumber = 1, dayNumber = d, name = "Session $d", muscleGroups = "",
        exercises = listOf(
          ParsedExercise("Back Squat", 6, "2", notes = "", orderIndex = 0, pct1rmTarget = "80%"),
          ParsedExercise("Optional accessory (light)", 2, "8-12", notes = "", orderIndex = 1)
        )
      )
    }
    return ParsedProgram(name = "Russian Squat Routine", totalWeeks = 1, sessions = sessions, liftSwappable = true)
  }

  @Test
  fun swapRenamesTheMainLiftEverywhere_andRetitlesTheProgram() {
    val swapped = russianLike().swappedToLift(MainLift.OHP)
    assertEquals("Russian Squat Routine — Overhead Press", swapped.name)
    val mains = swapped.sessions.flatMap { it.exercises }.filter { it.pct1rmTarget.isNotBlank() }
    assertEquals(3, mains.size)
    mains.forEach { assertEquals("Overhead Press", it.name) }
    // Accessories untouched.
    assertEquals(3, swapped.sessions.flatMap { it.exercises }.count { it.name.startsWith("Optional") })
    // And the 1RM requirement now follows the chosen lift.
    assertEquals(
      setOf(MainLift.OHP),
      PctPrefill.requiredLifts(swapped.sessions.flatMap { s -> s.exercises.map { it.name to it.pct1rmTarget } })
    )
  }

  @Test
  fun swapToTheLiftItAlreadyRunsOn_isANoOp() {
    val same = russianLike().swappedToLift(MainLift.SQUAT)
    assertEquals("Russian Squat Routine", same.name)
  }
}

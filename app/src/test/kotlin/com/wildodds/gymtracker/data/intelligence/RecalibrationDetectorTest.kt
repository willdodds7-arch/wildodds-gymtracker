package com.wildodds.gymtracker.data.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecalibrationDetectorTest {

  @Test
  fun programCompleteDetection() {
  assertTrue(RecalibrationDetector.isProgramComplete(totalSessions = 12, completedSessions = 12))
  assertTrue(RecalibrationDetector.isProgramComplete(12, 13))
  assertFalse(RecalibrationDetector.isProgramComplete(12, 11))
  assertFalse(RecalibrationDetector.isProgramComplete(0, 0))
  }

  @Test
  fun programStallDetection() {
  assertTrue(RecalibrationDetector.isProgramStalled(listOf(true, true, false)))   // 2/3 stalled
  assertFalse(RecalibrationDetector.isProgramStalled(listOf(true, false, false))) // only 1
  assertFalse(RecalibrationDetector.isProgramStalled(emptyList()))
  }

  @Test
  fun completionTakesPriorityOverStall() {
  val t = RecalibrationDetector.detect(
  totalSessions = 4, completedSessions = 4, perExerciseStalled = listOf(true, true)
  )
  assertEquals(RecalibrationTrigger.PROGRAM_COMPLETE, t)
  }

  @Test
  fun stallTriggersWhenIncomplete() {
  val t = RecalibrationDetector.detect(
  totalSessions = 12, completedSessions = 3, perExerciseStalled = listOf(true, true, false)
  )
  assertEquals(RecalibrationTrigger.STALLED, t)
  }

  @Test
  fun noTriggerWhenProgressingAndIncomplete() {
  val t = RecalibrationDetector.detect(12, 3, listOf(false, false, true))
  assertEquals(RecalibrationTrigger.NONE, t)
  }
}

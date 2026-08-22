package com.wildodds.gymtracker.data.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progression is proposal-only. These tests pin both the maths and — just as importantly — that
 * nothing here ever decides on the user's behalf.
 */
class ProgressionMethodTest {

  private val double = ProgressionSettings(
    method = ProgressionMethod.DOUBLE, repRangeLow = 8, repRangeHigh = 12, loadIncrement = 2.5f
  )

  @Test
  fun `manual never proposes anything`() {
    val s = ProgressionSettings(method = ProgressionMethod.MANUAL)
    assertNull(ProgressionAdvisor.propose(s, "Bench Press", listOf(12, 12, 12), 60f))
  }

  @Test
  fun `double progression proposes only when every set hits the top of the range`() {
    assertNotNull(ProgressionAdvisor.propose(double, "Bench Press", listOf(12, 12, 12), 60f))
    assertNull(ProgressionAdvisor.propose(double, "Bench Press", listOf(12, 12, 10), 60f))
  }

  @Test
  fun `double progression names the new load`() {
    val p = ProgressionAdvisor.propose(double, "Bench Press", listOf(12, 12, 12), 60f)!!
    assertTrue(p.summary.contains("62.5"))
    assertEquals("Bench Press", p.exerciseName)
  }

  @Test
  fun `linear load needs the required number of successful sets`() {
    val s = ProgressionSettings(
      method = ProgressionMethod.LINEAR_LOAD, repRangeLow = 5, requiredSuccessfulSets = 3,
      loadIncrement = 5f
    )
    assertNotNull(ProgressionAdvisor.propose(s, "Squat", listOf(5, 5, 5), 100f))
    assertNull(ProgressionAdvisor.propose(s, "Squat", listOf(5, 5, 4), 100f))
  }

  @Test
  fun `RIR proposes only at or above the target`() {
    val s = ProgressionSettings(method = ProgressionMethod.RIR, rirTarget = 2, loadIncrement = 2.5f)
    assertNotNull(ProgressionAdvisor.propose(s, "Row", listOf(10), 70f, lastRir = 3))
    assertNotNull(ProgressionAdvisor.propose(s, "Row", listOf(10), 70f, lastRir = 2))
    assertNull(ProgressionAdvisor.propose(s, "Row", listOf(10), 70f, lastRir = 1))
    assertNull(ProgressionAdvisor.propose(s, "Row", listOf(10), 70f, lastRir = null))
  }

  @Test
  fun `percentage based has nothing to say session to session`() {
    val s = ProgressionSettings(method = ProgressionMethod.PERCENTAGE)
    assertNull(ProgressionAdvisor.propose(s, "Squat", listOf(5, 5, 5), 100f))
  }

  @Test
  fun `turning off auto recommend silences every method`() {
    val s = double.copy(autoRecommend = false)
    assertNull(ProgressionAdvisor.propose(s, "Bench Press", listOf(12, 12, 12), 60f))
  }

  @Test
  fun `no logged sets means no proposal`() {
    assertNull(ProgressionAdvisor.propose(double, "Bench Press", emptyList(), 60f))
  }

  @Test
  fun `every method explains itself in plain language`() {
    ProgressionMethod.entries.forEach { m ->
      val text = m.explain(ProgressionSettings(method = m))
      assertTrue("${m.name} has no explanation", text.length > 20)
      assertTrue("${m.name} explanation should be a sentence", text.trim().endsWith("."))
    }
  }

  @Test
  fun `double progression explanation names the actual configured range`() {
    val text = ProgressionMethod.DOUBLE.explain(double)
    assertTrue(text.contains("8"))
    assertTrue(text.contains("12"))
    assertTrue(text.contains("2.5"))
  }

  @Test
  fun `deload weeks follow the configured cadence and never fire in week 1`() {
    val s = ProgressionSettings(deloadEveryWeeks = 4)
    assertTrue(ProgressionAdvisor.isDeloadWeek(s, 4))
    assertTrue(ProgressionAdvisor.isDeloadWeek(s, 8))
    assertTrue(!ProgressionAdvisor.isDeloadWeek(s, 5))
    assertTrue(!ProgressionAdvisor.isDeloadWeek(s, 1))
  }

  @Test
  fun `deload off means never`() {
    val s = ProgressionSettings(deloadEveryWeeks = 0)
    (1..20).forEach { assertTrue(!ProgressionAdvisor.isDeloadWeek(s, it)) }
  }

  @Test
  fun `load increments read as a human would write them`() {
    assertEquals("2.5", 2.5f.trimZeros())
    assertEquals("5", 5.0f.trimZeros())
  }
}

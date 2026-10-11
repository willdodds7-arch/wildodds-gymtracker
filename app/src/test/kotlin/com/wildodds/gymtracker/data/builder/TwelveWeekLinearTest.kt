package com.wildodds.gymtracker.data.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwelveWeekLinearTest {

  private fun plan(week: Int, start: Float? = 100f) = TwelveWeekLinear.weekPlan(week - 1, start)

  @Test fun `sets and reps follow the four phases`() {
    val shape = (1..12).map { plan(it).let { p -> "${p.sets}x${p.reps}" } }
    assertEquals(
      listOf("3x5", "4x5", "5x5", "5x6", "5x7", "5x8", "5x5", "4x5", "3x5", "3x5", "4x5", "5x5"),
      shape
    )
  }

  @Test fun `load holds for six weeks then climbs to plus fifteen percent`() {
    val loads = (1..12).map { plan(it).load }
    assertEquals(listOf(100f, 100f, 100f, 100f, 100f, 100f, 105f, 110f, 115f, 115f, 115f, 115f), loads)
  }

  @Test fun `week 13 loops back to phase 1 at week 12's weight`() {
    val w13 = plan(13)
    assertEquals(1, w13.step.week)
    assertEquals(1, w13.cycle)
    assertEquals(3, w13.sets)
    assertEquals(115f, w13.load)
    assertTrue(w13.note.contains("round 2"))
    // Round 2's phase 3 builds on 115 kg.
    assertEquals(120.0f, plan(19).load) // 115 × 1.05 = 120.75 → 120
  }

  @Test fun `loads round to loadable plates`() {
    assertEquals(62.5f, TwelveWeekLinear.roundLoad(63f))
    assertEquals(15.5f, TwelveWeekLinear.roundLoad(15.6f))
  }

  @Test fun `note states the jump when a weight is known, a percentage when not`() {
    assertTrue(plan(7).note.contains("about 105 kg"))
    assertTrue(plan(7).note.contains("+5 kg on last week"))
    assertTrue(plan(12).note.contains("loops back to Phase 1"))
    val noWeight = plan(7, start = null)
    assertNull(noWeight.load)
    assertTrue(noWeight.note.contains("5%"))
  }

  @Test fun `merging replaces the old progression line and keeps the coach's own notes`() {
    val first = TwelveWeekLinear.mergeNotes("Pause at the bottom", plan(1).note)
    val second = TwelveWeekLinear.mergeNotes(first, plan(2).note)
    assertTrue(second.startsWith(TwelveWeekLinear.NOTE_PREFIX))
    assertTrue(second.endsWith("Pause at the bottom"))
    assertFalse(second.contains("Week 1 of 12"))
    assertEquals("Pause at the bottom", TwelveWeekLinear.stripNote(second))
  }
}

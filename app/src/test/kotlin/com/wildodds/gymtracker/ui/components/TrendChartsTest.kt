package com.wildodds.gymtracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendChartsTest {

  @Test fun `ticks are round numbers covering the data`() {
    assertEquals(listOf(0f, 5f, 10f, 15f), niceTicks(0f, 13f))
    assertEquals(listOf(60f, 70f, 80f, 90f, 100f), niceTicks(60f, 100f))
  }

  @Test fun `a flat series still gets a scale around it`() {
    val ticks = niceTicks(100f, 100f)
    assertTrue(ticks.first() < 100f && ticks.last() > 100f)
  }

  @Test fun `axis labels stay compact`() {
    assertEquals("80", formatAxisValue(80f))
    assertEquals("102.5", formatAxisValue(102.5f))
    assertEquals("1.3k", formatAxisValue(1250f))
    assertEquals("2k", formatAxisValue(2000f))
    assertEquals("0.75", formatAxisValue(0.75f))
  }
}

package com.wildodds.gymtracker.data.parser

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Validates the shipped blocks.json catalogue: the curated programs parse with correct flags. */
@RunWith(RobolectricTestRunner::class)
class DefaultProgramCatalogueAssetTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun shippedCatalogueHasTheCuratedPrograms() {
    val all = DefaultProgramCatalogue.load(context)
    val names = all.map { it.name }
    assertTrue("Strength Formula present", "Strength Formula" in names)
    assertTrue("Wild Odds Split present", "Wild Odds Split" in names)
    assertEquals(2, all.size)
  }

  @Test
  fun blocksRepeatWeeksIsEitherFullBlockOrPerWeek() {
    val all = DefaultProgramCatalogue.load(context)
    // Lifting blocks either repeat one representative week across the block (repeatWeeks == totalWeeks)
    // or are per-week programs already materialised week-by-week (repeatWeeks == 1).
    assertTrue(all.all { it.repeatWeeks == it.totalWeeks || it.repeatWeeks == 1 })
    // The classic single-week blocks still default to a 4-week repeat.
    assertEquals(4, all.first { it.name == "Strength Formula" }.repeatWeeks)
    assertEquals(4, all.first { it.name == "Wild Odds Split" }.repeatWeeks)
  }

  @Test
  fun liftingProgramsCarryTheirTagsAndExercises() {
    val sf = DefaultProgramCatalogue.load(context).first { it.name == "Strength Formula" }
    assertEquals("Strength", sf.category)
    assertEquals(4, sf.daysPerWeek)
    assertTrue("description was authored", sf.description.length > 40)
    // Day 1 opens with a heavy bench.
    val day1 = sf.sessions.first { it.weekNumber == 1 && it.dayNumber == 1 }
    assertEquals("Bench Press", day1.exercises.first().name)
    assertTrue(sf.trackOneRm)  // %1RM is prescribed on the main lifts
  }
}

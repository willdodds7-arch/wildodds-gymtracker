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
    assertTrue("5/3/1 present", "5/3/1 (Jim Wendler)" in names)
    assertTrue("Candito bench present", "Candito 6-Week (Advanced Bench Hybrid)" in names)
    assertTrue("Russian present", "Russian Squat Routine" in names)
    assertTrue("Calgary present", "Calgary Barbell 8-Week (adapted)" in names)
    assertEquals(6, all.size)
  }

  // ── The %1RM, week-varying programs ────────────────────────────────────────

  @Test
  fun fiveThreeOne_wavesItsPercentagesWeekByWeek_perSet() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "5/3/1 (Jim Wendler)" }
    assertEquals(4, p.totalWeeks)
    assertEquals(1, p.repeatWeeks) // per-week shape: weeks are materialised as authored
    val benchW1 = p.sessions.first { it.weekNumber == 1 && it.name == "Bench Day" }.exercises.first()
    val benchW2 = p.sessions.first { it.weekNumber == 2 && it.name == "Bench Day" }.exercises.first()
    val benchW4 = p.sessions.first { it.weekNumber == 4 && it.name == "Bench Day" }.exercises.first()
    // Different % AND different reps each week — the whole point of a week-varying program.
    assertEquals("58.5/67.5/76.5%", benchW1.pct1rmTarget)
    assertEquals("63/72/81%", benchW2.pct1rmTarget)
    assertEquals("36/45/54%", benchW4.pct1rmTarget) // deload
    assertEquals("5/5/5+", benchW1.repsTarget)
    assertEquals("3/3/3+", benchW2.repsTarget)
  }

  @Test
  fun russian_is18SessionsAcross6Weeks_andLiftSwappable() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "Russian Squat Routine" }
    assertTrue(p.liftSwappable)
    assertEquals(6, p.totalWeeks)
    assertEquals(18, p.sessions.size)
    // Volume phase all at 80%; the ladder peaks at 105% in session 18.
    assertEquals("80%", p.sessions.first { it.weekNumber == 1 }.exercises.first().pct1rmTarget)
    assertEquals("105%", p.sessions.last().exercises.first().pct1rmTarget)
  }

  @Test
  fun canditoAndCalgary_carryTheirWeeks() {
    val all = DefaultProgramCatalogue.load(context)
    assertEquals(6, all.first { it.name.startsWith("Candito") }.totalWeeks)
    assertEquals(8, all.first { it.name.startsWith("Calgary") }.totalWeeks)
    // Calgary week 1 vs week 6: same lift, different prescription.
    val cal = all.first { it.name.startsWith("Calgary") }
    val sqW1 = cal.sessions.first { it.weekNumber == 1 }.exercises.first { it.name == "Back Squat" }
    val sqW6 = cal.sessions.first { it.weekNumber == 6 }.exercises.first { it.name == "Back Squat" }
    assertTrue(sqW1.pct1rmTarget != sqW6.pct1rmTarget)
  }

  @Test
  fun onlyTheRussianProgramIsLiftSwappable() {
    val all = DefaultProgramCatalogue.load(context)
    assertEquals(listOf("Russian Squat Routine"), all.filter { it.liftSwappable }.map { it.name })
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

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
    assertTrue("wave strength present", "Wave Strength 5s-3s-1s" in names)
    assertTrue("bench hybrid present", "6-Week Bench-Focus Hybrid" in names)
    assertTrue("Russian present", "Russian Squat Routine" in names)
    assertTrue("powerlifting block present", "8-Week Powerlifting Block" in names)
    assertTrue("Olympic lifting split present", "5-Day Olympic Lifting Split" in names)
    assertTrue("powerbuilding present", "Top-Set Powerbuilding 5-Day" in names)
    assertTrue("5x Upper Lower present", "5x Upper Lower" in names)
    assertTrue("5x Bro Split 2.0 present", "5x Bro Split 2.0" in names)
    assertTrue("Carpark Bodybuilding present", "Carpark Bodybuilding" in names)
    assertTrue("Silver-Era Space Marine present", "Silver-Era Space Marine" in names)
    assertTrue("Bronze Era Basics present", "Bronze Era Basics" in names)
    assertTrue("12-week linear 6-day split present", "12-Week Linear 6-Day Split" in names)
    assertTrue("12-week linear 3x2 split present", "12-Week Linear 3x2 Split" in names)
    assertEquals(15, all.size)
  }

  @Test
  fun linear3x2_regroupsTheSameExercisesIntoThreeSessionsTwice() {
    val all = DefaultProgramCatalogue.load(context)
    val base = all.first { it.name == "12-Week Linear 6-Day Split" }
    val p = all.first { it.name == "12-Week Linear 3x2 Split" }
    assertEquals(12, p.totalWeeks)
    assertEquals(6, p.daysPerWeek)
    val week1 = p.sessions.filter { it.weekNumber == 1 }.sortedBy { it.dayNumber }
    assertEquals(
      listOf("Day 1 - Chest, Arms & Abs 1", "Day 2 - Back & Shoulders 1", "Day 3 - Legs: Squat",
             "Day 4 - Chest, Arms & Abs 2", "Day 5 - Back & Shoulders 2", "Day 6 - Legs: Hinge"),
      week1.map { it.name }
    )
    // Same exercises as the original, each exactly once per week.
    fun names(s: List<ParsedSession>) = s.flatMap { it.exercises.map { e -> e.name } }.sorted()
    assertEquals(names(base.sessions.filter { it.weekNumber == 1 }), names(week1))
    // Leg days unchanged.
    assertEquals(week1[2].exercises.map { it.name },
                 base.sessions.first { it.weekNumber == 1 && it.name == "Day 3 - Legs: Squat" }.exercises.map { it.name })
    // At most one 12-week progression lift per session.
    val lp = com.wildodds.gymtracker.data.builder.TwelveWeekLinear.MODIFIER
    assertTrue(week1.all { s -> s.exercises.count { lp in it.modifiers } <= 1 })
    assertEquals(5, week1.sumOf { s -> s.exercises.count { lp in it.modifiers } })
  }

  @Test
  fun linear6Day_runsItsMainLiftsOnTheTwelveWeekProgression() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "12-Week Linear 6-Day Split" }
    val lp = com.wildodds.gymtracker.data.builder.TwelveWeekLinear.MODIFIER
    assertEquals(12, p.totalWeeks)
    assertEquals(1, p.repeatWeeks) // every week is written out, since the main lifts differ by week
    assertEquals(6, p.daysPerWeek)
    assertEquals(72, p.sessions.size)
    val week1 = p.sessions.filter { it.weekNumber == 1 }.sortedBy { it.dayNumber }
    assertEquals(
      listOf("Day 1 - Chest", "Day 2 - Back", "Day 3 - Legs: Squat", "Day 4 - Arms & Abs",
             "Day 5 - Shoulders", "Day 6 - Legs: Hinge"),
      week1.map { it.name }
    )
    val progressed = p.sessions.flatMap { s -> s.exercises.filter { lp in it.modifiers }.map { s.weekNumber to it } }
    assertEquals(setOf("Incline Bench Press", "Pull Up", "Back Squat", "Seated Overhead Press", "Deadlift"),
                 progressed.map { it.second.name }.toSet())
    // Week 6 of the squat is 5 x 8; week 9 is 3 x 5 with the weight-jump note.
    fun squat(week: Int) = progressed.first { it.first == week && it.second.name == "Back Squat" }.second
    assertEquals(5, squat(6).sets); assertEquals("8", squat(6).repsTarget)
    assertEquals(3, squat(9).sets)
    assertTrue(squat(9).notes.startsWith(com.wildodds.gymtracker.data.builder.TwelveWeekLinear.NOTE_PREFIX))
    assertTrue("keeps the coach's own note", squat(9).notes.endsWith("Full depth."))
    // Accessories are the same every week.
    val dips = p.sessions.flatMap { it.exercises }.filter { it.name == "Dip" }
    assertEquals(12, dips.size)
    assertTrue(dips.all { it.sets == 3 && it.repsTarget == "20/15/15" })
  }

  @Test
  fun bronzeEraBasics_isThreeDaysOverEightWeeks_withRirConvertedToRpe() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "Bronze Era Basics" }
    assertEquals(8, p.totalWeeks)
    assertEquals(8, p.repeatWeeks)
    assertEquals(3, p.daysPerWeek)
    assertTrue("prescribes RPE", p.trackRpe)
    val week1 = p.sessions.filter { it.weekNumber == 1 }
    assertEquals(listOf("Push & Zercher", "Flex & Squat", "Bench & Pull"), week1.map { it.name })
    // The source prescribes RIR; the catalogue stores RPE = 10 - RIR and restates the RIR in the note.
    val bench = week1.first { it.name == "Bench & Pull" }.exercises.first()
    assertEquals("Barbell Bench Press", bench.name)
    assertEquals(5, bench.sets)
    assertEquals("4-8", bench.repsTarget)
    assertEquals("8", bench.rpeTarget) // 2 RIR
    assertTrue("note keeps the original RIR", bench.notes.contains("2 RIR"))
    val everyExercise = week1.flatMap { it.exercises }
    assertTrue("every exercise carries an RPE target", everyExercise.all { it.rpeTarget.isNotBlank() })
    assertTrue("every exercise restates its RIR", everyExercise.all { it.notes.contains("RIR") })
  }

  @Test
  fun carparkBodybuilding_isSixDaysOverEightWeeks() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "Carpark Bodybuilding" }
    assertEquals(8, p.totalWeeks)
    assertEquals(8, p.repeatWeeks) // single-week shape: block_weeks drives the repeat
    assertEquals(6, p.daysPerWeek)
    val week1 = p.sessions.filter { it.weekNumber == 1 }
    assertEquals(6, week1.size)
    assertTrue("opens on the posterior chain day", week1.first().name.startsWith("Sunday"))
    // The Tuesday/Wednesday/Thursday days each fold two sessions together.
    val wednesday = week1.first { it.name.startsWith("Wednesday") }
    assertEquals(14, wednesday.exercises.size)
  }

  @Test
  fun silverEraSpaceMarine_isFourDaysOverEightWeeks_andTracksRpe() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "Silver-Era Space Marine" }
    assertEquals(8, p.totalWeeks)
    assertEquals(8, p.repeatWeeks)
    assertEquals(4, p.daysPerWeek)
    assertTrue("prescribes RPE", p.trackRpe)
    val week1 = p.sessions.filter { it.weekNumber == 1 }
    assertEquals(listOf("Confirmed Sasquatch (D1)", "Bro Day (D2)", "Quads, Abs & Calves (D3)", "IRL Space Marine (D4)"),
                 week1.map { it.name })
    val zercher = week1.first().exercises.first { it.name == "Zercher Deadlifts" }
    assertEquals(1, zercher.sets)
    assertEquals("10-15", zercher.repsTarget)
    assertEquals("9", zercher.rpeTarget)
  }

  @Test
  fun fiveXUpperLower_isFiveDaysOfTwoBySixToTwelve() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "5x Upper Lower" }
    assertEquals(5, p.daysPerWeek)
    val week1 = p.sessions.filter { it.weekNumber == 1 }
    assertEquals(5, week1.size)
    val everyExercise = week1.flatMap { it.exercises }
    assertTrue("all sets are 2", everyExercise.all { it.sets == 2 })
    assertTrue("all reps are 6-12", everyExercise.all { it.repsTarget == "6-12" })
  }

  @Test
  fun fiveXBroSplit_isFiveDaysOfTwoBySixToTwelve() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "5x Bro Split 2.0" }
    assertEquals(5, p.daysPerWeek)
    val week1 = p.sessions.filter { it.weekNumber == 1 }
    assertEquals(5, week1.size)
    val everyExercise = week1.flatMap { it.exercises }
    assertTrue("all sets are 2", everyExercise.all { it.sets == 2 })
    assertTrue("all reps are 6-12", everyExercise.all { it.repsTarget == "6-12" })
  }

  // ── The %1RM, week-varying programs ────────────────────────────────────────

  @Test
  fun fiveThreeOne_wavesItsPercentagesWeekByWeek_perSet() {
    val p = DefaultProgramCatalogue.load(context).first { it.name == "Wave Strength 5s-3s-1s" }
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
  fun benchHybridAndPowerliftingBlock_carryTheirWeeks() {
    val all = DefaultProgramCatalogue.load(context)
    assertEquals(6, all.first { it.name == "6-Week Bench-Focus Hybrid" }.totalWeeks)
    assertEquals(8, all.first { it.name == "8-Week Powerlifting Block" }.totalWeeks)
    // Powerlifting block week 1 vs week 6: same lift, different prescription.
    val cal = all.first { it.name == "8-Week Powerlifting Block" }
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

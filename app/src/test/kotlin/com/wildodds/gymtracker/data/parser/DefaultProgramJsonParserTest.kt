package com.wildodds.gymtracker.data.parser

import com.wildodds.gymtracker.data.repository.expandWeeks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure unit tests for the JSON block catalogue parser + the 4-week materialisation it relies on. */
class DefaultProgramJsonParserTest {

  private val sampleJson = """
    {
      "library_version": "1.0",
      "program_count": 1,
      "programs": [
        {
          "name": "3x Full Body - High Volume Hypertrophy",
          "author": "Will \"Wild Odds\" Dodds",
          "split": "3x Full Body",
          "style": "High Volume Hypertrophy",
          "specialisation": "None",
          "description": "A test block.",
          "days": [
            {
              "name": "Full Body A",
              "exercises": [
                { "name": "Back Squat", "sets": 3, "target_reps": "5", "percent_1rm": "80%", "notes": "heavy" },
                { "name": "Bench Press", "sets": 3, "target_reps": "8-12", "percent_1rm": null, "notes": "to failure" }
              ]
            },
            {
              "name": "Full Body B",
              "exercises": [
                { "name": "Deadlift", "sets": 2, "target_reps": "3", "percent_1rm": "85%", "notes": "" }
              ]
            }
          ]
        }
      ]
    }
  """.trimIndent()

  private fun parse(json: String) =
    DefaultProgramJsonParser.parseStream(json.byteInputStream())

  @Test
  fun parsesBlockFieldsAndTags() {
    val progs = parse(sampleJson)
    assertEquals(1, progs.size)
    val p = progs.first()
    assertEquals("3x Full Body - High Volume Hypertrophy", p.name)
    assertEquals("3x Full Body", p.split)
    assertEquals("High Volume Hypertrophy", p.style)
    assertEquals("Hypertrophy", p.category)            // style → category bucket
    assertEquals("Will \"Wild Odds\" Dodds", p.coach)
    assertEquals("A test block.", p.description)
    assertEquals(2, p.daysPerWeek)
    assertTrue("any percent_1rm present → tracks 1RM", p.trackOneRm)
  }

  @Test
  fun blockDefaultsToFourWeeks_butShipsOneRepresentativeWeek() {
    val p = parse(sampleJson).first()
    assertEquals(4, p.totalWeeks)
    assertEquals(4, p.repeatWeeks)
    // Catalogue keeps memory light: only week 1 is materialised in the parsed sessions.
    assertEquals(setOf(1), p.sessions.map { it.weekNumber }.toSet())
    assertEquals(2, p.sessions.size)
  }

  @Test
  fun exerciseFieldsMapThrough() {
    val day1 = parse(sampleJson).first().sessions.first { it.dayNumber == 1 }
    val squat = day1.exercises.first()
    assertEquals("Back Squat", squat.name)
    assertEquals(3, squat.sets)
    assertEquals("5", squat.repsTarget)
    assertEquals("80%", squat.pct1rmTarget)
    assertEquals("heavy", squat.notes)
  }

  @Test
  fun expandWeeks_materialisesTheRepresentativeWeekAcrossFour() {
    val p = parse(sampleJson).first()
    val expanded = expandWeeks(p.sessions, p.repeatWeeks)
    // 2 days × 4 weeks
    assertEquals(8, expanded.size)
    assertEquals(listOf(1, 2, 3, 4), expanded.map { it.weekNumber }.distinct())
    // Each week keeps both days with their exercises intact.
    (1..4).forEach { wk ->
      val days = expanded.filter { it.weekNumber == wk }
      assertEquals(2, days.size)
      assertEquals("Back Squat", days.first { it.dayNumber == 1 }.exercises.first().name)
    }
  }

  @Test
  fun expandWeeks_isNoOpForSingleWeekPrograms() {
    val p = parse(sampleJson).first()
    assertEquals(p.sessions, expandWeeks(p.sessions, 1))
  }

  @Test
  fun perWeekBlock_materialisesEachWeekDistinctly() {
    val json = """
      { "programs": [ {
        "name": "Wave Bench", "author": "Candito", "split": "Bench Focus", "style": "Powerlifting",
        "weeks": [
          { "days": [ { "name": "Bench", "exercises": [
            { "name": "Bench Press", "sets": 4, "target_reps": "8", "percent_1rm": "70%" } ] } ] },
          { "days": [ { "name": "Bench", "exercises": [
            { "name": "Bench Press", "sets": 5, "target_reps": "5", "percent_1rm": "80%" } ] } ] },
          { "days": [ { "name": "Bench", "exercises": [
            { "name": "Bench Press", "sets": 3, "target_reps": "2", "rpe": "9" } ] } ] }
        ]
      } ] }
    """.trimIndent()
    val p = parse(json).first()
    // Three explicit weeks, used as-is (no repetition).
    assertEquals(3, p.totalWeeks)
    assertEquals(1, p.repeatWeeks)
    assertEquals(listOf(1, 2, 3), p.sessions.map { it.weekNumber }.distinct())
    assertEquals("Powerlifting", p.category)
    assertTrue("a %1RM appears in some week", p.trackOneRm)
    assertTrue("an RPE appears in week 3", p.trackRpe)
    // Week-to-week prescription genuinely differs.
    fun benchIn(week: Int) = p.sessions.first { it.weekNumber == week }.exercises.first()
    assertEquals("8", benchIn(1).repsTarget)
    assertEquals("5", benchIn(2).repsTarget)
    assertEquals("2", benchIn(3).repsTarget)
    assertEquals("9", benchIn(3).rpeTarget)
    // expandWeeks is a no-op (already materialised), so import sees all three weeks unchanged.
    assertEquals(p.sessions, expandWeeks(p.sessions, p.repeatWeeks))
  }

  @Test
  fun emptyOrJunkJson_yieldsEmptyList() {
    assertTrue(parse("not json at all").isEmpty())
    assertTrue(parse("""{ "programs": [] }""").isEmpty())
    // A block with no usable days is dropped, not crashed on.
    assertTrue(parse("""{ "programs": [ { "name": "x", "days": [] } ] }""").isEmpty())
  }

  @Test
  fun typeFieldIsInertMetadata() {
    // The Running tab (and its "type": "running" routing) was removed in the online-first
    // rework; a block carrying that field today is just parsed like any other lifting block.
    val json = """
      { "programs": [ {
        "name": "Legacy Typed Block", "author": "Wild Odds", "type": "running",
        "split": "Cardio", "style": "Hypertrophy",
        "days": [
          { "name": "Zone 2 Run", "exercises": [ { "name": "Zone 2 Run", "sets": 1, "target_reps": "30-60 min" } ] }
        ]
      } ] }
    """.trimIndent()
    val p = parse(json).first()
    // category is derived purely from `style` now — "type" no longer special-cases anything.
    assertEquals("Hypertrophy", p.category)
    assertEquals(DefaultProgramJsonParser.DEFAULT_BLOCK_WEEKS, p.totalWeeks)
    assertEquals(DefaultProgramJsonParser.DEFAULT_BLOCK_WEEKS, p.repeatWeeks)
  }

  @Test
  fun missingTargetRepsDefaults() {
    val json = """
      { "programs": [ { "name": "B", "split": "s", "style": "Low Volume Strength",
        "days": [ { "name": "D", "exercises": [ { "name": "Bench Press", "sets": 3 } ] } ] } ] }
    """.trimIndent()
    val ex = parse(json).first().sessions.first().exercises.first()
    assertEquals("8-12", ex.repsTarget)
    assertEquals("", ex.pct1rmTarget)
  }
}

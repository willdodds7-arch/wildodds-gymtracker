package com.wildodds.gymtracker.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end: text/CSV → resolve → flag → compile to the app's ParsedProgram. */
class UniversalImportTest {

  @Test fun pplTextPost_parsesSessionsAndExercises() {
    val text = """
      Day 1 - Push
      Bench Press 3x5 @RPE8
      Overhead Press 3x8-12
      Tricep Pushdown 3x12-15

      Day 2 - Pull
      Deadlift 1x5
      Barbell Row 3x8
      Lat Pulldown 3x12
    """.trimIndent()

    val r = UniversalProgramImporter.import("PPL", text)
    assertEquals(2, r.program.sessions.filter { it.weekNumber == 1 }.size)
    val push = r.program.sessions.first { it.name.contains("Push") }
    assertEquals(3, push.exercises.size)
    assertEquals("Bench Press", push.exercises.first().name)
    assertEquals("8", push.exercises.first().rpeTarget)
    assertTrue(r.program.trackRpe)
  }

  @Test fun fiveThreeOne_style_percentOfTmAndAmrap() {
    val text = """
      Day 1
      Squat 1x5 @65% TM
      Squat 1x5 @75% TM
      Squat 1x5+ @85% TM
      Squat BBB 5x10 @50% TM
    """.trimIndent()
    val r = UniversalProgramImporter.import("531", text)
    val ex = r.program.sessions.first().exercises
    assertTrue(r.program.trackOneRm)
    assertEquals("85% TM", ex[2].pct1rmTarget)
    assertEquals("5+", ex[2].repsTarget)          // AMRAP final set preserved, not flattened to "5"
    assertEquals(5, ex[3].sets); assertEquals("10", ex[3].repsTarget)
  }

  @Test fun noFabrication_endToEnd_repsStayEmptyWhenUnstated() {
    val text = "Day 1\nProwler Sprints\nFarmer Carry"
    val r = UniversalProgramImporter.import("Conditioning", text)
    val ex = r.program.sessions.first().exercises
    assertEquals("", ex.first().repsTarget)        // NOT the old parser's fabricated "8-10"
    assertTrue("verbatim source is preserved in notes",
      ex.first().notes.contains("Prowler Sprints"))
  }

  @Test fun textWithCommaPerSet_isNotMisdetectedAsCsv() {
    val text = """
      Day 1 - Full
      Front Squat 6, 8, 10 @RPE8
      Bench Press 10, 8, 6
      RDL 3x6
    """.trimIndent()
    assertTrue("comma per-set reps must not trip CSV detection", !CsvProgramImporter.looksLikeCsv(text))
    val r = UniversalProgramImporter.import("Ratelle", text)
    val ex = r.program.sessions.first().exercises
    assertEquals("Front Squat", ex.first().name)     // header stayed a session, not an exercise
    assertEquals("6, 8, 10", ex.first().repsTarget)   // per-set reps captured
    assertEquals(3, ex.first().sets)
  }

  @Test fun exerciseStartingWithSessionKeyword_isNotSplitIntoAFakeDay() {
    // "Pull Up 3x10" must be one exercise, not a "Pull" day + exercise "Up".
    val text = "Day 1 - Full\nFront Squat 3x5\nPull Up 3x10\nPush Press 3x8"
    val r = UniversalProgramImporter.import("P", text)
    assertEquals(1, r.program.sessions.map { it.dayNumber }.distinct().size)
    val names = r.program.sessions.flatMap { it.exercises }.map { it.name }
    assertTrue("Pull Up kept whole", names.contains("Pull Up"))
    assertTrue("no bogus 'Up' exercise", names.none { it == "Up" })
  }

  @Test fun csv_withHeaderRow() {
    val csv = """
      Day,Exercise,Sets,Reps,RPE
      1,Back Squat,5,5,8
      1,Leg Curl,3,12,
      2,Bench Press,5,5,8
    """.trimIndent()
    assertTrue(CsvProgramImporter.looksLikeCsv(csv))
    val r = UniversalProgramImporter.import("CSV Program", csv)
    assertEquals(2, r.program.sessions.filter { it.weekNumber == 1 }.size)
    val squat = r.program.sessions.first().exercises.first()
    assertEquals("Back Squat", squat.name); assertEquals(5, squat.sets); assertEquals("5", squat.repsTarget)
  }

  @Test fun tsv_fromXlsxExtraction_parses() {
    // AiFileExtractor emits xlsx as tab-separated rows (sheets split by "---"). It must parse like CSV.
    val tsv = "Day\tExercise\tSets\tReps\tRPE\n1\tBack Squat\t4\t5\t8\n1\tLeg Curl\t3\t12\t9\n---\n"
    assertTrue(CsvProgramImporter.looksLikeCsv(tsv))
    val r = UniversalProgramImporter.import("Sheet", tsv)
    val first = r.program.sessions.first().exercises.first()
    assertEquals("Back Squat", first.name); assertEquals(4, first.sets); assertEquals("5", first.repsTarget)
    assertEquals("8", first.rpeTarget)
  }

  @Test fun weekByWeek_expandsToRealWeeks() {
    val text = """
      Week 1
      Day 1
      Squat 3x5 @70%
      Week 2
      Day 1
      Squat 3x5 @75%
    """.trimIndent()
    val r = UniversalProgramImporter.import("Wave", text)
    assertEquals(2, r.program.totalWeeks)
    assertEquals(1, r.program.repeatWeeks)          // real weeks, not a repeated single week
    assertEquals(2, r.program.sessions.map { it.weekNumber }.distinct().size)
  }

  @Test fun aliasLearningResolvesUserShorthand() {
    val text = "Day 1\nZ Press 3x5"
    val without = UniversalProgramImporter.import("P", text)
    assertTrue(without.flags.any { it.message.contains("Z Press") })   // flagged as needs-review

    val alias: (String) -> String? = { n -> if (n == "z press") "Overhead Press" else null }
    val withAlias = UniversalProgramImporter.import("P", text, alias)
    assertEquals("Overhead Press", withAlias.program.sessions.first().exercises.first().name)
  }

  @Test fun emptyOrJunk_flagsRatherThanFabricates() {
    val r = UniversalProgramImporter.import("Empty", "   \n  \n")
    assertTrue(r.flags.any { it.severity == FlagSeverity.BLOCKER })
    assertEquals(0, r.exerciseCount)
  }

  @Test fun resolver_normalizesAbbreviations() {
    assertEquals("barbell bench press", ExerciseResolver.normalize("BB Bench Press"))
    assertEquals("romanian deadlift", ExerciseResolver.normalize("RDL"))
    assertEquals(ResolutionStatus.EXACT, ExerciseResolver.resolve("Bench Press").status)
    // A name with no gym keyword the built-in library recognises stays inferred (flagged for review).
    val ref = ExerciseResolver.resolve("Zzz Qqq Wibble")
    assertNotNull(ref)
    assertTrue(ref.status == ResolutionStatus.INFERRED || ref.status == ResolutionStatus.UNRESOLVED)
  }
}

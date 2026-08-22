package com.wildodds.gymtracker.data.importer.vocab

import com.wildodds.gymtracker.data.importer.TextProgramImporter
import com.wildodds.gymtracker.data.importer.FlagSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's job is as much about what it REFUSES as what it finds. An English program read as
 * Spanish is a corrupted import; a Spanish program read as English is only the behaviour we had
 * before, so every threshold here leans towards saying nothing.
 */
class LanguageDetectorTest {

  private val german = """
    Woche 1 - Tag 1
    Kniebeuge 3 Sätze x 8 Wdh
    Bankdrücken 3 Sätze x 10 Wdh
    Pause: 120 Sek
  """.trimIndent()

  private val spanish = """
    Semana 1 - Dia 1
    Sentadilla 4 series x 6 repeticiones
    Press Banca 3 series x 8 repeticiones
    Descanso 90s
  """.trimIndent()

  @Test fun `a german program is detected`() {
    val d = LanguageDetector.detect(german)
    assertEquals("de", d.code)
    assertTrue("evidence should name the words found: ${d.words}", d.words.contains("woche"))
  }

  @Test fun `a spanish program is detected`() {
    assertEquals("es", LanguageDetector.detect(spanish).code)
  }

  @Test fun `an english program is not detected as anything`() {
    val english = """
      Week 1 - Day 1
      Back Squat 3 sets x 8 reps
      Bench Press 3 sets x 10 reps
      Rest 120s
    """.trimIndent()
    assertFalse(LanguageDetector.detect(english).detected)
  }

  @Test fun `english words that appear in the foreign lists are never the evidence`() {
    // "series", "reps" and "training" are all in a foreign list AND ordinary English. On their own
    // they must decide nothing, or an English program starts importing as Spanish.
    val english = """
      Training block A
      Bench Press - 3 series of 8 reps
      Row - 3 series of 10 reps
    """.trimIndent()
    assertFalse("English gym words must not settle a language", LanguageDetector.detect(english).detected)
  }

  @Test fun `two words is not enough evidence`() {
    assertFalse(LanguageDetector.detect("Woche 1\nTag 1").detected)
  }

  @Test fun `blank and wordless text detect nothing`() {
    assertFalse(LanguageDetector.detect("").detected)
    assertFalse(LanguageDetector.detect("   \n\n 3x8 @ 100kg \n 5x5").detected)
  }

  @Test fun `detection is deterministic`() {
    assertEquals(LanguageDetector.detect(german), LanguageDetector.detect(german))
  }

  @Test fun `translation rewrites structure and leaves movement names alone`() {
    val dialect = LanguageDetector.dialectFor(LanguageDetector.detect(german))!!
    val out = dialect.translate(german)
    assertTrue("week header not translated: $out", out.contains("Week 1"))
    assertTrue("day header not translated: $out", out.contains("Day 1"))
    // German capitalises its nouns, so "Sätze" becomes "Sets" - case is carried over, and every
    // match downstream is case-insensitive under Locale.ROOT anyway.
    assertTrue("sets not translated: $out", out.contains("3 Sets x 8 Reps"))
    assertTrue("rest not translated: $out", out.contains("Rest: 120 Sek"))
    // The movements are the user's data and are never touched.
    assertTrue(out.contains("Kniebeuge"))
    assertTrue(out.contains("Bankdrücken"))
  }

  @Test fun `translation carries the case of the word it replaces`() {
    val dialect = LanguageDetector.dialectFor(LanguageDetector.detect(german))!!
    assertTrue(dialect.translate("WOCHE 2").contains("WEEK"))
    assertTrue(dialect.translate("woche 2").contains("week"))
    assertTrue(dialect.translate("Woche 2").contains("Week"))
  }

  @Test fun `a german program imports with weeks, days and prescriptions`() {
    val draft = TextProgramImporter.import("Programm", german)
    assertEquals(1, draft.weeks.size)
    val session = draft.weeks.single().sessions.first()
    val slot = session.slots.first { it.exercise.original.contains("Kniebeuge") }
    assertEquals(3, slot.sets.first().count)
    assertEquals(8, slot.sets.first().reps?.min)
  }

  @Test fun `the language it was read as is surfaced as a flag`() {
    val draft = TextProgramImporter.import("Programm", german)
    val flag = draft.flags.firstOrNull { it.message.startsWith("Read as German") }
    assertTrue("the swap must be visible to the user, got ${draft.flags.map { it.message }}",
      flag != null)
    assertEquals(FlagSeverity.INFO, flag!!.severity)
  }

  @Test fun `an english program gets no language flag`() {
    val draft = TextProgramImporter.import("Program", "Week 1\nBack Squat 3x8\nBench Press 3x10")
    assertTrue(draft.flags.none { it.message.startsWith("Read as ") })
  }
}

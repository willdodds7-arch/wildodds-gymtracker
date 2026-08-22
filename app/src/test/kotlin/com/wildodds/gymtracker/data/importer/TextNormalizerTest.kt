package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The normaliser removes FORMATTING and restores STRUCTURE. It must never change content.
 *
 * The invariant asserted at the bottom is the important one: for every fixture, the digits that go
 * in are the digits that come out. A normaliser that quietly altered a number would be the most
 * damaging bug in the whole subsystem, because nothing downstream could detect it.
 */
class TextNormalizerTest {

  private fun n(s: String) = TextNormalizer.normalize(s)

  // ── markdown ─────────────────────────────────────────────────────────────────

  @Test fun `headings, bullets and emphasis are stripped`() {
    val out = n("""
      ## Day 1 — Heavy Squat
      * **Back Squat** — 3x5
      1. Leg Press — 3x10
      • Ab Wheel — 3x12
    """.trimIndent())
    assertEquals(
      listOf("Day 1 - Heavy Squat", "Back Squat - 3x5", "Leg Press - 3x10", "Ab Wheel - 3x12"),
      out.lines())
  }

  @Test fun `a markdown table is reflowed into one exercise per line`() {
    val out = n("""
      | Exercise | Sets | Reps | Intensity |
      |---|---|---|---|
      | Back Squat | 3 | 5 | 80% |
      | Leg Press | 3 | 10 | — |
    """.trimIndent())
    assertEquals(listOf("Back Squat 3x5 @ 80%", "Leg Press 3x10"), out.lines())
  }

  @Test fun `a horizontal rule disappears without taking a line with it`() {
    assertEquals(listOf("Day 1", "Back Squat 3x5"), n("Day 1\n---\nBack Squat 3x5").lines())
  }

  // ── unicode ──────────────────────────────────────────────────────────────────

  @Test fun `full-width digits and multiplication signs become ASCII`() {
    assertEquals("Back Squat 3x5", n("Back Squat ３×５").trim())
  }

  @Test fun `every dash in the family becomes a plain hyphen`() {
    assertEquals("Day 1 - Squat 8-12", n("Day 1 — Squat 8–12").trim())
  }

  @Test fun `smart quotes and non-breaking spaces are normalised`() {
    val out = n("Farmer’s Walk 2x30m")
    assertEquals("Farmer's Walk 2x30m", out.trim())
  }

  @Test fun `zero-width characters are removed`() {
    // A zero-width space is a line-break hint, not a space: removing it must not fuse two words
    // that had a real space between them, and must not insert one where there was none.
    assertEquals("Bench Press 3x8", n("Bench Press​ 3x8").trim())
    assertEquals("BenchPress 3x8", n("Bench​Press 3x8").trim())
  }

  @Test fun `a meaningful emoji becomes a word and the rest are stripped`() {
    val out = n("Burpees 3x20 🔥 😀")
    assertTrue("the fire emoji marks a finisher: $out", out.contains("finisher"))
    assertFalse("decorative emoji stay out", out.contains("😀"))
  }

  // ── structure ────────────────────────────────────────────────────────────────

  @Test fun `indentation is preserved because it is evidence`() {
    val out = n("Back Squat\n  set 1: 5 @ 70%")
    assertTrue("indent lost: ${out.lines()}", out.lines()[1].startsWith("  "))
  }

  @Test fun `a hard-wrapped note is rejoined to the line it continues`() {
    val out = n("Barbell Bench Press 3x8 @ 80kg, pause on\nthe chest for a full second")
    assertEquals(1, out.lines().size)
    assertTrue(out.contains("pause on the chest"))
  }

  @Test fun `a new exercise is never joined to the line above it`() {
    val out = n("Barbell Bench Press 3x8\nbarbell row 3x10")
    assertEquals("a prescription always starts a new line", 2, out.lines().size)
  }

  @Test fun `an indented child is never joined to its parent`() {
    val out = n("Back Squat\n  set 1: 5 @ 70%")
    assertEquals(2, out.lines().size)
  }

  @Test fun `page furniture repeated across pages is dropped`() {
    val page = { i: Int -> """
      Smith Strength - Block 2
      Day $i
      Back Squat 3x5
      Leg Press 3x10
      Page $i of 4
    """.trimIndent() }
    val doc = (1..4).joinToString("\n") { page(it) }
    val out = n(doc)
    assertFalse("the repeated header is furniture: $out", out.contains("Smith Strength"))
    assertFalse("page numbers are furniture", out.contains("Page 2 of 4"))
    assertTrue("the program survives", out.contains("Back Squat 3x5"))
  }

  @Test fun `a repeated line that prescribes something is never dropped`() {
    // "Back Squat 3x5" legitimately appears in every week of a program.
    val doc = (1..5).joinToString("\n") { "Week $it\nDay 1\nBack Squat 3x5\nLeg Press 3x10" }
    val out = n(doc)
    assertEquals(5, out.lines().count { it == "Back Squat 3x5" })
  }

  // ── prose ────────────────────────────────────────────────────────────────────

  @Test fun `prose detection is conservative`() {
    assertTrue(TextNormalizer.isProse("This block focuses on building the squat.", false))
    assertFalse("a real exercise name is not prose",
      TextNormalizer.isProse("Single Arm Dumbbell Overhead Press", false))
    assertFalse("anything with set notation is never prose",
      TextNormalizer.isProse("Rest 2-3 minutes between working sets.", true))
  }

  // ── the invariant ────────────────────────────────────────────────────────────

  @Test fun `normalising never changes the digits in a document`() {
    val fixtures = listOf(
      "## Day 1 — Heavy Squat\n* **Back Squat** — 3×5 @ 80%",
      "| Exercise | Sets | Reps |\n|---|---|---|\n| Back Squat | 3 | 5 |",
      "Week 1-4\nMonday — Upper\nBench Press 4x6 @ RPE 8",
      "Bench 3x8 @ 80kg, pause on\nthe chest",
      "Squat ３×５ @ ８０%",
      "A1) Incline DB Press 3x10 rest 90s"
    )
    for (f in fixtures) {
      // Compare digit VALUES, not characters: NFKC turns a full-width 3 into an ASCII 3, which is
      // the point of the normalisation, and both are digits worth three.
      val before = f.filter { it.isDigit() }.map { Character.digit(it, 10) }
      val after = n(f).filter { it.isDigit() }.map { Character.digit(it, 10) }
      assertEquals("digits changed for: $f", before, after)
    }
  }

  @Test fun `normalising is idempotent`() {
    val fixtures = listOf(
      "## Day 1 — Heavy Squat\n* Back Squat — 3×5",
      "Week 1\nBench 3x8\n\nWeek 2\nBench 3x6"
    )
    for (f in fixtures) assertEquals(n(f), n(n(f)))
  }

  @Test fun `normalising does not depend on the device locale`() {
    val doc = "## DAY 1 — Incline Press\n* Incline DB Press 3x10"
    val defaults = java.util.Locale.getDefault()
    val english = n(doc)
    try {
      java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
      assertEquals(english, n(doc))
    } finally {
      java.util.Locale.setDefault(defaults)
    }
  }
}

/**
 * The vocabulary is DATA, and data can be edited without a compiler to catch mistakes. These are the
 * invariants that keep an edit from silently changing how documents parse.
 */
class ReaderVocabularyTest {

  @Test fun `the vocabulary loads and is versioned`() {
    assertTrue("version must be set and bumped on every edit", ReaderVocabulary.version >= 1)
    assertTrue(ReaderVocabulary.abbreviations.isNotEmpty())
    assertTrue(ReaderVocabulary.modifiers.isNotEmpty())
  }

  @Test fun `every canonical modifier in the code has surface forms in the data`() {
    val declared = ReaderVocabulary.modifiers.map { it.canonical }.toSet()
    val missing = Modifier.ALL.filterNot { it in declared }
    assertTrue("modifiers with no surface forms: $missing", missing.isEmpty())
  }

  @Test fun `no modifier surface form is claimed by two different modifiers`() {
    val seen = HashMap<String, String>()
    val clashes = ArrayList<String>()
    for (m in ReaderVocabulary.modifiers) for (p in m.patterns) {
      val prev = seen.put(p, m.canonical)
      if (prev != null && prev != m.canonical) clashes += "$p claimed by $prev and ${m.canonical}"
    }
    assertTrue("ambiguous surface forms: $clashes", clashes.isEmpty())
  }

  @Test fun `modifier patterns are matched longest first`() {
    val lengths = ReaderVocabulary.modifierPatterns.map { it.first.length }
    assertEquals("longest-first ordering is what makes 'each side' beat 'each'",
      lengths.sortedDescending(), lengths)
  }

  @Test fun `abbreviations never shadow a common movement word`() {
    // Expanding "row" or "set" as an abbreviation would rewrite real exercise names.
    val forbidden = setOf("row", "set", "max", "in", "on", "up", "press", "curl", "leg")
    val bad = ReaderVocabulary.abbreviations.keys.filter { it in forbidden }
    assertTrue("these abbreviations would corrupt exercise names: $bad", bad.isEmpty())
  }

  @Test fun `abbreviation expansion works on whole tokens only`() {
    assertEquals("dumbbell bench press", ReaderVocabulary.expandAbbreviations("db bench press"))
    assertEquals("Dbell Press", ReaderVocabulary.expandAbbreviations("Dbell Press"))
  }

  @Test fun `every weekday form resolves back to its weekday`() {
    for ((day, forms) in ReaderVocabulary.weekdays) {
      for (f in forms) assertEquals("$f should be $day", day, ReaderVocabulary.weekdayFor(f))
    }
  }

  @Test fun `rest day labels are recognised in the forms coaches write`() {
    for (label in listOf("Rest", "REST", "Day off", "rest day", "Wednesday - REST", "Sat: off")) {
      assertTrue("'$label' should read as a rest day", ReaderVocabulary.isRestDayLabel(label))
    }
    assertFalse("a rest INSTRUCTION is not a rest day",
      ReaderVocabulary.isRestDayLabel("Rest 2-3 minutes between sets"))
  }

  @Test fun `section labels resolve for the headings people write`() {
    assertEquals("warmup", ReaderVocabulary.sectionFor("Warm-up"))
    assertEquals("accessory", ReaderVocabulary.sectionFor("Accessories"))
    assertEquals("conditioning", ReaderVocabulary.sectionFor("Finisher"))
    assertEquals("cooldown", ReaderVocabulary.sectionFor("Cool down:"))
  }

  @Test fun `lowercasing is locale independent`() {
    val defaults = java.util.Locale.getDefault()
    try {
      java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
      // The Turkish locale lowercases I to a dotless i, which would break every "RIR", "1RM" and
      // "Incline" lookup on those devices.
      assertEquals("incline", ReaderVocabulary.lower("INCLINE").take(7))
      assertEquals("rir", ReaderVocabulary.lower("RIR"))
    } finally {
      java.util.Locale.setDefault(defaults)
    }
  }
}

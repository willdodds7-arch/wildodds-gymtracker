package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.reader.ProgramReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confidence has to be an instrument, not a mood.
 *
 * The old model was three constants and two caps; it could not say why a slot scored what it did,
 * so the review screen could only show a number. These tests pin the two properties that make the
 * new model usable: every score decomposes into named components, and the component that matters
 * most — the fraction of a line no token consumed — actually moves it.
 */
class ConfidenceTest {

  private fun read(text: String) = ProgramReader().readText(text, "Test")

  private fun slots(text: String) =
    read(text).importResult.draft.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }

  private fun slot(text: String) = slots(text).first()

  // ── the components ───────────────────────────────────────────────────────────

  @Test fun `a clean line scores near certain`() {
    val s = slot("Day 1\nBarbell Bench Press 3x8 @ 80kg rest 90s")
    assertTrue("scored ${s.confidence} (${s.breakdown.reasons})", s.confidence > 0.9)
    assertTrue("a clean line has nothing to explain", s.breakdown.reasons.isEmpty())
  }

  @Test fun `an unrecognised exercise pulls the score down and says so`() {
    val s = slot("Day 1\nZercher Yoke Medley Thing 3x8")
    assertTrue("scored ${s.confidence}", s.confidence < 0.75)
    assertTrue("no reason given: ${s.breakdown.reasons}",
      s.breakdown.reasons.any { it.contains("exercise") })
  }

  @Test fun `unconsumed text pulls the score down and says how much`() {
    val s = slot("Day 1\nBench Press 3x8 ~~~ zone 4 protocol delta 7 sigma")
    assertTrue("coverage was ${s.breakdown.notationCoverage}", s.breakdown.notationCoverage < 1.0)
    assertTrue("no unparsed reason: ${s.breakdown.reasons}",
      s.breakdown.reasons.any { it.contains("unparsed") })
  }

  @Test fun `a line with no recognised loading is not confident`() {
    val s = slot("Day 1\nProwler Sprints")
    assertTrue("scored ${s.confidence}", s.confidence < 0.6)
    assertTrue(s.breakdown.reasons.any { it.contains("sets/reps") })
  }

  @Test fun `the breakdown multiplies rather than averages`() {
    // An average would let four good components hide one catastrophic one.
    val b = ConfidenceBreakdown(structure = 1.0, notationCoverage = 1.0, resolution = 0.3,
      extractor = 1.0, layoutMargin = 1.0)
    assertEquals(0.3, b.score, 0.001)
  }

  @Test fun `every score stays inside zero and one`() {
    val texts = listOf(
      "", "Bench Press", "Day 1\nBench Press 3x8", "Day 1\n!!!\n???",
      "Day 1\n" + "Bench Press 3x8 @ 80kg rest 90s each side 31X0\n".repeat(20)
    )
    for (t in texts) for (s in slots(t)) {
      assertTrue("confidence ${s.confidence} for '$t'", s.confidence in 0.0..1.0)
      assertTrue("breakdown ${s.breakdown.score}", s.breakdown.score in 0.0..1.0)
    }
  }

  // ── aggregation ──────────────────────────────────────────────────────────────

  @Test fun `the program summary carries the worst slot, not just the mean`() {
    // Thirty-nine clean lines and one unreadable one is not a 0.97 import.
    val doc = "Day 1\n" + "Bench Press 3x8 @ 80kg\n".repeat(39) + "Zercher Yoke Medley Thing\n"
    val draft = read(doc).importResult.draft
    val summary = Confidence.forProgram(draft)
    val mean = draft.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }.map { it.confidence }.average()
    assertTrue("summary ${summary.score} should sit below the mean $mean", summary.score < mean)
    assertTrue(summary.needingReview >= 1)
  }

  @Test fun `the summary explains itself`() {
    val summary = Confidence.forProgram(read("Day 1\nZercher Yoke Medley Thing 3x8").importResult.draft)
    val explained = summary.explain()
    assertTrue("expected a reason in '$explained'", explained.contains("—"))
    assertTrue(explained.startsWith(Regex("""\d+""").find(explained)!!.value))
  }

  @Test fun `an empty program is honest about being empty`() {
    val summary = Confidence.forSlots(emptyList())
    assertEquals(0.0, summary.score, 0.0)
    assertTrue(summary.reasons.isNotEmpty())
  }

  // ── the document-level fold ──────────────────────────────────────────────────

  @Test fun `a low-confidence extractor can never produce a high-confidence slot`() {
    val draft = read("Day 1\nBench Press 3x8 @ 80kg").importResult.draft
    val before = draft.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }.first().confidence
    assertTrue(before > 0.9)

    // This is the OCR case: a 0.6-confidence page read perfectly cleanly.
    val after = Confidence.applyDocumentConfidence(draft, 0.6, extractorReason = "read at 60% confidence")
    val slot = after.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }.first()
    assertTrue("expected the page confidence to cap the slot, got ${slot.confidence}",
      slot.confidence <= 0.62)
    assertTrue(slot.breakdown.reasons.any { it.contains("60%") })
  }

  @Test fun `a certain extractor changes nothing`() {
    val draft = read("Day 1\nBench Press 3x8").importResult.draft
    assertEquals(draft, Confidence.applyDocumentConfidence(draft, 1.0))
  }

  @Test fun `an ambiguous layout costs confidence but does not collapse it`() {
    assertEquals(1.0, Confidence.marginMultiplier(0.5), 0.001)
    assertTrue(Confidence.marginMultiplier(0.02) < 1.0)
    assertTrue("a close call is uncertain, not wrong", Confidence.marginMultiplier(0.0) >= 0.7)
  }
}

/**
 * The flags are the product. They are what the whole subsystem exists to produce alongside the
 * program, and each one here corresponds to a way an import can be quietly wrong.
 */
class ImportFlagTest {

  private fun flags(text: String) = ProgramReader().readText(text, "Test").importResult.flags

  private fun messages(text: String) = flags(text).map { it.message }

  @Test fun `a document with no exercises is blocked, not imported empty`() {
    val f = flags("Shopping list\n- eggs\n- milk\n- oats")
    assertTrue("expected a blocker, got $f", f.any { it.severity == FlagSeverity.BLOCKER })
  }

  @Test fun `an unstated set count is flagged with its source line`() {
    val f = flags("Day 1\nBench Press 8-12 reps")
    val flag = f.firstOrNull { it.message.contains("how many sets") }
    assertNotNull("no set-count flag in $f", flag)
    assertTrue("the flag must carry the evidence", flag!!.sourceLine.contains("Bench Press"))
  }

  @Test fun `ambiguous units are flagged rather than assumed`() {
    val f = messages("Day 1\nBench Press 3x8 @ 100")
    assertTrue("expected a unit flag in $f", f.any { it.contains("kg/lb") })
  }

  @Test fun `rest days are reported as read`() {
    val f = messages("Day 1 - Push\nBench Press 3x8\nDay 2 - Rest")
    assertTrue("expected a rest-day flag in $f", f.any { it.contains("rest day", true) })
  }

  @Test fun `a duplicate session is flagged`() {
    val doc = """
      Week 1
      Day 1
      Bench Press 3x8
      Barbell Row 3x10
      Day 1
      Bench Press 3x8
      Barbell Row 3x10
    """.trimIndent()
    assertTrue("expected a duplicate flag in ${messages(doc)}",
      messages(doc).any { it.contains("duplicate", true) })
  }

  @Test fun `a half-read line is flagged`() {
    val f = messages("Day 1\nBench Press 3x8 ~~~ zone 4 protocol delta 7 sigma omega")
    assertTrue("expected an unparsed flag in $f", f.any { it.contains("wasn't understood") })
  }

  @Test fun `a single written week says so`() {
    assertTrue(messages("Day 1\nBench Press 3x8").any { it.contains("Only one week") })
  }

  @Test fun `every flag has a severity and a message`() {
    val f = flags("Day 1\nZercher Yoke Thing 8-12 reps\nDay 2 - Rest")
    assertTrue(f.isNotEmpty())
    assertTrue(f.all { it.message.isNotBlank() })
  }

  @Test fun `flags are deterministic`() {
    val doc = "Week 1\nDay 1\nBench Press 3x8\nZercher Yoke Thing"
    val first = messages(doc)
    repeat(3) { assertEquals(first, messages(doc)) }
  }
}

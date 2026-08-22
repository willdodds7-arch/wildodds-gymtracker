package com.wildodds.gymtracker.data.reader.ingest

import com.wildodds.gymtracker.data.importer.layout.LayoutDetector
import com.wildodds.gymtracker.data.importer.layout.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PDF path's decisions, driven by synthetic glyph positions.
 *
 * The case that matters is the first one. A coach's four-column PDF read in PDFBox's reading order
 * comes out as "Bench Press", "3", "8", "80kg", "Row", "3", "10", "40kg" — a list of exercises with
 * no prescriptions attached to any of them, which accounts for most failed coach-PDF imports. These
 * tests pin the reconstruction that fixes it.
 *
 * What they cannot cover is PDFBox itself: `LegacyPDFStreamEngine` loads glyph resources through
 * Android's asset loader and throws `ExceptionInInitializerError` on a plain JVM, so the binding
 * needs a device. Everything downstream of it does not, and is here.
 */
class PdfLayoutTest {

  /** One glyph run, as PDFBox reports it: y measured downward from the top of the page. */
  private fun run(text: String, left: Int, top: Int, width: Int = text.length * 6, height: Int = 12) =
    OcrLine(text, 1.0, left, top, left + width, top + height)

  /** A four-column page: exercise | sets | reps | load, four rows of it. */
  private fun coachPage(baseY: Int = 100): List<OcrLine> {
    val rows = listOf(
      listOf("Back Squat", "5", "5", "100kg"),
      listOf("Bench Press", "3", "8", "80kg"),
      listOf("Barbell Row", "3", "10", "60kg"),
      listOf("Overhead Press", "3", "8", "45kg")
    )
    val x = listOf(60, 300, 400, 500)
    return rows.flatMapIndexed { r, cells ->
      cells.mapIndexed { c, cell -> run(cell, x[c], baseY + r * 30) }
    }
  }

  @Test fun `a four column page rebuilds into four columns`() {
    val a = PdfLayout.assemble(listOf(coachPage()), readingOrder = "ignored")
    assertTrue("expected columns, got ${a.text}", a.columnar)
    assertEquals(1, a.tables.size)
    val first = a.text.lines().first().split('\t')
    assertEquals(listOf("Back Squat", "5", "5", "100kg"), first)
  }

  @Test fun `a rebuilt page reads as a program with prescriptions attached`() {
    // The end-to-end point: each number must land on the lift it belongs to. This goes through the
    // LAYOUT engine, which is the route ProgramReader takes for a PDF that rebuilt into columns —
    // the reconstructed table is handed to it as a grid, not as flat text.
    val a = PdfLayout.assemble(listOf(coachPage()), readingOrder = "ignored")
    val draft = LayoutDetector.detect(Workbook(a.tables, fileName = "coach.pdf"))
      ?.winner?.scored?.build?.invoke()
    assertTrue("the layout engine should read a rebuilt PDF table", draft != null)
    val slots = draft!!.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }
    assertEquals(
      listOf("Back Squat", "Bench Press", "Barbell Row", "Overhead Press"),
      slots.map { it.exercise.original })
    // …and the numbers with them. A four-column page read in reading order arrives as four exercise
    // names followed by twelve loose numbers, with nothing to say which belongs to which.
    assertEquals(listOf(5, 3, 3, 3), slots.map { it.sets.first().count })
    assertEquals(listOf(5, 8, 10, 8), slots.map { it.sets.first().reps?.min })
  }

  @Test fun `reading order is trusted when there are no columns`() {
    // A single-column PDF is exactly the case PDFBox already gets right; second-guessing it would
    // be a reconstruction with nothing to gain and a layout to lose.
    val page = listOf(
      run("Back Squat 5x5 @ 100kg", 60, 100),
      run("Bench Press 3x8 @ 80kg", 60, 130),
      run("Barbell Row 3x10 @ 60kg", 60, 160)
    )
    val a = PdfLayout.assemble(listOf(page), readingOrder = "READING ORDER TEXT")
    assertFalse(a.columnar)
    assertEquals("READING ORDER TEXT", a.text)
    assertEquals(emptyList<Grid>(), a.tables)
  }

  @Test fun `a reconstruction is a reading and its confidence says so`() {
    assertEquals(0.9, PdfLayout.assemble(listOf(coachPage()), "x").confidence, 1e-9)
    assertEquals(1.0, PdfLayout.assemble(listOf(emptyList()), "x").confidence, 1e-9)
  }

  @Test fun `a rebuilt page warns that the columns were inferred`() {
    val a = PdfLayout.assemble(listOf(coachPage()), "x")
    assertTrue(a.warnings.any { it.message.contains("Columns were reconstructed") })
    assertTrue(PdfLayout.assemble(listOf(emptyList()), "x").warnings.isEmpty())
  }

  @Test fun `multiple pages become one document`() {
    // Four pages of a printout are one program, not four.
    val a = PdfLayout.assemble(List(3) { coachPage() }, "x")
    assertEquals(3, a.tables.size)
    // And identical pages keep all their rows: repetition alone must never delete a table row, or a
    // four-week PDF whose weeks share an exercise list loses that list.
    assertEquals(12, a.text.lines().count { it.isNotBlank() })
  }

  @Test fun `a banner repeated across rebuilt pages is still dropped`() {
    val page = { n: Int ->
      listOf(run("Smith Strength - Block 2", 60, 40)) + coachPage(baseY = 100 + n * 0)
    }
    val a = PdfLayout.assemble(List(3) { page(it) }, "x")
    assertFalse("the running header should be gone: ${a.text}", a.text.contains("Smith Strength"))
    assertEquals(12, a.text.lines().count { it.isNotBlank() })
  }

  @Test fun `an empty page contributes nothing rather than a blank row`() {
    val a = PdfLayout.assemble(listOf(coachPage(), emptyList()), "x")
    assertTrue(a.columnar)
    assertEquals(4, a.text.lines().count { it.isNotBlank() })
  }

  // ── scan detection ──────────────────────────────────────────────────────────

  @Test fun `a page with almost no text is a scan`() {
    assertTrue(PdfLayout.looksScanned("", 1))
    assertTrue(PdfLayout.looksScanned("  \n \n", 4))
    assertTrue("a stray watermark is not a text layer", PdfLayout.looksScanned("DRAFT", 2))
  }

  @Test fun `a real page of text is not a scan`() {
    val page = "Back Squat 5x5 @ 100kg\nBench Press 3x8 @ 80kg\nBarbell Row 3x10\n"
    assertFalse(PdfLayout.looksScanned(page, 1))
    assertFalse(PdfLayout.looksScanned(page.repeat(4), 4))
  }

  @Test fun `zero pages is a scan rather than a crash`() {
    assertTrue(PdfLayout.looksScanned("anything at all, at length", 0))
  }

  // ── page furniture ──────────────────────────────────────────────────────────

  @Test fun `a header repeated on every page is dropped`() {
    val body = (1..4).joinToString("\n") { p ->
      "Smith Strength - Block 2\nDay $p\nBack Squat 5x5\nBench Press 3x8\nSmith Strength - Block 2"
    }
    val out = PdfLayout.stripRepeatedPageFurniture(body)
    assertFalse("the running header should be gone: $out", out.contains("Smith Strength"))
    assertTrue(out.contains("Back Squat 5x5"))
    assertTrue(out.contains("Day 3"))
  }

  @Test fun `a repeated line carrying a prescription is never furniture`() {
    // Four days that each start with the same warm-up is not a header, and dropping it would delete
    // real training.
    val body = (1..4).joinToString("\n") { p ->
      "Day $p\nBack Squat 3x8\nAssault Bike 3x8\nCool down"
    }
    val out = PdfLayout.stripRepeatedPageFurniture(body)
    assertEquals(4, out.lines().count { it.trim() == "Back Squat 3x8" })
  }

  @Test fun `a short document is left alone`() {
    // Two pages of a three-page-minimum rule cannot establish repetition.
    val body = "Header\nBack Squat 5x5\nHeader"
    assertEquals(body, PdfLayout.stripRepeatedPageFurniture(body))
  }
}

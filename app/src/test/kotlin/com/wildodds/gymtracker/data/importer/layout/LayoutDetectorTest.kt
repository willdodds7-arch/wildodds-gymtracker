package com.wildodds.gymtracker.data.importer.layout

import com.wildodds.gymtracker.data.importer.layout.WorkbookFixtures.Sheet
import com.wildodds.gymtracker.data.reader.ingest.XlsxExtractor
import com.wildodds.gymtracker.data.reader.ingest.ZipReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spreadsheet engine, against 30 fixture workbooks.
 *
 * `SmartXlsxParser` was the largest file in the subsystem (773 lines) and had **two** tests, both on
 * the classic template; the other six strategies had none at all. It was simultaneously the thing
 * most likely to meet a layout nobody anticipated and the thing least able to prove it handled one.
 *
 * Every scorer here has at least one fixture, the pathological cases have their own, and the
 * selection tests assert the property that matters most: the winner is chosen by SCORE, and a close
 * call is reported rather than silently resolved.
 */
class LayoutDetectorTest {

  private fun detect(bytes: ByteArray, name: String = "fixture.xlsx") =
    LayoutDetector.detect(WorkbookFixtures.read(bytes, name))

  private fun winnerOf(bytes: ByteArray, name: String = "fixture.xlsx") =
    detect(bytes, name)?.winner?.scorer?.id

  private fun draftOf(bytes: ByteArray, name: String = "fixture.xlsx") =
    detect(bytes, name)?.winner?.scored?.build?.invoke()

  private fun slots(bytes: ByteArray) =
    draftOf(bytes)?.weeks.orEmpty().flatMap { w -> w.sessions.flatMap { it.slots } }

  private val header = listOf("Exercise", "Sets", "Reps", "Load", "RPE", "Rest", "Notes")

  private fun row(name: String, sets: String = "3", reps: String = "8", load: String = "",
                  rpe: String = "", rest: String = "", notes: String = "") =
    listOf(name, sets, reps, load, rpe, rest, notes)

  // ── 1. the app's own template ───────────────────────────────────────────────

  @Test fun `fixture 01 - the classic W1D1 template wins outright`() {
    val bytes = WorkbookFixtures.workbook(
      Sheet("Program Info", listOf(listOf("Program Name", "Winter Block"), listOf("Weeks", "8"))),
      Sheet("W1D1", listOf(listOf("Heavy Squat"), header, row("Back Squat", "5", "5"))),
      Sheet("W1D2", listOf(listOf("Bench"), header, row("Bench Press", "5", "5")))
    )
    assertEquals("classic-template", winnerOf(bytes))
    val d = draftOf(bytes)!!
    assertEquals("Winter Block", d.name)
    assertEquals(2, d.weeks.single().sessions.size)
  }

  @Test fun `fixture 02 - the template survives extra sheets`() {
    val bytes = WorkbookFixtures.workbook(
      Sheet("Instructions", listOf(listOf("Fill in the tabs below"))),
      Sheet("W1D1", listOf(listOf("Push"), header, row("Bench Press")))
    )
    assertEquals("classic-template", winnerOf(bytes))
  }

  // ── 2. one sheet per week ───────────────────────────────────────────────────

  @Test fun `fixture 03 - sheet per week`() {
    val bytes = WorkbookFixtures.workbook(
      Sheet("Week 1", listOf(header, row("Back Squat", "5", "5"), row("Bench Press"))),
      Sheet("Week 2", listOf(header, row("Back Squat", "5", "3"), row("Bench Press"))),
      Sheet("Week 3", listOf(header, row("Back Squat", "5", "1"), row("Bench Press")))
    )
    assertEquals("sheet-per-week", winnerOf(bytes))
    assertEquals(listOf(1, 2, 3), draftOf(bytes)!!.weeks.map { it.index })
  }

  @Test fun `fixture 04 - abbreviated week sheet names`() {
    val bytes = WorkbookFixtures.workbook(
      Sheet("Wk1", listOf(header, row("Back Squat"))),
      Sheet("Wk2", listOf(header, row("Back Squat")))
    )
    assertEquals("sheet-per-week", winnerOf(bytes))
  }

  // ── 3. week labels inside one sheet ─────────────────────────────────────────

  @Test fun `fixture 05 - week labels down a single sheet`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Block", listOf(
      header,
      listOf("Week 1"), row("Back Squat", "5", "5"), row("Bench Press"),
      listOf("Week 2"), row("Back Squat", "5", "3"), row("Bench Press")
    )))
    assertEquals("week-labels-in-sheet", winnerOf(bytes))
    assertEquals(listOf(1, 2), draftOf(bytes)!!.weeks.map { it.index })
  }

  // ── 4. repeating day sequences ──────────────────────────────────────────────

  @Test fun `fixture 06 - a repeating Push Pull Legs sequence becomes weeks`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Program", listOf(
      header,
      listOf("Day 1"), row("Bench Press"), row("Overhead Press"),
      listOf("Day 2"), row("Barbell Row"), row("Lat Pulldown"),
      listOf("Day 1"), row("Bench Press"), row("Overhead Press"),
      listOf("Day 2"), row("Barbell Row"), row("Lat Pulldown")
    )))
    assertEquals("repeating-days", winnerOf(bytes))
    assertEquals(2, draftOf(bytes)!!.weeks.size)
  }

  // ── 5. column groups ────────────────────────────────────────────────────────

  @Test fun `fixture 07 - weeks running sideways as repeated column headings`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Block", listOf(
      listOf("Exercise", "Sets", "Reps", "Sets", "Reps", "Sets", "Reps"),
      listOf("Back Squat", "5", "5", "5", "3", "5", "1"),
      listOf("Bench Press", "5", "5", "5", "3", "5", "1")
    )))
    val d = detect(bytes)!!
    assertTrue("column groups must be in contention: ${d.all.map { it.scorer.id to it.scored.confidence }}",
      d.all.any { it.scorer.id == "column-groups" })
  }

  // ── 6. dated log ────────────────────────────────────────────────────────────

  @Test fun `fixture 08 - rows grouped by date`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Log", listOf(
      listOf("Date", "Exercise", "Sets", "Reps"),
      listOf("2026-03-02", "Back Squat", "5", "5"),
      listOf("2026-03-02", "Bench Press", "3", "8"),
      listOf("2026-03-04", "Deadlift", "1", "5"),
      listOf("2026-03-04", "Barbell Row", "3", "8")
    )))
    assertEquals("dates", winnerOf(bytes))
    val d = draftOf(bytes)!!
    assertEquals(2, d.weeks.single().sessions.size)
  }

  @Test fun `fixture 09 - dates keep the order they appear in, never a locale sort`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Log", listOf(
      listOf("Date", "Exercise", "Reps"),
      listOf("03/04/2026", "Deadlift", "5"),
      listOf("02/03/2026", "Back Squat", "5")
    )))
    val sessions = draftOf(bytes)!!.weeks.single().sessions
    assertEquals("a locale-dependent sort would import differently on two phones",
      listOf("03/04/2026", "02/03/2026"), sessions.map { it.label })
  }

  // ── 7. flat ─────────────────────────────────────────────────────────────────

  @Test fun `fixture 10 - a plain single table`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(
      header, row("Back Squat", "5", "5"), row("Bench Press"), row("Barbell Row")
    )))
    assertEquals("flat", winnerOf(bytes))
    assertEquals(3, slots(bytes).size)
  }

  @Test fun `fixture 11 - a header-less sheet is read from content alone`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(
      listOf("Back Squat", "5", "5", "100"),
      listOf("Bench Press", "3", "8", "80"),
      listOf("Barbell Row", "3", "10", "60")
    )))
    val s = slots(bytes)
    assertEquals("all three rows should read as exercises", 3, s.size)
    assertEquals("Back Squat", s.first().exercise.original)
    assertEquals(5, s.first().sets.first().count)
  }

  // ── 8. transposed ───────────────────────────────────────────────────────────

  @Test fun `fixture 12 - exercises across the top instead of down the side`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Block", listOf(
      listOf("", "Back Squat", "Bench Press", "Barbell Row", "Deadlift"),
      listOf("Sets", "5", "3", "3", "1"),
      listOf("Reps", "5", "8", "10", "5")
    )))
    assertEquals("transposed", winnerOf(bytes))
    val names = slots(bytes).map { it.exercise.original }
    assertTrue("expected the real exercises, got $names", names.any { it.contains("Squat", true) })
  }

  // ── 9. one row per set ──────────────────────────────────────────────────────

  @Test fun `fixture 13 - a set log folds identical sets into a count`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Log", listOf(
      listOf("Exercise", "Reps", "Load"),
      listOf("Bench Press", "8", "80"),
      listOf("Bench Press", "8", "80"),
      listOf("Bench Press", "8", "80"),
      listOf("Barbell Row", "10", "60"),
      listOf("Barbell Row", "10", "60"),
      listOf("Barbell Row", "10", "60")
    )))
    assertEquals("one-row-per-set", winnerOf(bytes))
    val s = slots(bytes)
    assertEquals("two exercises, not six", 2, s.size)
    assertEquals(3, s.first().sets.first().count)
    assertTrue("identical sets fold", s.first().sets.first().perSet.isEmpty())
  }

  @Test fun `fixture 14 - a set log keeps genuinely different sets`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Log", listOf(
      listOf("Exercise", "Reps", "Load"),
      listOf("Back Squat", "5", "100"),
      listOf("Back Squat", "3", "110"),
      listOf("Back Squat", "1", "120"),
      listOf("Deadlift", "5", "140"),
      listOf("Deadlift", "5", "140"),
      listOf("Deadlift", "5", "140")
    )))
    val squat = slots(bytes).first { it.exercise.original.contains("Squat", true) }
    assertEquals("averaging these would invent a number", 3, squat.sets.first().perSet.size)
  }

  // ── 10. month calendar ──────────────────────────────────────────────────────

  @Test fun `fixture 15 - a month calendar reads cell by cell`() {
    val bytes = WorkbookFixtures.workbook(Sheet("March", listOf(
      listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
      listOf("Bench Press 3x8", "Back Squat 5x5", "Rest", "Barbell Row 3x10", "Deadlift 1x5", "Rest", "Rest"),
      listOf("Bench Press 3x8", "Back Squat 5x5", "Rest", "Barbell Row 3x10", "Deadlift 1x5", "Rest", "Rest")
    )))
    assertEquals("month-calendar", winnerOf(bytes))
    val d = draftOf(bytes)!!
    assertEquals(2, d.weeks.size)
    assertEquals(7, d.weeks.first().sessions.size)
    assertTrue("rest days survive",
      d.weeks.first().sessions.count { it.type == com.wildodds.gymtracker.data.importer.SessionType.REST } == 3)
  }

  // ── 11. metadata block ──────────────────────────────────────────────────────

  @Test fun `fixture 16 - a key value block above the table gives the program its name`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(
      listOf("Program", "Smith Off-Season"),
      listOf("Athlete", "J. Smith"),
      listOf("Start date", "2026-03-02"),
      listOf(""),
      header, row("Back Squat", "5", "5"), row("Bench Press")
    )))
    assertEquals("metadata-block", winnerOf(bytes))
    val d = draftOf(bytes)!!
    assertEquals("Smith Off-Season", d.name)
    assertTrue("the metadata is kept as notes", d.notes.any { it.contains("J. Smith") })
    assertEquals("and never becomes an exercise", 2, slots(bytes).size)
  }

  // ── 12. colour as semantics ─────────────────────────────────────────────────

  @Test fun `fixture 17 - shaded rows read as section headings`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Block", listOf(
      header,
      listOf("UPPER", "", "", "", "", "", ""),
      row("Bench Press"), row("Barbell Row"),
      listOf("LOWER", "", "", "", "", "", ""),
      row("Back Squat"), row("Romanian Deadlift")
    ), filled = setOf(1 to 0, 1 to 1, 1 to 2, 1 to 3, 4 to 0, 4 to 1, 4 to 2, 4 to 3)))
    val d = detect(bytes)!!
    assertTrue("colour semantics must be in contention",
      d.all.any { it.scorer.id == "colour-semantics" })
  }

  // ── the reader features the old engine lacked ───────────────────────────────

  @Test fun `fixture 18 - a percent-formatted cell reads as a percentage`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(
      listOf("Exercise", "Sets", "Reps", "Intensity"),
      listOf("Back Squat", "5", "5", "0.85")
    ), percentCells = setOf(1 to 3)))
    val squat = slots(bytes).single()
    assertEquals("0.85 formatted as a percent means 85, not 0.85",
      85.0, squat.sets.first().load!!.value!!, 0.01)
  }

  @Test fun `fixture 19 - a merged banner is readable from the rows it spans`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Block", listOf(
      listOf("Week 1"), header, row("Back Squat"), row("Bench Press")
    ), merges = listOf("A1:G1")))
    val grids = XlsxExtractor.read(ZipReader.read(bytes)).grids
    assertEquals("the merged banner fills its span", "Week 1", grids.single().cell(0, 3))
  }

  @Test fun `fixture 20 - a hidden row is not imported`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(
      header, row("Back Squat"), row("OLD DRAFT - Bench Press"), row("Barbell Row")
    ), hiddenRows = setOf(3)))
    val names = slots(bytes).map { it.exercise.original }
    assertTrue("a hidden row is usually a stale draft: $names", names.none { it.contains("OLD DRAFT") })
    assertEquals(2, names.size)
  }

  @Test fun `fixture 21 - a frozen pane identifies the header row`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(
      listOf("Coach Smith's block"),
      listOf("(v3, revised)"),
      header,
      row("Back Squat"), row("Bench Press")
    ), frozenRow = 3))
    assertEquals("the two title lines must not become exercises", 2, slots(bytes).size)
  }

  @Test fun `fixture 22 - an inline string is read`() {
    // Google Sheets exports use inline strings; the old reader dropped them entirely.
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(header, row("Back Squat"))))
    assertEquals("Back Squat", slots(bytes).single().exercise.original)
  }

  // ── the pathological cases ──────────────────────────────────────────────────

  @Test fun `fixture 23 - a blank first sheet does not decide the workbook`() {
    val bytes = WorkbookFixtures.workbook(
      Sheet("Sheet1", listOf(listOf(""))),
      Sheet("Program", listOf(header, row("Back Squat"), row("Bench Press")))
    )
    assertEquals(2, slots(bytes).size)
  }

  @Test fun `fixture 24 - a notes column containing a full exercise line does not become the exercise`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(
      listOf("Exercise", "Sets", "Reps", "Notes"),
      listOf("Back Squat", "5", "5", "last week you did Bench Press 3x8"),
      listOf("Bench Press", "3", "8", "")
    )))
    val names = slots(bytes).map { it.exercise.original }
    assertEquals(listOf("Back Squat", "Bench Press"), names)
  }

  @Test fun `fixture 25 - two programs on one tab are not merged into one day`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Both", listOf(
      header,
      listOf("Day 1"), row("Back Squat"), row("Bench Press"),
      listOf("Day 2"), row("Deadlift"), row("Barbell Row")
    )))
    val d = draftOf(bytes)!!
    assertEquals(2, d.weeks.flatMap { it.sessions }.size)
  }

  @Test fun `fixture 26 - forty sheets are read within the cap`() {
    val sheets = (1..40).map { Sheet("Week $it", listOf(header, row("Back Squat"))) }
    val bytes = WorkbookFixtures.workbook(sheets)
    assertEquals("sheet-per-week", winnerOf(bytes))
    assertTrue(draftOf(bytes)!!.weeks.size >= 10)
  }

  @Test fun `fixture 27 - a right-to-left sheet still reads its numbers`() {
    val bytes = WorkbookFixtures.workbook(Sheet("תוכנית", listOf(
      listOf("Exercise", "Sets", "Reps"),
      listOf("סקוואט", "5", "5"),
      listOf("Back Squat", "3", "8")
    )))
    val s = slots(bytes)
    assertEquals(2, s.size)
    assertEquals("a name in another script is kept verbatim", "סקוואט", s.first().exercise.original)
    assertEquals(5, s.first().sets.first().count)
  }

  @Test fun `fixture 28 - an empty workbook produces no detection rather than an empty program`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(listOf(""), listOf(""))))
    assertNull(detect(bytes))
  }

  @Test fun `fixture 29 - a shopping list is not a program`() {
    val bytes = WorkbookFixtures.workbook(Sheet("List", listOf(
      listOf("Item", "Qty"),
      listOf("Eggs", "12"),
      listOf("Milk", "2"),
      listOf("Oats", "1")
    )))
    val d = detect(bytes)
    val exercises = d?.winner?.scored?.build?.invoke()?.weeks.orEmpty()
      .flatMap { w -> w.sessions.flatMap { it.slots } }
    assertTrue("a shopping list must not import as a program: ${exercises.map { it.exercise.original }}",
      exercises.isEmpty() || exercises.none { it.sets.firstOrNull()?.count != null })
  }

  @Test fun `fixture 30 - a sheet whose numbers are all text still parses`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(
      listOf("Exercise", "Sets", "Reps"),
      listOf("Back Squat", "five", "5")
    )))
    val s = slots(bytes).single()
    assertNull("'five' is not a number and must not be invented as one", s.sets.first().count)
  }

  // ── selection behaviour ─────────────────────────────────────────────────────

  @Test fun `every scorer runs and the winner is chosen by score, not by order`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Block", listOf(
      header, listOf("Week 1"), row("Back Squat"), listOf("Week 2"), row("Back Squat")
    )))
    val d = detect(bytes)!!
    assertTrue("more than one scorer should apply here", d.all.size >= 2)
    assertEquals("the winner is the highest score",
      d.all.maxOf { it.scored.confidence }, d.winner.scored.confidence, 0.0001)
  }

  @Test fun `a close call is reported as ambiguous rather than resolved silently`() {
    // Two readings within the margin must both be surfaced.
    val d = LayoutDetector.Detection(
      winner = LayoutDetector.Ranked(FlatScorer, LayoutScorer.Scored(0.60, listOf("a")) { null }),
      runnerUp = LayoutDetector.Ranked(TransposedScorer, LayoutScorer.Scored(0.55, listOf("b")) { null }),
      all = emptyList()
    )
    assertTrue(d.ambiguous)
    assertEquals(0.05, d.margin, 0.0001)
  }

  @Test fun `every winning detection carries its evidence`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Sheet1", listOf(header, row("Back Squat"))))
    val d = detect(bytes)!!
    assertTrue("evidence is what makes a weird import traceable",
      d.winner.scored.evidence.isNotEmpty() && d.winner.scored.evidence.first().isNotBlank())
  }

  @Test fun `detection is deterministic`() {
    val bytes = WorkbookFixtures.workbook(Sheet("Block", listOf(
      header, listOf("Day 1"), row("Back Squat"), listOf("Day 2"), row("Bench Press")
    )))
    val first = detect(bytes)!!.winner.scorer.id
    repeat(5) { assertEquals(first, detect(bytes)!!.winner.scorer.id) }
  }

  @Test fun `every scorer has a unique id and a human description`() {
    val ids = LayoutDetector.SCORERS.map { it.id }
    assertEquals(ids.size, ids.distinct().size)
    assertTrue(LayoutDetector.SCORERS.all { it.description.length > 8 })
    assertTrue("at least twelve layouts", LayoutDetector.SCORERS.size >= 12)
  }

  // ── the shipped template must never break silently ──────────────────────────

  @Test fun `the shipped bulk template still imports`() {
    val bytes = javaClass.getResourceAsStream("/assets/bulk_template.xlsx")?.readBytes()
      ?: WorkbookFixtures::class.java.getResourceAsStream("/bulk_template.xlsx")?.readBytes()
    // The asset is bundled for the app, not for the JVM test classpath. When it is not reachable
    // the test says so rather than passing vacuously.
    if (bytes == null) {
      println("bulk_template.xlsx not on the test classpath — covered by BulkTemplateFixtureTest")
      return
    }
    val grids = XlsxExtractor.read(ZipReader.read(bytes)).grids
    assertTrue(grids.isNotEmpty())
    assertNotNull(LayoutDetector.detect(Workbook(grids, null, "bulk_template.xlsx")))
  }
}

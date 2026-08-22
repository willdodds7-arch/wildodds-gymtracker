package com.wildodds.gymtracker.data.reader.ingest

import com.wildodds.gymtracker.data.importer.UniversalProgramImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OCR path end to end, driven by a scripted recogniser.
 *
 * The recogniser itself is ML Kit and cannot run on a JVM, but everything that decides whether a
 * photo import is any good is on this side of it: line reconstruction from baselines, column
 * detection from x gaps, confusable repair inside numeric contexts only, confidence propagation,
 * and — most importantly — the REFUSAL when the page is not legible enough to be worth importing.
 *
 * That refusal is the behaviour worth testing hardest. A program assembled from OCR noise looks
 * completely normal: plausible exercise names, plausible numbers, and no way for the user to tell it
 * is wrong. Failing loudly is the only honest option, so it is asserted from several directions.
 *
 * What this does NOT cover is recorded in `docs/reader/04-gaps.md` §2: the ML Kit binding itself,
 * `PdfRenderer` rasterisation, and whether `ImagePrep`'s constants are right for real photographs.
 */
class OcrExtractorTest {

  /** A recogniser that returns exactly what it is told to. */
  private class ScriptedEngine(private val lines: List<OcrLine>) : OcrEngine {
    override fun recognise(image: PageImage): List<OcrLine> = lines
  }

  private class OnePage(private val w: Int = 1200, private val h: Int = 1600) : PageImageSource {
    override fun pageCount() = 1
    override fun render(pageIndex: Int, targetWidthPx: Int) =
      PageImage(pageIndex, w, h, IntArray(w * h) { 0xFFFFFFFF.toInt() })
  }

  private fun line(text: String, top: Int, left: Int = 100, right: Int = 400, conf: Double = 0.95) =
    OcrLine(text, conf, left, top, right, top + 30)

  private fun extract(lines: List<OcrLine>, pages: PageImageSource = OnePage()) =
    OcrExtractor(ScriptedEngine(lines), pages)
      .extract(ExtractInput(ByteArray(0), "photo.jpg", kind = SourceKind.IMAGE))

  // ── reconstruction ───────────────────────────────────────────────────────────

  @Test fun `fragments on the same baseline become one line`() {
    // A recogniser routinely splits "Back Squat" and "5x5 @ 100kg" into separate blocks on the same
    // physical line. Without baseline clustering they become two lines and the exercise loses its
    // prescription entirely.
    val doc = extract(listOf(
      line("Day 1 - Lower Body", top = 100),
      line("Back Squat", top = 200, left = 100, right = 300),
      line("5x5 @ 100kg", top = 205, left = 320, right = 520),
      line("Romanian Deadlift", top = 260, left = 100, right = 300),
      line("3x8 @ 80kg", top = 262, left = 320, right = 520),
      line("Leg Press", top = 320, left = 100, right = 300),
      line("3x12 @ 140kg", top = 322, left = 320, right = 520)
    ))
    val lines = doc.text.lines()
    assertTrue("expected the fragments joined, got $lines",
      lines.any { it.contains("Back Squat") && it.contains("5x5") })
    assertEquals(4, lines.size)
  }

  @Test fun `a wide gutter is read as a column boundary`() {
    // Four short fragments per row with a big gap between them is a table, and reading it as a
    // run-on sentence loses which number belongs to which lift.
    val rows = listOf(200, 250, 300, 350, 400, 450)
    val lines = rows.flatMapIndexed { i, y ->
      listOf(
        line("Exercise$i", top = y, left = 50, right = 250),
        line("3", top = y, left = 600, right = 640),
        line("8", top = y, left = 900, right = 940)
      )
    }
    val doc = extract(lines)
    assertTrue("expected tab-separated columns, got:\n${doc.text}", doc.text.contains("\t"))
  }

  // ── repair ───────────────────────────────────────────────────────────────────

  @Test fun `confusables are repaired inside numbers and nowhere else`() {
    val doc = extract(listOf(
      line("Barbell Row 3xlO @ 8O kg", top = 200),
      line("Overhead Press 3x8 @ 45kg", top = 250),
      line("Face Pull 3x15 @ 20kg", top = 300)
    ))
    assertTrue("numbers were not repaired: ${doc.text}", doc.text.contains("3x10"))
    assertTrue("numbers were not repaired: ${doc.text}", doc.text.contains("80"))
    assertTrue("the movement name must not be touched: ${doc.text}", doc.text.contains("Row"))
  }

  @Test fun `words that merely look unit-like are never repaired`() {
    // "Press" is five letters ending in `s` and contains an `s`. An earlier rule read that as a
    // unit-suffixed numeric token and returned "Pre5". Turning a movement name into gibberish is
    // far worse than leaving a misread digit alone.
    for (word in listOf("Press", "Rows", "Dips", "Legs", "Abs", "Sets", "Reps", "Bs", "Is")) {
      assertEquals(word, OcrRepair.repairNumerics(word))
    }
    // …while the genuinely numeric forms still repair.
    assertEquals("10kg", OcrRepair.repairNumerics("lOkg"))
    assertEquals("3x10", OcrRepair.repairNumerics("3xlO"))
    assertEquals("80", OcrRepair.repairNumerics("8O"))
    // An abbreviation with a digit in it is a name, not a number.
    assertEquals("T2B", OcrRepair.repairNumerics("T2B"))
  }

  @Test fun `a repaired page still parses into a program`() {
    val doc = extract(listOf(
      line("Day l", top = 100),
      line("Back Squat 5x5 @ lOOkg", top = 200),
      line("Bench Press 3x8 @ 8Okg", top = 250),
      line("Barbell Row 3x10 @ 6Okg", top = 300)
    ))
    val result = UniversalProgramImporter.import("Photo", doc.text)
    val names = result.program.sessions.flatMap { s -> s.exercises.map { it.name } }
    assertEquals(listOf("Back Squat", "Bench Press", "Barbell Row"), names)
    val squat = result.program.sessions.first().exercises.first()
    assertEquals(5, squat.setsStated)
    assertEquals(100.0, squat.loadValue!!, 0.01)
  }

  // ── confidence ───────────────────────────────────────────────────────────────

  @Test fun `page confidence becomes document confidence`() {
    val doc = extract(List(6) { line("Bench Press 3x8 @ 80kg", top = 200 + it * 50, conf = 0.72) })
    assertEquals(0.72, doc.confidence, 0.02)
    assertTrue("per-line confidence must survive", doc.lineConfidence.isNotEmpty())
  }

  @Test fun `a low-confidence page can never produce a high-confidence slot`() {
    // The rule that stops an OCR read looking exactly like a clean CSV.
    val doc = extract(List(6) { line("Bench Press 3x8 @ 80kg", top = 200 + it * 50, conf = 0.62) })
    val draft = com.wildodds.gymtracker.data.importer.Confidence.applyDocumentConfidence(
      UniversalProgramImporter.import("Photo", doc.text).draft, doc.confidence)
    val worst = draft.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }.maxOf { it.confidence }
    assertTrue("best slot scored $worst on a 0.62-confidence page", worst <= 0.63)
  }

  @Test fun `a line is only as trustworthy as its worst fragment`() {
    val doc = extract(listOf(
      line("Back Squat", top = 200, left = 100, right = 300, conf = 0.98),
      line("5x5", top = 202, left = 320, right = 400, conf = 0.40),
      line("Romanian Deadlift 3x8 @ 80kg", top = 260, conf = 0.96),
      line("Leg Press 3x12 @ 140kg", top = 320, conf = 0.96)
    ))
    assertTrue("expected the low fragment to dominate: ${doc.lineConfidence}",
      doc.lineConfidence.first() <= 0.41)
  }

  // ── refusal ──────────────────────────────────────────────────────────────────

  @Test fun `an illegible page is refused rather than imported`() {
    val e = runCatching {
      extract(List(6) { line("Bench Press 3x8", top = 200 + it * 50, conf = 0.30) })
    }.exceptionOrNull()
    assertTrue("expected Illegible, got $e", e is IngestError.Illegible)
    assertTrue("the message must tell the user what to do",
      e!!.message!!.contains("photo", true) || e.message!!.contains("paste", true))
  }

  @Test fun `a page with almost no text is refused`() {
    val e = runCatching { extract(listOf(line("ab", top = 200))) }.exceptionOrNull()
    assertTrue("expected Illegible, got $e", e is IngestError.Illegible)
  }

  @Test fun `an empty recognition is refused`() {
    val e = runCatching { extract(emptyList()) }.exceptionOrNull()
    assertTrue("expected an IngestError, got $e", e is IngestError)
  }

  // ── multi-page ───────────────────────────────────────────────────────────────

  @Test fun `several photos are one document in page order`() {
    // Four photos of a printout are one program, not four one-day programs.
    val pages = object : PageImageSource {
      override fun pageCount() = 3
      override fun render(pageIndex: Int, targetWidthPx: Int) =
        PageImage(pageIndex, 100, 100, IntArray(10_000) { 0xFFFFFFFF.toInt() })
    }
    var page = 0
    val engine = object : OcrEngine {
      override fun recognise(image: PageImage): List<OcrLine> {
        val n = page++
        return listOf(
          line("Day ${n + 1}", top = 100),
          line("Bench Press 3x8 @ 80kg", top = 160),
          line("Barbell Row 3x10 @ 60kg", top = 220)
        )
      }
    }
    val doc = OcrExtractor(engine, pages)
      .extract(ExtractInput(ByteArray(0), "photos", kind = SourceKind.IMAGE))
    val result = UniversalProgramImporter.import("Photos", doc.text)
    assertEquals("three photos should be three days of one program", 3,
      result.program.sessions.size)
    assertEquals(6, result.exerciseCount)
  }

  @Test fun `the page cap is honoured and reported`() {
    val pages = object : PageImageSource {
      override fun pageCount() = 50
      override fun render(pageIndex: Int, targetWidthPx: Int) =
        PageImage(pageIndex, 100, 100, IntArray(10_000) { 0xFFFFFFFF.toInt() })
    }
    val engine = ScriptedEngine(listOf(
      line("Bench Press 3x8 @ 80kg", top = 100),
      line("Barbell Row 3x10", top = 160)
    ))
    val doc = OcrExtractor(engine, pages).extract(ExtractInput(
      ByteArray(0), "big.pdf", kind = SourceKind.PDF_SCANNED,
      limits = IngestLimits(maxOcrPages = 4)))
    assertTrue("the cap must be surfaced: ${doc.warnings}",
      doc.warnings.any { it.message.contains("of 50 pages") })
  }

  @Test fun `an OCR read always warns that it was read by a machine`() {
    val doc = extract(List(6) { line("Bench Press 3x8 @ 80kg", top = 200 + it * 50) })
    assertTrue("the user must be told to check the numbers: ${doc.warnings}",
      doc.warnings.any { it.message.contains("recognition", true) })
  }

  // ── preprocessing ────────────────────────────────────────────────────────────

  @Test fun `adaptive threshold survives an illumination gradient`() {
    // The photo people actually take: bright at the top, shadowed at the bottom. A single global
    // cutoff turns the shadowed half solid black; a local mean follows the light.
    val w = 200
    val h = 200
    val px = IntArray(w * h) { i ->
      val y = i / w
      val x = i % w
      val background = 250 - (y * 120 / h)          // 250 at the top, 130 at the bottom
      val ink = (x / 10) % 2 == 0                    // vertical stripes of "text"
      val v = if (ink) (background - 60).coerceAtLeast(0) else background
      (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val out = ImagePrep.adaptiveThreshold(PageImage(0, w, h, px))
    fun blackFraction(fromY: Int, toY: Int): Double {
      var black = 0
      var total = 0
      for (y in fromY until toY) for (x in 0 until w) {
        if ((out.argb[y * w + x] and 0xFF) == 0) black++
        total++
      }
      return black.toDouble() / total
    }
    val top = blackFraction(20, 80)
    val bottom = blackFraction(120, 180)
    assertTrue("the top half lost its text: $top", top in 0.2..0.8)
    assertTrue("the shadowed half went solid: $bottom", bottom in 0.2..0.8)
  }

  @Test fun `skew is estimated within a degree`() {
    // Horizontal text lines, rotated by a known angle.
    val w = 400
    val h = 400
    val angle = 4.0
    val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
    val tan = kotlin.math.tan(Math.toRadians(angle))
    for (row in 1..9) {
      val baseY = row * 40
      for (x in 40 until 360) {
        val y = baseY + (x * tan).toInt()
        if (y in 0 until h) for (d in 0..3) {
          val yy = y + d
          if (yy in 0 until h) px[yy * w + x] = 0xFF000000.toInt()
        }
      }
    }
    val estimated = ImagePrep.estimateSkew(PageImage(0, w, h, px))
    assertTrue("estimated $estimated for a $angle degree skew",
      kotlin.math.abs(estimated - angle) <= 1.5)
  }

  @Test fun `upscaling small text does not distort the aspect ratio`() {
    val src = PageImage(0, 100, 50, IntArray(5000) { 0xFFFFFFFF.toInt() })
    val out = ImagePrep.upscale(src, 400)
    assertEquals(400, out.width)
    assertEquals(200, out.height)
  }

  @Test fun `preprocessing never returns an empty image`() {
    for (size in listOf(1 to 1, 5 to 5, 64 to 64, 300 to 200)) {
      val img = PageImage(0, size.first, size.second,
        IntArray(size.first * size.second) { 0xFF808080.toInt() })
      val out = ImagePrep.prepare(img, minWidth = 200)
      assertTrue("prepare(${size}) produced ${out.width}x${out.height}",
        out.width > 0 && out.height > 0 && out.argb.isNotEmpty())
    }
  }
}

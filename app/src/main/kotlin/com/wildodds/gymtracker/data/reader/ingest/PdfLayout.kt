package com.wildodds.gymtracker.data.reader.ingest

/**
 * Everything the PDF path decides once the glyphs are in hand — and nothing that needs a device.
 *
 * `PdfExtractor` has to live in the Android source set because PDFBox does: its stream engine loads
 * glyph resources through Android's asset loader and will not even static-initialise on a plain JVM.
 * That is a fine reason for the PDFBox *binding* to be untestable off-device. It is not a reason for
 * the reconstruction to be, and while these two lived in one file the reconstruction was untested by
 * association — including the column rebuild, which is the single fix that made coach PDFs import at
 * all.
 *
 * So the split is by dependency, not by size: glyph capture stays with PDFBox, and every decision
 * made from those glyphs — is this a scan, are there columns, which lines are page furniture, what
 * confidence does a reconstruction deserve — lives here, pure and injectable, and is tested against
 * synthetic pages.
 */
object PdfLayout {

  /** Below this many characters per page, the PDF is a scan wearing a PDF wrapper. */
  const val MIN_CHARS_PER_PAGE = 24

  data class Assembled(
    val text: String,
    val tables: List<Grid>,
    /** True when at least one page rebuilt into two or more columns. */
    val columnar: Boolean,
    /** A positional reconstruction is a reading, not a transcription, and this says so. */
    val confidence: Double,
    val warnings: List<IngestWarning>
  )

  /**
   * True when there is too little selectable text for this to be a digital PDF.
   *
   * Deliberately not an error: the caller routes it to OCR. An empty document and "this is a
   * photograph of a page" are different facts and produce different next steps.
   */
  fun looksScanned(readingOrder: String, pages: Int): Boolean {
    if (pages <= 0) return true
    return readingOrder.count { !it.isWhitespace() } < MIN_CHARS_PER_PAGE * pages / 2
  }

  /**
   * Rebuilds the pages from their glyph runs.
   *
   * @param pages one list of positioned glyph runs per page, in page order.
   * @param readingOrder PDFBox's own reading-order text, used when the reconstruction finds no
   *   columns — a single-column PDF is exactly the case reading order already handles correctly.
   */
  fun assemble(pages: List<List<OcrLine>>, readingOrder: String): Assembled {
    val rebuilt = pages.map { runs ->
      if (runs.isEmpty()) Rebuilt("", 0) else OcrRepair.repair(runs).let { Rebuilt(it.text, it.columnCount) }
    }
    val columnar = rebuilt.any { it.columnCount >= 2 }
    val text = if (columnar) rebuilt.joinToString("\n") { it.text } else readingOrder
    val tables = rebuilt.withIndex()
      .filter { it.value.columnCount >= 2 }
      .map { (i, p) -> Grid("page ${i + 1}", p.text.lines().map { it.split('\t') }) }
    val warnings = if (!columnar) emptyList() else listOf(
      IngestWarning("Columns were reconstructed from the page layout — check the numbers line up."))
    return Assembled(
      // With real page boundaries in hand, furniture is found at the page EDGES. Without them
      // (reading order is one undivided string) it falls back to plain repetition.
      text = if (columnar) stripRepeatedPageFurniture(rebuilt.map { it.text })
             else stripRepeatedPageFurniture(text),
      tables = tables,
      columnar = columnar,
      confidence = if (columnar) 0.9 else 1.0,
      warnings = warnings
    )
  }

  private data class Rebuilt(val text: String, val columnCount: Int)

  /**
   * A repeated header or footer, removed using the page boundaries we actually have.
   *
   * Repetition ALONE is not enough evidence, and assuming it was cost real training: a four-week
   * PDF whose weeks share an exercise list has every one of those rows repeated four times, and
   * three identical pages had every line deleted as furniture. So a line has to repeat AND sit
   * within [EDGE_LINES] of a page boundary — which is what a header or footer is, and what a
   * prescription row in the middle of a page is not.
   *
   * Position is used as a filter here, never as the detector: a coach's footer is as likely to be
   * at the top of the page as the bottom, so both edges count.
   */
  fun stripRepeatedPageFurniture(pages: List<String>, minPages: Int = 3): String {
    if (pages.size < minPages) return pages.joinToString("\n")
    val perPage = pages.map { page -> page.lines() }

    fun edges(lines: List<String>): List<String> {
      val meaningful = lines.map { it.trim() }.filter { it.length in 3..80 }
      return (meaningful.take(EDGE_LINES) + meaningful.takeLast(EDGE_LINES)).distinct()
    }

    // Counted once per page: a header printed twice on one page is still one page's worth.
    val counts = HashMap<String, Int>()
    for (lines in perPage) for (line in edges(lines)) counts[line] = (counts[line] ?: 0) + 1

    val furniture = counts.filterValues { it >= minPages }.keys
      .filterNot { carriesPrescription(it) }
      // A rebuilt page puts tabs between its columns, and a header spanning the page has none of
      // them: renderColumns trims the trailing empties, so a banner in column one comes out tab-free
      // while every content row keeps its tabs. Three identical pages of a table would otherwise
      // have every row deleted as a repeat of itself.
      .filterNot { it.contains('\t') }
    if (furniture.isEmpty()) return pages.joinToString("\n")

    val furnitureSet = furniture.toSet()
    return perPage.joinToString("\n") { lines ->
      lines.filterIndexed { i, line ->
        // Only at the edges: the same words in the middle of a page are content.
        val atEdge = i < EDGE_LINES || i >= lines.size - EDGE_LINES
        !(atEdge && line.trim() in furnitureSet)
      }.joinToString("\n")
    }
  }

  /** How far from a page boundary a line can be and still be a header or footer. */
  const val EDGE_LINES = 3

  /** A repeated "3x8" is real content, whatever else it looks like. */
  private fun carriesPrescription(line: String): Boolean =
    Regex("""\d\s*[xX×]\s*\d""").containsMatchIn(line)

  /**
   * The same idea without page boundaries, for PDFBox's reading-order text — one undivided string,
   * so repetition is all there is to go on.
   */
  fun stripRepeatedPageFurniture(text: String, minPages: Int = 3): String {
    val lines = text.lines()
    if (lines.size < minPages * 4) return text
    val counts = lines.map { it.trim() }.filter { it.length in 3..80 }
      .groupingBy { it }.eachCount()
    val furniture = counts.filter { (line, n) -> n >= minPages && !carriesPrescription(line) }.keys
    if (furniture.isEmpty()) return text
    return lines.filter { it.trim() !in furniture }.joinToString("\n")
  }
}

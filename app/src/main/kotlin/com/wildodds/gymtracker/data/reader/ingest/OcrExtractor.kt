package com.wildodds.gymtracker.data.reader.ingest

/**
 * Scanned PDFs and photos: rasterise → preprocess → recognise → repair → text.
 *
 * Pure. It takes a [PageImageSource] (which the Android layer backs with `PdfRenderer` or a decoded
 * bitmap) and an [OcrEngine] (which the Android layer backs with ML Kit's **bundled** model, so the
 * whole path works in airplane mode on first launch). That split is what lets the corpus test this
 * with a scripted fake recogniser and assert the repair and legibility rules exactly.
 *
 * The most important behaviour here is the refusal. When a page comes back below the confidence
 * threshold or with too few legible glyphs, this throws [IngestError.Illegible] rather than
 * returning what it managed to read. A program assembled from OCR noise looks completely normal —
 * plausible exercise names, plausible numbers — and the user has no way to tell it is wrong. Failing
 * loudly with "try a flatter, brighter photo" is the only honest option.
 */
class OcrExtractor(
  private val engine: OcrEngine,
  private val images: PageImageSource
) : Extractor {

  override val handles = setOf(SourceKind.PDF_SCANNED, SourceKind.IMAGE)

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val pageCount = images.pageCount()
    if (pageCount <= 0) throw IngestError.Empty("There are no pages to read.")
    val pages = minOf(pageCount, input.limits.maxOcrPages)

    val warnings = ArrayList<IngestWarning>()
    if (pageCount > pages) warnings += IngestWarning("Only the first $pages of $pageCount pages were read.")

    val started = System.nanoTime()
    val textParts = ArrayList<String>(pages)
    val confidences = ArrayList<Double>()
    var glyphs = 0
    var columnsSeen = 0

    for (p in 0 until pages) {
      input.cancelCheck()
      if ((System.nanoTime() - started) / 1_000_000 > input.limits.maxOcrMillis) {
        warnings += IngestWarning("Reading timed out after page $p — import what was read, or try fewer pages.")
        break
      }
      input.progress.report("Reading page ${p + 1} of $pages", p + 1, pages)

      val raw = images.render(p, targetWidthPx = 2000)
      val prepared = ImagePrep.prepare(raw)
      val lines = engine.recognise(prepared)
      if (lines.isEmpty()) continue

      val repaired = OcrRepair.repair(lines)
      if (repaired.text.isBlank()) continue
      textParts += repaired.text
      confidences += repaired.lineConfidence
      glyphs += repaired.alphanumericGlyphs
      if (repaired.columnCount >= 2) columnsSeen++
    }

    val mean = if (confidences.isEmpty()) 0.0 else confidences.average()
    if (glyphs < OcrRepair.MIN_GLYPHS) {
      throw IngestError.Illegible(
        "There isn't enough readable text in that image — try a flatter, brighter photo of the page.")
    }
    if (mean < OcrRepair.MIN_MEAN_CONFIDENCE) {
      throw IngestError.Illegible(
        "That image is too low quality to read reliably — try a flatter, brighter photo, " +
        "or paste the text instead.")
    }

    // Multi-page documents and multi-image batches are ONE document, in page order: a user who
    // photographs four pages of a printout has one program, not four.
    val text = textParts.joinToString("\n")
    if (columnsSeen > 0) {
      warnings += IngestWarning("Columns were reconstructed from the page layout — check the numbers line up.")
    }
    warnings += IngestWarning("Read by on-device text recognition — check the numbers against the original.")

    return ExtractedDocument(
      text = text,
      sourceKind = input.kind,
      // Straight through to slot confidence: a 0.6-confidence page can never yield a 0.90 slot.
      confidence = mean,
      warnings = warnings,
      lineConfidence = confidences
    )
  }
}

/**
 * Several images picked at once — four photos of a printout — read as ONE document in the order the
 * user picked them. Any other treatment produces four one-day programs.
 */
class MultiImageSource(private val images: List<PageImage>) : PageImageSource {
  override fun pageCount(): Int = images.size
  override fun render(pageIndex: Int, targetWidthPx: Int): PageImage = images[pageIndex]
}

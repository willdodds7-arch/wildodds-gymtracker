package com.wildodds.gymtracker.data.reader.android

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.wildodds.gymtracker.data.reader.ingest.ExtractInput
import com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument
import com.wildodds.gymtracker.data.reader.ingest.Extractor
import com.wildodds.gymtracker.data.reader.ingest.IngestError
import com.wildodds.gymtracker.data.reader.ingest.IngestWarning
import com.wildodds.gymtracker.data.reader.ingest.OcrLine
import com.wildodds.gymtracker.data.reader.ingest.PdfLayout
import com.wildodds.gymtracker.data.reader.ingest.SourceKind

/**
 * Digital PDFs — PDFBox for the glyphs, and a **positional pass** to put them back into columns.
 *
 * `PDFTextStripper` returns text in reading order, which for a coach's four-column PDF means
 * "Bench Press", "3", "8", "80kg", "Row", "3", "10", "40kg" as one undifferentiated stream. Nothing
 * downstream can tell which number belongs to which lift, so those imports came out as a list of
 * exercises with no prescriptions. That single problem accounts for most failed coach-PDF imports.
 *
 * So instead of trusting reading order, this collects every glyph's x/y and rebuilds the page:
 * cluster glyph runs into lines by baseline, find column gutters from an x-gap histogram over the
 * whole page, and emit tab-separated rows. It reuses [OcrRepair]'s geometry for both — a PDF's
 * positioned glyphs and OCR's positioned words are the same reconstruction problem, and having one
 * implementation means fixing it once.
 *
 * When the reconstruction finds no columns, the plain reading-order text is used, because a
 * single-column PDF is exactly the case reading order already handles correctly.
 *
 * This file holds ONLY what needs PDFBox: opening the document and capturing each glyph run's
 * position. PDFBox will not static-initialise off a device, so anything living here is untestable
 * except on one — which is why every decision made FROM those positions lives in [PdfLayout].
 *
 * A PDF with no extractable text at all is a SCAN. That is not an error and not an empty document —
 * it is a different pipeline, so this reports [SourceKind.PDF_SCANNED] and lets the caller route to
 * OCR rather than importing a blank program.
 */
class PdfExtractor : Extractor {

  override val handles = setOf(SourceKind.PDF_DIGITAL)

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val doc = try {
      PDDocument.load(input.bytes.inputStream())
    } catch (e: Exception) {
      throw IngestError.Corrupt("That PDF couldn't be opened — it may be damaged or encrypted.", e)
    }

    return doc.use { pdf ->
      val pages = minOf(pdf.numberOfPages, input.limits.maxPages)
      if (pages <= 0) throw IngestError.Empty("That PDF has no pages.")
      val warnings = ArrayList<IngestWarning>()
      if (pdf.numberOfPages > pages) {
        warnings += IngestWarning("Only the first $pages of ${pdf.numberOfPages} pages were read.")
      }

      val collector = PositionalStripper(input)
      collector.startPage = 1
      collector.endPage = pages
      val readingOrder = try {
        collector.getText(pdf)
      } catch (e: IngestError) {
        throw e
      } catch (e: Exception) {
        throw IngestError.Corrupt("That PDF's text couldn't be read.", e)
      }

      if (PdfLayout.looksScanned(readingOrder, pages)) {
        // Deliberately not an error: the caller routes this to OCR.
        return@use ExtractedDocument(
          text = "", sourceKind = SourceKind.PDF_SCANNED, confidence = 0.0,
          warnings = warnings + IngestWarning("This PDF has no selectable text — it looks like a scan.")
        )
      }

      // Every decision from here is made from the glyph positions alone, so it lives in the pure
      // [PdfLayout] where it can be tested without a device.
      val assembled = PdfLayout.assemble(collector.pages, readingOrder)

      ExtractedDocument(
        text = assembled.text,
        tables = assembled.tables,
        title = pdf.documentInformation?.title?.trim()?.takeIf { it.isNotBlank() },
        sourceKind = SourceKind.PDF_DIGITAL,
        confidence = assembled.confidence,
        warnings = warnings + assembled.warnings
      )
    }
  }

  /**
   * Captures each glyph run with its page-space position. PDFBox reports y downward from the top,
   * which is already what the clustering wants.
   */
  private class PositionalStripper(private val input: ExtractInput) : PDFTextStripper() {
    val pages = ArrayList<MutableList<OcrLine>>()
    private var current = mutableListOf<OcrLine>()

    override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage?) {
      input.cancelCheck()
      input.progress.report("Reading page ${pages.size + 1}", pages.size + 1, endPage)
      current = mutableListOf()
      super.startPage(page)
    }

    override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage?) {
      pages.add(current)
      super.endPage(page)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>?) {
      val positions = textPositions
      if (!text.isBlank() && !positions.isNullOrEmpty()) {
        val left = positions.minOf { it.xDirAdj }
        val right = positions.maxOf { it.xDirAdj + it.widthDirAdj }
        val top = positions.minOf { it.yDirAdj - it.heightDir }
        val bottom = positions.maxOf { it.yDirAdj }
        current += OcrLine(text.trim(), 1.0, left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
      }
      super.writeString(text, textPositions)
    }
  }
}

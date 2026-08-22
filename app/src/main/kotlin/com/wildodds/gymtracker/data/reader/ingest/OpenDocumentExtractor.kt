package com.wildodds.gymtracker.data.reader.ingest

/**
 * OpenDocument: `.ods` (LibreOffice / Google Sheets download) and `.odt`.
 *
 * Same zip-plus-XML shape as OOXML but a different vocabulary, and one trap that makes a naive
 * reader produce nonsense: ODF compresses repeats with `table:number-columns-repeated`, so a row of
 * 1,024 empty cells is a single element with `repeated="1024"`. Ignoring it collapses every row to
 * its non-empty cells and every column lines up with the wrong header. Honouring it naively lets a
 * trailing `repeated="16384"` allocate a 16k-wide row, so it is capped to the row's real content.
 */
object OpenDocumentExtractor : Extractor {

  override val handles = setOf(SourceKind.ODS, SourceKind.ODT)

  /** ODF's own cap on a sensible repeat. Beyond this the repeat is padding, not data. */
  private const val MAX_REPEAT = 512

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val zip = input.archive ?: ZipReader.read(input.bytes, input.limits, input.progress)
    val content = zip["content.xml"] ?: throw IngestError.Corrupt("That OpenDocument file has no content.")
    val xml = content.toXmlString()

    val title = zip["meta.xml"]?.let { readMetaTitle(it.toXmlString()) }

    return if (input.kind == SourceKind.ODS) {
      val grids = readSpreadsheet(xml, input.limits, input.progress)
      if (grids.isEmpty()) throw IngestError.Empty("That spreadsheet has no readable sheets.")
      ExtractedDocument(
        text = grids.joinToString("\n\n") { g -> "# ${g.name}\n" + g.rows.joinToString("\n") { it.joinToString("\t") } },
        tables = grids, title = title, sourceKind = SourceKind.ODS, warnings = zip.warnings
      )
    } else {
      val text = readTextDocument(xml)
      if (text.isBlank()) throw IngestError.Empty("That document has no text in it.")
      ExtractedDocument(text = text, title = title, sourceKind = SourceKind.ODT, warnings = zip.warnings)
    }
  }

  // ── .ods ─────────────────────────────────────────────────────────────────────

  fun readSpreadsheet(xml: String, limits: IngestLimits, progress: IngestProgress): List<Grid> {
    val grids = ArrayList<Grid>()
    var sheetName = ""
    var rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    var cellRepeat = 1
    var rowRepeat = 1
    var depth = 0
    var inCell = false
    var percentCell = false
    val text = StringBuilder()
    val percent = HashSet<Pair<Int, Int>>()
    var budget = limits.maxCells

    fun endSheet() {
      if (sheetName.isNotEmpty() && rows.any { r -> r.any { it.isNotBlank() } }) {
        // Trim the trailing all-blank padding rows ODF loves to emit.
        val last = rows.indexOfLast { r -> r.any { it.isNotBlank() } }
        grids += Grid(sheetName, rows.take(last + 1), percentCells = percent.toSet())
      }
      rows = ArrayList(); percent.clear()
    }

    MiniXml.parse(xml) { e ->
      if (progress.isCancelled()) throw IngestError.Cancelled()
      when (e) {
        is MiniXml.Event.Start -> when (e.name) {
          "table" -> { endSheet(); sheetName = e.attrs["table:name"] ?: e.attrs["name"] ?: "Sheet ${grids.size + 1}" }
          "table-row" -> {
            row = ArrayList()
            rowRepeat = (e.attrs["table:number-rows-repeated"] ?: e.attrs["number-rows-repeated"])
              ?.toIntOrNull()?.coerceIn(1, MAX_REPEAT) ?: 1
          }
          "table-cell", "covered-table-cell" -> {
            inCell = true; depth = 0; text.setLength(0)
            cellRepeat = (e.attrs["table:number-columns-repeated"] ?: e.attrs["number-columns-repeated"])
              ?.toIntOrNull()?.coerceIn(1, MAX_REPEAT) ?: 1
            percentCell = (e.attrs["office:value-type"] ?: e.attrs["value-type"]) == "percentage"
            // A typed cell carries its machine value in an attribute; the displayed text may be
            // rounded, so prefer the attribute when present.
            val v = e.attrs["office:value"] ?: e.attrs["value"]
            if (v != null) {
              val d = v.toDoubleOrNull()
              text.append(if (d != null && percentCell) fmt(d * 100) + "%" else if (d != null) fmt(d) else v)
              depth = -1  // suppress the display text, we already have the value
            }
          }
          "p", "span" -> if (inCell && depth >= 0) depth++
        }
        is MiniXml.Event.Text -> if (inCell && depth > 0) text.append(e.value)
        is MiniXml.Event.End -> when (e.name) {
          "table-cell", "covered-table-cell" -> {
            val v = text.toString().trim()
            repeat(cellRepeat) { if (budget-- > 0) row.add(v) }
            if (percentCell && v.isNotBlank()) percent += rows.size to (row.size - 1)
            inCell = false; depth = 0
          }
          "p" -> if (inCell && depth > 0) { depth--; if (depth > 0) text.append(' ') }
          "table-row" -> {
            // Trailing repeated-blank padding is not data; keep it out of the row width.
            val last = row.indexOfLast { it.isNotBlank() }
            val trimmed = if (last < 0) emptyList() else row.take(last + 1)
            repeat(rowRepeat) { rows.add(trimmed) }
          }
          "table" -> endSheet()
        }
      }
      budget > 0
    }
    endSheet()
    return grids.take(limits.maxSheets)
  }

  // ── .odt ─────────────────────────────────────────────────────────────────────

  /**
   * Paragraphs and headings become lines; a table row becomes one tab-separated line, so a program
   * written in an ODT table reaches the same downstream path as one written in a docx table.
   */
  fun readTextDocument(xml: String): String {
    val out = StringBuilder()
    val cell = StringBuilder()
    var inCell = false
    var rowCells = ArrayList<String>()
    var inRow = false

    MiniXml.parse(xml) { e ->
      when (e) {
        is MiniXml.Event.Start -> when (e.name) {
          "table-row" -> { inRow = true; rowCells = ArrayList() }
          "table-cell" -> { inCell = true; cell.setLength(0) }
        }
        is MiniXml.Event.Text -> if (inCell) cell.append(e.value) else out.append(e.value)
        is MiniXml.Event.End -> when (e.name) {
          "table-cell" -> { rowCells.add(cell.toString().trim()); inCell = false }
          "table-row" -> { out.append(rowCells.joinToString("\t")).append('\n'); inRow = false }
          "p", "h" -> if (!inCell && !inRow) out.append('\n')
          "tab" -> if (inCell) cell.append('\t')
        }
      }
      true
    }
    return out.toString()
  }

  private fun readMetaTitle(xml: String): String? {
    var inTitle = false
    val sb = StringBuilder()
    MiniXml.parse(xml) { e ->
      when (e) {
        is MiniXml.Event.Start -> if (e.name == "title") inTitle = true
        is MiniXml.Event.Text -> if (inTitle) sb.append(e.value)
        is MiniXml.Event.End -> if (e.name == "title") inTitle = false
      }
      true
    }
    return sb.toString().trim().takeIf { it.isNotBlank() }
  }

  private fun fmt(d: Double): String =
    if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e15) d.toLong().toString()
    else java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP)
      .stripTrailingZeros().toPlainString()
}

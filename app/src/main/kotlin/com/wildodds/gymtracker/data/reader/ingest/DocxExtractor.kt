package com.wildodds.gymtracker.data.reader.ingest

/**
 * `.docx` — paragraphs, runs, **and tables**.
 *
 * Tables are the point. Coaches put programs in Word tables constantly, and a paragraph-only reader
 * turns a four-column table into a stream of loose words with no relationship between an exercise
 * and its sets. Here a table row becomes both a `Grid` row (for the layout engine) and one
 * tab-separated text line (for the text path), so the same document is available to whichever
 * downstream stage reads it better.
 *
 * Two Word-specific details that a naive `<w:t>` scrape gets wrong:
 *  - `<w:tab/>` and `<w:br/>` carry no text but ARE separators; without them "3x8" and "80kg" in
 *    adjacent tab stops concatenate into "3x880kg".
 *  - Word splits a single word across runs whenever formatting changes mid-word, so runs must be
 *    concatenated with NO separator, while paragraphs get a newline.
 *
 * `.doc` (OLE2) is not handled here at all — [XlsExtractor] recognises it and refuses with a clear
 * message, which is better than emitting silent garbage.
 */
object DocxExtractor : Extractor {

  override val handles = setOf(SourceKind.DOCX)

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val zip = input.archive ?: ZipReader.read(input.bytes, input.limits, input.progress)
    val doc = zip["word/document.xml"]
      ?: throw IngestError.Corrupt("That .docx is missing its document body.")

    val parsed = parse(doc.toXmlString(), input.limits)
    if (parsed.text.isBlank() && parsed.tables.isEmpty()) {
      throw IngestError.Empty("That document has no text in it.")
    }
    val title = zip["docProps/core.xml"]?.let { readCoreTitle(it.toXmlString()) }

    return ExtractedDocument(
      text = parsed.text,
      tables = parsed.tables,
      title = title,
      sourceKind = SourceKind.DOCX,
      warnings = zip.warnings
    )
  }

  data class Parsed(val text: String, val tables: List<Grid>)

  fun parse(xml: String, limits: IngestLimits = IngestLimits.DEFAULT): Parsed {
    val out = StringBuilder()
    val tables = ArrayList<Grid>()

    // Word tables nest, so the row/cell state is a stack rather than a pair of flags.
    val tableStack = ArrayList<ArrayList<List<String>>>()
    var rowCells = ArrayList<String>()
    val cell = StringBuilder()
    val para = StringBuilder()
    var cellDepth = 0
    var budget = limits.maxCells

    fun sink(): StringBuilder = if (cellDepth > 0) cell else para

    MiniXml.parse(xml) { e ->
      when (e) {
        is MiniXml.Event.Start -> when (e.name) {
          "tbl" -> tableStack.add(ArrayList())
          "tr" -> rowCells = ArrayList()
          "tc" -> { cellDepth++; cell.setLength(0) }
          "tab" -> sink().append('\t')
          "br", "cr" -> sink().append(if (cellDepth > 0) ' ' else '\n')
        }
        is MiniXml.Event.Text -> sink().append(e.value)
        is MiniXml.Event.End -> when (e.name) {
          "p" -> {
            if (cellDepth > 0) {
              // Multiple paragraphs inside one cell are one cell value, space-joined.
              if (cell.isNotEmpty() && !cell.endsWith(' ')) cell.append(' ')
            } else {
              out.append(para.toString().trim()).append('\n')
              para.setLength(0)
            }
          }
          "tc" -> { cellDepth--; if (budget-- > 0) rowCells.add(cell.toString().trim()); cell.setLength(0) }
          "tr" -> tableStack.lastOrNull()?.add(rowCells.toList())
          "tbl" -> {
            val rows = tableStack.removeLastOrNull() ?: return@parse true
            if (rows.any { r -> r.any { it.isNotBlank() } }) {
              tables += Grid("table ${tables.size + 1}", rows)
              // Also emit the table into the text stream, so a program written as a table is
              // readable by the text pipeline as well as by the layout engine.
              for (r in rows) if (r.any { it.isNotBlank() }) out.append(r.joinToString("\t")).append('\n')
            }
          }
        }
      }
      budget > 0
    }
    if (para.isNotBlank()) out.append(para.toString().trim()).append('\n')

    return Parsed(out.toString(), tables)
  }

  private fun readCoreTitle(xml: String): String? {
    var inTitle = false
    val sb = StringBuilder()
    MiniXml.parse(xml) { e ->
      when (e) {
        is MiniXml.Event.Start -> if (e.qName == "dc:title") inTitle = true
        is MiniXml.Event.Text -> if (inTitle) sb.append(e.value)
        is MiniXml.Event.End -> if (e.qName == "dc:title") inTitle = false
      }
      true
    }
    return sb.toString().trim().takeIf { it.isNotBlank() }
  }
}

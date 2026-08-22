package com.wildodds.gymtracker.data.reader.ingest

/**
 * CSV, TSV, PSV and the semicolon-delimited CSV every European Excel writes.
 *
 * The previous reader split on a character and toggled a boolean for quotes, which meant a quoted
 * field containing a newline — a coach's multi-line note, which is extremely common — silently
 * split one exercise into two garbage rows. This is a real RFC-4180 reader: quoted newlines,
 * doubled quotes as escapes, ragged rows, and a delimiter chosen by column-count variance across
 * the file rather than by counting separators on line one.
 */
object DelimitedExtractor : Extractor {

  override val handles = setOf(SourceKind.DELIMITED)

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val decoded = TextDecoding.decode(input.bytes, input.limits.maxTextChars)
    if (decoded.text.isBlank()) throw IngestError.Empty("That file has no text in it.")

    val warnings = mutableListOf<IngestWarning>()
    // Confident inference first. When the file is too ragged for that - which happens, coaches edit
    // these by hand - fall back to the most-used separator and SAY SO, rather than refusing a file
    // something upstream already identified as a table.
    val delim = Sniffer.inferDelimiter(decoded.text) ?: fallbackDelimiter(decoded.text)?.also {
      warnings += IngestWarning(
        "The columns in that file are inconsistent; it was read as ${describe(it)}-separated.")
    } ?: throw IngestError.Corrupt("Couldn't work out how that table is separated.")

    val rows = parse(decoded.text, delim, input.limits.maxCells)
    if (rows.isEmpty()) throw IngestError.Empty("That table has no rows.")

    if (decoded.charset != Charsets.UTF_8) {
      warnings += IngestWarning("Read as ${decoded.charset.name()} rather than UTF-8.")
    }
    val widths = rows.map { it.size }.distinct()
    if (widths.size > 1) {
      warnings += IngestWarning(
        "Rows have between ${widths.min()} and ${widths.max()} columns — some cells may be shifted.")
    }

    return ExtractedDocument(
      // BOTH forms: the grid for the layout engine, and a flat text rendering so a delimited file
      // that is really a pasted program (one column of exercise lines) still reaches the text path.
      text = rows.joinToString("\n") { it.joinToString("\t") },
      tables = listOf(Grid(name = input.fileName.ifBlank { "table" }, rows = rows)),
      sourceKind = SourceKind.DELIMITED,
      warnings = warnings
    )
  }

  /** The separator that appears most often outside quotes, or null if none appears at all. */
  private fun fallbackDelimiter(text: String): Char? {
    val head = text.lineSequence().filter { it.isNotBlank() }.take(40).joinToString("\n")
    return charArrayOf('\t', ';', '|', ',')
      .map { d -> d to head.count { it == d } }
      .filter { it.second > 0 }
      // Ties go to the earlier, less ambiguous separator: a tab is almost never incidental
      // punctuation, a comma very often is.
      .maxWithOrNull(compareBy { it.second })?.first
  }

  private fun describe(d: Char) = when (d) {
    '\t' -> "tab"; ';' -> "semicolon"; '|' -> "pipe"; else -> "comma"
  }

  /**
   * RFC 4180, plus the two deviations real files contain: a lone `"` inside an unquoted field is a
   * literal quote, and a final row without a trailing newline still counts.
   */
  fun parse(text: String, delim: Char, maxCells: Int = Int.MAX_VALUE): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var inQuotes = false
    var cells = 0
    var i = 0

    fun endField() {
      row.add(field.toString().trim())
      field.setLength(0)
      cells++
    }
    fun endRow() {
      endField()
      // A row of nothing but empty cells is a blank line, not a data row.
      if (row.any { it.isNotEmpty() }) rows.add(row)
      row = ArrayList()
    }

    while (i < text.length) {
      if (cells >= maxCells) break
      val c = text[i]
      when {
        inQuotes -> when {
          c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
          c == '"' -> inQuotes = false
          else -> field.append(c)
        }
        // A quote only OPENS a field at its start; mid-field it is a literal (`5" ROM`).
        c == '"' && field.isEmpty() -> inQuotes = true
        c == delim -> endField()
        c == '\n' -> endRow()
        c == '\r' -> Unit  // CRLF: the \n does the work
        else -> field.append(c)
      }
      i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
  }
}

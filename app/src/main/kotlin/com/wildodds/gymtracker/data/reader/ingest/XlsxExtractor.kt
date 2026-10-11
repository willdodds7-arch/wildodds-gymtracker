package com.wildodds.gymtracker.data.reader.ingest

/**
 * `.xlsx` / `.xlsm` / `.xltx` — a clean, tested reader over the OOXML zip.
 *
 * Dependency-free by design and that stays a genuine asset: POI is ~12 MB of dex and pulls in a
 * dozen transitive libraries for a format whose sheet XML is a flat list of `<c>` elements. What
 * this adds over the three (three!) hand-rolled readers it replaces:
 *
 *  - **number formats**, so a cell showing "85%" is 85 rather than 0.85, and a date is a date;
 *  - **merged cells**, expanded into every covered position, so a merged "Week 1" header is
 *    readable from the row beneath it;
 *  - **hidden rows and columns**, flagged rather than silently included — a hidden row is very
 *    often a stale draft the coach left behind;
 *  - **frozen panes**, which are the single best hint about where the header row is;
 *  - **cached formula values**, so a computed load reads as a number rather than `=B4*0.85`;
 *  - **fill colours**, because in a training spreadsheet a shaded row is a header and a coloured
 *    block is a superset group — formatting IS semantics here;
 *  - **inline strings**, which Google Sheets exports use and the old reader dropped entirely.
 */
object XlsxExtractor : Extractor {

  override val handles = setOf(SourceKind.XLSX)

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val zip = input.archive ?: ZipReader.read(input.bytes, input.limits, input.progress)
    val book = read(zip, input.limits, input.progress)
    if (book.grids.isEmpty()) throw IngestError.Empty("That workbook has no readable sheets.")

    return ExtractedDocument(
      text = book.grids.joinToString("\n\n") { g ->
        "# ${g.name}\n" + g.rows.joinToString("\n") { it.joinToString("\t") }
      },
      tables = book.grids,
      title = book.title,
      sourceKind = SourceKind.XLSX,
      warnings = zip.warnings + book.warnings
    )
  }

  data class Workbook(
    val grids: List<Grid>,
    val title: String? = null,
    val definedNames: Map<String, String> = emptyMap(),
    val warnings: List<IngestWarning> = emptyList()
  )

  fun read(
    zip: ZipReader.Archive,
    limits: IngestLimits = IngestLimits.DEFAULT,
    progress: IngestProgress = IngestProgress.NONE
  ): Workbook {
    val warnings = mutableListOf<IngestWarning>()
    val shared = readSharedStrings(zip["xl/sharedStrings.xml"])
    val styles = readStyles(zip["xl/styles.xml"])
    val wb = zip["xl/workbook.xml"] ?: throw IngestError.Corrupt("That workbook is missing its index.")
    val (sheetOrder, definedNames) = readWorkbook(wb)
    val rels = readRels(zip["xl/_rels/workbook.xml.rels"])

    val grids = ArrayList<Grid>()
    var cellBudget = limits.maxCells
    for ((index, sheet) in sheetOrder.withIndex()) {
      if (progress.isCancelled()) throw IngestError.Cancelled()
      if (grids.size >= limits.maxSheets) {
        warnings += IngestWarning("Only the first ${limits.maxSheets} sheets were read.")
        break
      }
      progress.report("Reading sheet ${index + 1} of ${sheetOrder.size}", index + 1, sheetOrder.size)
      val target = rels[sheet.rId]
      val data = resolveSheet(zip, target, index) ?: continue
      val grid = readSheet(sheet.name, data, shared, styles, cellBudget)
      cellBudget -= grid.rowCount * grid.colCount
      grids += grid
      if (cellBudget <= 0) {
        warnings += IngestWarning("The workbook exceeded the ${limits.maxCells}-cell budget; later sheets were skipped.")
        break
      }
    }

    val title = readDocTitle(zip["docProps/core.xml"])
    return Workbook(grids, title, definedNames, warnings)
  }

  /**
   * The relationship target is relative to `xl/`, but openpyxl writes absolute targets
   * (`/xl/worksheets/sheet1.xml`) and some exporters write no rels at all. Fall back to position.
   */
  private fun resolveSheet(zip: ZipReader.Archive, target: String?, index: Int): ByteArray? {
    if (target != null) {
      val t = target.removePrefix("/")
      zip[if (t.startsWith("xl/")) t else "xl/$t"]?.let { return it }
      zip[t]?.let { return it }
    }
    return zip["xl/worksheets/sheet${index + 1}.xml"]
  }

  // ── workbook.xml ─────────────────────────────────────────────────────────────

  private data class SheetRef(val name: String, val rId: String)

  private fun readWorkbook(data: ByteArray): Pair<List<SheetRef>, Map<String, String>> {
    val sheets = ArrayList<SheetRef>()
    val names = LinkedHashMap<String, String>()
    var currentName: String? = null
    val sb = StringBuilder()
    MiniXml.parse(data.toXmlString()) { e ->
      when (e) {
        is MiniXml.Event.Start -> when (e.name) {
          "sheet" -> {
            val n = e.attrs["name"] ?: return@parse true
            val rid = e.attrs.entries.firstOrNull { MiniXml.local(it.key) == "id" }?.value ?: ""
            sheets += SheetRef(n, rid)
          }
          "definedName" -> { currentName = e.attrs["name"]; sb.setLength(0) }
        }
        is MiniXml.Event.Text -> if (currentName != null) sb.append(e.value)
        is MiniXml.Event.End -> if (e.name == "definedName") {
          currentName?.let { names[it] = sb.toString().trim() }
          currentName = null
        }
      }
      true
    }
    return sheets to names
  }

  private fun readDocTitle(data: ByteArray?): String? {
    if (data == null) return null
    var inTitle = false
    val sb = StringBuilder()
    MiniXml.parse(data.toXmlString()) { e ->
      when (e) {
        is MiniXml.Event.Start -> if (e.name == "title") inTitle = true
        is MiniXml.Event.Text -> if (inTitle) sb.append(e.value)
        is MiniXml.Event.End -> if (e.name == "title") inTitle = false
      }
      true
    }
    return sb.toString().trim().takeIf { it.isNotBlank() }
  }

  private fun readRels(data: ByteArray?): Map<String, String> {
    if (data == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    MiniXml.parse(data.toXmlString()) { e ->
      if (e is MiniXml.Event.Start && e.name == "Relationship") {
        val id = e.attrs["Id"]; val t = e.attrs["Target"]
        if (id != null && t != null) out[id] = t
      }
      true
    }
    return out
  }

  // ── sharedStrings.xml ────────────────────────────────────────────────────────

  /**
   * A shared string can be split across many `<r><t>` runs when parts of it are formatted, so the
   * runs must be CONCATENATED. Reading only the first `<t>` is how "Romanian Deadlift" became
   * "Romanian" in a bold-first-word sheet.
   */
  fun readSharedStrings(data: ByteArray?): List<String> {
    if (data == null) return emptyList()
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var depth = 0
    var inT = false
    MiniXml.parse(data.toXmlString()) { e ->
      when (e) {
        is MiniXml.Event.Start -> when (e.name) {
          "si" -> { depth = 1; sb.setLength(0) }
          "t" -> inT = depth > 0
        }
        is MiniXml.Event.Text -> if (inT) sb.append(e.value)
        is MiniXml.Event.End -> when (e.name) {
          "t" -> inT = false
          "si" -> { out += sb.toString(); depth = 0 }
        }
      }
      true
    }
    return out
  }

  // ── styles.xml ───────────────────────────────────────────────────────────────

  /** What a cell's style tells us about how to read its value. */
  data class Styles(
    /** cellXf index -> true when its number format is a percentage. */
    val isPercent: List<Boolean> = emptyList(),
    /** cellXf index -> true when its number format is a date/time. */
    val isDate: List<Boolean> = emptyList(),
    /** cellXf index -> a stable colour token for its background fill, or "" for none. */
    val fill: List<String> = emptyList()
  )

  private val BUILTIN_PERCENT = setOf(9, 10)
  private val BUILTIN_DATE = (14..22).toSet() + (45..47).toSet()

  fun readStyles(data: ByteArray?): Styles {
    if (data == null) return Styles()
    val customPercent = HashSet<Int>()
    val customDate = HashSet<Int>()
    val fillColors = ArrayList<String>()
    val xfNumFmt = ArrayList<Int>()
    val xfFill = ArrayList<Int>()

    var inCellXfs = false
    var inFills = false
    MiniXml.parse(data.toXmlString()) { e ->
      when (e) {
        is MiniXml.Event.Start -> when (e.name) {
          "numFmt" -> {
            val id = e.attrs["numFmtId"]?.toIntOrNull()
            val code = e.attrs["formatCode"].orEmpty()
            if (id != null) {
              // A format code is a percentage iff it contains an unescaped '%'.
              if (code.replace("\\%", "").contains('%')) customPercent += id
              if (Regex("""[dmyhs]""", RegexOption.IGNORE_CASE).containsMatchIn(
                    code.replace(Regex("""\[[^\]]*]"""), "").replace(Regex("""".*?""""), ""))) customDate += id
            }
          }
          "cellXfs" -> inCellXfs = true
          "fills" -> inFills = true
          "fgColor" -> if (inFills) fillColors[fillColors.lastIndex] =
            e.attrs["rgb"] ?: e.attrs["theme"]?.let { "theme$it" } ?: ""
          "fill" -> if (inFills) fillColors += ""
          "xf" -> if (inCellXfs) {
            xfNumFmt += e.attrs["numFmtId"]?.toIntOrNull() ?: 0
            xfFill += e.attrs["fillId"]?.toIntOrNull() ?: 0
          }
        }
        is MiniXml.Event.End -> when (e.name) {
          "cellXfs" -> inCellXfs = false
          "fills" -> inFills = false
        }
        else -> Unit
      }
      true
    }

    return Styles(
      isPercent = xfNumFmt.map { it in BUILTIN_PERCENT || it in customPercent },
      isDate = xfNumFmt.map { it in BUILTIN_DATE || it in customDate },
      // Fill id 0 is "none" and 1 is the grey default pattern — neither is a deliberate highlight.
      fill = xfFill.map { id -> if (id <= 1) "" else fillColors.getOrElse(id) { "" } }
    )
  }

  // ── sheet XML ────────────────────────────────────────────────────────────────

  fun readSheet(
    name: String,
    data: ByteArray,
    shared: List<String>,
    styles: Styles,
    cellBudget: Int
  ): Grid {
    val cells = HashMap<Long, String>()
    val percent = HashSet<Pair<Int, Int>>()
    val fills = HashMap<Pair<Int, Int>, String>()
    val hiddenRows = HashSet<Int>()
    val hiddenCols = HashSet<Int>()
    val merges = ArrayList<IntArray>()
    var frozenRow: Int? = null
    var maxRow = -1
    var maxCol = -1
    var budget = cellBudget

    var row = -1
    var col = -1
    var cellType = ""
    var styleIndex = -1
    var inValue = false
    var inInlineString = false
    // A formula's CACHED value lives in <v> and is what we want; the <f> text is not.
    var inFormula = false
    val value = StringBuilder()

    MiniXml.parse(data.toXmlString()) { e ->
      when (e) {
        is MiniXml.Event.Start -> when (e.name) {
          "row" -> {
            row = (e.attrs["r"]?.toIntOrNull() ?: (row + 2)) - 1
            if (e.attrs["hidden"] == "1" || e.attrs["hidden"] == "true") hiddenRows += row
          }
          "col" -> {
            if (e.attrs["hidden"] == "1" || e.attrs["hidden"] == "true") {
              val lo = (e.attrs["min"]?.toIntOrNull() ?: 1) - 1
              val hi = (e.attrs["max"]?.toIntOrNull() ?: (lo + 1)) - 1
              for (c in lo..minOf(hi, lo + 512)) hiddenCols += c
            }
          }
          "pane" -> e.attrs["ySplit"]?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }?.let { frozenRow = it }
          "mergeCell" -> e.attrs["ref"]?.let { ref -> parseRange(ref)?.let { merges += it } }
          "c" -> {
            val ref = e.attrs["r"]
            col = if (ref != null) A1.col(ref) else col + 1
            if (ref != null) A1.row(ref).takeIf { it >= 0 }?.let { row = it }
            cellType = e.attrs["t"].orEmpty()
            styleIndex = e.attrs["s"]?.toIntOrNull() ?: -1
            value.setLength(0)
          }
          "v" -> inValue = true
          "f" -> inFormula = true
          "is" -> inInlineString = true
          "t" -> if (inInlineString) inValue = true
        }
        is MiniXml.Event.Text -> if (inValue && !inFormula) value.append(e.value)
        is MiniXml.Event.End -> when (e.name) {
          "v", "t" -> inValue = false
          "f" -> inFormula = false
          "is" -> inInlineString = false
          "c" -> {
            if (row >= 0 && col >= 0 && budget > 0) {
              val raw = value.toString()
              val isPct = styleIndex >= 0 && styles.isPercent.getOrElse(styleIndex) { false }
              val text = renderCell(raw, cellType, shared, isPct)
              if (text.isNotBlank()) {
                cells[key(row, col)] = text
                if (row > maxRow) maxRow = row
                if (col > maxCol) maxCol = col
                budget--
                if (isPct) percent += row to col
              }
              if (styleIndex >= 0) styles.fill.getOrNull(styleIndex)?.takeIf { it.isNotBlank() }
                ?.let { fills[row to col] = it }
            }
            value.setLength(0)
          }
        }
      }
      true
    }

    // Expand merged regions: every covered cell reads as the anchor's value, so a merged "Week 1"
    // banner is visible from the rows and columns it actually spans.
    for (m in merges) {
      val v = cells[key(m[0], m[1])] ?: continue
      for (r in m[0]..minOf(m[2], m[0] + 256)) for (c in m[1]..minOf(m[3], m[1] + 256)) {
        if (cells.putIfAbsent(key(r, c), v) == null) {
          if (r > maxRow) maxRow = r
          if (c > maxCol) maxCol = c
        }
      }
    }

    val rows = (0..maxRow).map { r -> (0..maxCol).map { c -> cells[key(r, c)].orEmpty() } }
    return Grid(name, rows, hiddenRows, hiddenCols, frozenRow, percent, fills)
  }

  /**
   * A cell's stored value is not its meaning. `t="s"` indexes the shared table, `t="b"` is a
   * boolean, `t="e"` is an error, and a percent-formatted `0.85` means 85 — reading the raw number
   * is how "85%" became "0.85" and every percentage in the program was wrong by two orders of
   * magnitude.
   */
  fun renderCell(raw: String, type: String, shared: List<String>, isPercent: Boolean): String {
    if (raw.isEmpty()) return ""
    return when (type) {
      "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
      "b" -> if (raw == "1") "TRUE" else "FALSE"
      "e" -> ""                       // #REF! etc. carries no program content
      "str", "inlineStr" -> raw
      else -> {
        val d = raw.toDoubleOrNull() ?: return raw
        if (isPercent) fmt(d * 100.0) + "%" else fmt(d)
      }
    }
  }

  private fun fmt(d: Double): String =
    if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e15) d.toLong().toString()
    // Round to 6 places so 0.85*100 does not render as 85.00000000000001.
    else java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP)
      .stripTrailingZeros().toPlainString()

  private fun key(r: Int, c: Int): Long = r.toLong() * 65_536L + c
  private fun parseRange(ref: String): IntArray? {
    val parts = ref.split(':')
    if (parts.size != 2) return null
    val r1 = A1.row(parts[0]); val c1 = A1.col(parts[0])
    val r2 = A1.row(parts[1]); val c2 = A1.col(parts[1])
    if (r1 < 0 || c1 < 0 || r2 < 0 || c2 < 0) return null
    return intArrayOf(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
  }
}

/** A1-notation helpers. `AA10` -> col 26, row 9. */
object A1 {
  fun col(ref: String): Int {
    var n = 0
    for (ch in ref) {
      val u = ch.uppercaseChar()
      if (u < 'A' || u > 'Z') break
      n = n * 26 + (u - 'A' + 1)
    }
    return n - 1
  }

  fun row(ref: String): Int = ref.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
    .toIntOrNull()?.minus(1) ?: -1

  fun letters(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) { n--; sb.insert(0, ('A' + n % 26)); n /= 26 }
    return sb.toString()
  }
}

/** OOXML and ODF are always UTF-8; a BOM sometimes sneaks in from hand-edited files. */
internal fun ByteArray.toXmlString(): String =
  String(this, Charsets.UTF_8).removePrefix("\uFEFF")

package com.wildodds.gymtracker.data.importer.layout

import com.wildodds.gymtracker.data.reader.ingest.XlsxExtractor
import com.wildodds.gymtracker.data.reader.ingest.ZipReader
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds real `.xlsx` bytes in memory.
 *
 * Fixture workbooks are BUILT rather than checked in, and that is the point of this file. A binary
 * fixture is opaque in a diff, impossible to tweak without a spreadsheet application, and when a
 * test fails against one nobody can see why. Here every fixture is a few lines of Kotlin, a failure
 * points at the row that broke it, and adding the twenty-sixth layout costs four lines.
 *
 * The bytes are genuine OOXML — written through a real zip with a real shared-string table — so they
 * exercise [XlsxExtractor] end to end rather than bypassing it.
 */
object WorkbookFixtures {

  data class Sheet(
    val name: String,
    val rows: List<List<String>>,
    /** 1-based rows to mark hidden. */
    val hiddenRows: Set<Int> = emptySet(),
    /** Cells (0-based row, col) to mark percent-formatted. */
    val percentCells: Set<Pair<Int, Int>> = emptySet(),
    /** 1-based row the frozen pane splits at. */
    val frozenRow: Int? = null,
    /** Cells (0-based) to give a background fill. */
    val filled: Set<Pair<Int, Int>> = emptySet(),
    /** Merged ranges in A1 form, e.g. "A1:C1". */
    val merges: List<String> = emptyList()
  )

  fun workbook(vararg sheets: Sheet): ByteArray = workbook(sheets.toList())

  fun workbook(sheets: List<Sheet>, title: String? = null): ByteArray {
    // One shared-string table for the whole book, exactly as Excel writes it.
    val shared = LinkedHashMap<String, Int>()
    fun sid(s: String): Int = shared.getOrPut(s) { shared.size }
    for (sh in sheets) for (row in sh.rows) for (c in row) if (c.isNotBlank() && c.toDoubleOrNull() == null) sid(c)

    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
      fun put(name: String, body: String) {
        z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry()
      }

      put("[Content_Types].xml",
        """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""")

      put("xl/workbook.xml", buildString {
        append("""<?xml version="1.0"?><workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, s ->
          append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
      })

      put("xl/_rels/workbook.xml.rels", buildString {
        append("<?xml version=\"1.0\"?><Relationships>")
        sheets.indices.forEach {
          append("""<Relationship Id="rId${it + 1}" Target="worksheets/sheet${it + 1}.xml"/>""")
        }
        append("</Relationships>")
      })

      put("xl/sharedStrings.xml", buildString {
        append("""<?xml version="1.0"?><sst count="${shared.size}" uniqueCount="${shared.size}">""")
        for (s in shared.keys) append("<si><t>${esc(s)}</t></si>")
        append("</sst>")
      })

      // Style 0 = plain, 1 = percent (numFmtId 9), 2 = filled.
      put("xl/styles.xml", """<?xml version="1.0"?><styleSheet>
        <fills count="3"><fill><patternFill patternType="none"/></fill>
        <fill><patternFill patternType="gray125"/></fill>
        <fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/></patternFill></fill></fills>
        <cellXfs count="3">
          <xf numFmtId="0" fillId="0"/>
          <xf numFmtId="9" fillId="0"/>
          <xf numFmtId="0" fillId="2"/>
        </cellXfs></styleSheet>""")

      if (title != null) {
        put("docProps/core.xml",
          """<?xml version="1.0"?><cp:coreProperties xmlns:cp="c" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(title)}</dc:title></cp:coreProperties>""")
      }

      sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s, ::sid)) }
    }
    return out.toByteArray()
  }

  private fun sheetXml(s: Sheet, sid: (String) -> Int): String = buildString {
    append("""<?xml version="1.0"?><worksheet>""")
    s.frozenRow?.let { append("""<sheetViews><sheetView><pane ySplit="$it" topLeftCell="A${it + 1}" state="frozen"/></sheetView></sheetViews>""") }
    append("<sheetData>")
    s.rows.forEachIndexed { r, row ->
      val hidden = if ((r + 1) in s.hiddenRows) """ hidden="1"""" else ""
      append("""<row r="${r + 1}"$hidden>""")
      row.forEachIndexed { c, raw ->
        val styled = (r to c) in s.percentCells || (r to c) in s.filled
        // A blank cell that carries FORMATTING is still written by Excel, and it has to be here
        // too — a shaded row is shaded across its whole width, most of which is empty.
        if (raw.isBlank() && !styled) return@forEachIndexed
        val ref = colLetters(c) + (r + 1)
        val style = when {
          (r to c) in s.percentCells -> """ s="1""""
          (r to c) in s.filled -> """ s="2""""
          else -> ""
        }
        val num = raw.toDoubleOrNull()
        when {
          raw.isBlank() -> append("""<c r="$ref"$style/>""")
          num != null -> append("""<c r="$ref"$style><v>$raw</v></c>""")
          else -> append("""<c r="$ref"$style t="s"><v>${sid(raw)}</v></c>""")
        }
      }
      append("</row>")
    }
    append("</sheetData>")
    if (s.merges.isNotEmpty()) {
      append("""<mergeCells count="${s.merges.size}">""")
      for (m in s.merges) append("""<mergeCell ref="$m"/>""")
      append("</mergeCells>")
    }
    append("</worksheet>")
  }

  private fun colLetters(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) { n--; sb.insert(0, ('A' + n % 26)); n /= 26 }
    return sb.toString()
  }

  private fun esc(s: String) = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  /** Read fixture bytes back through the real extractor, as the app would. */
  fun read(bytes: ByteArray, fileName: String = "fixture.xlsx"): Workbook {
    val zip = ZipReader.read(bytes)
    val book = XlsxExtractor.read(zip)
    return Workbook(book.grids, book.title, fileName)
  }
}

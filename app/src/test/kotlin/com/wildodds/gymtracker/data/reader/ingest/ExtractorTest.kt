package com.wildodds.gymtracker.data.reader.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Per-family extractor behaviour. Every fixture is built in code rather than checked in as a binary,
 * so a failure points at the byte that broke rather than at an opaque file, and the whole suite runs
 * on a bare JVM in milliseconds.
 */
class ExtractorTest {

  private fun input(bytes: ByteArray, name: String = "", kind: SourceKind) =
    ExtractInput(bytes, name, kind = kind)

  private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
      for ((name, body) in entries) {
        z.putNextEntry(ZipEntry(name))
        z.write(body.toByteArray(Charsets.UTF_8))
        z.closeEntry()
      }
    }
    return out.toByteArray()
  }

  // ── text ─────────────────────────────────────────────────────────────────────

  @Test fun `latin-1 text is decoded without replacement characters`() {
    // "Übung: Kniebeuge 3x8" in windows-1252 — a Windows-exported coach file.
    val bytes = "Übung: Kniebeuge 3x8".toByteArray(Charsets.ISO_8859_1)
    val doc = PlainTextExtractor.extract(input(bytes, "u.txt", SourceKind.PLAIN_TEXT))
    assertTrue("no replacement characters", '�' !in doc.text)
    assertTrue(doc.text.contains("Übung"))
    assertTrue("the fallback charset is surfaced", doc.warnings.any { it.message.contains("rather than UTF-8") })
  }

  @Test fun `a UTF-16 file with a BOM is decoded`() {
    val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Bench 3x8".toByteArray(Charsets.UTF_16LE)
    val doc = PlainTextExtractor.extract(input(bytes, "n.txt", SourceKind.PLAIN_TEXT))
    assertEquals("Bench 3x8", doc.text.trim())
  }

  @Test fun `WhatsApp timestamps are stripped so the lines can be parsed`() {
    val chat = """
      [21/03/2026, 19:04] Coach: Bench Press 3x8
      [21/03/2026, 19:04] Coach: Barbell Row 3x10
    """.trimIndent()
    val doc = PlainTextExtractor.extract(input(chat.toByteArray(), "chat.txt", SourceKind.PLAIN_TEXT))
    assertEquals(listOf("Bench Press 3x8", "Barbell Row 3x10"), doc.text.lines().map { it.trim() })
  }

  @Test fun `email quote markers are stripped`() {
    val doc = PlainTextExtractor.extract(
      input("> > Bench Press 3x8".toByteArray(), "m.txt", SourceKind.PLAIN_TEXT))
    assertEquals("Bench Press 3x8", doc.text.trim())
  }

  // ── delimited ────────────────────────────────────────────────────────────────

  @Test fun `a quoted newline inside a cell does not split the row`() {
    // The old splitter turned this into two garbage rows, silently.
    val csv = "Exercise,Notes\nBench Press,\"keep the elbows tucked\nand pause on the chest\"\nRow,pull to the hip\n"
    val doc = DelimitedExtractor.extract(input(csv.toByteArray(), "p.csv", SourceKind.DELIMITED))
    val grid = doc.tables.single()
    assertEquals(3, grid.rowCount)
    assertTrue(grid.cell(1, 1).contains("pause on the chest"))
  }

  @Test fun `doubled quotes are an escaped quote`() {
    val csv = "Exercise,Notes\nPin Press,\"stop at the \"\"sticking point\"\"\"\nRow,x\n"
    val grid = DelimitedExtractor.extract(input(csv.toByteArray(), "p.csv", SourceKind.DELIMITED)).tables.single()
    assertEquals("""stop at the "sticking point"""", grid.cell(1, 1))
  }

  @Test fun `a quote inside an unquoted field is a literal`() {
    val csv = "Exercise,Range\nDeficit Deadlift,2\" deficit\nRow,full\n"
    val grid = DelimitedExtractor.extract(input(csv.toByteArray(), "p.csv", SourceKind.DELIMITED)).tables.single()
    assertEquals("""2" deficit""", grid.cell(1, 1))
  }

  @Test fun `ragged rows are kept and flagged rather than dropped`() {
    val csv = "Exercise,Sets,Reps\nBench,3,8\nRow,3\nSquat,5,5,extra\n"
    val doc = DelimitedExtractor.extract(input(csv.toByteArray(), "p.csv", SourceKind.DELIMITED))
    assertEquals(4, doc.tables.single().rowCount)
    assertTrue(doc.warnings.any { it.message.contains("columns") })
  }

  // ── docx ─────────────────────────────────────────────────────────────────────

  @Test fun `a docx table becomes both a grid and tab-separated text`() {
    val body = """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>
      <w:p><w:r><w:t>Week 1</w:t></w:r></w:p>
      <w:tbl>
        <w:tr><w:tc><w:p><w:r><w:t>Exercise</w:t></w:r></w:p></w:tc>
             <w:tc><w:p><w:r><w:t>Sets</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:p><w:r><w:t>Bench </w:t></w:r><w:r><w:t>Press</w:t></w:r></w:p></w:tc>
             <w:tc><w:p><w:r><w:t>3x8</w:t></w:r></w:p></w:tc></w:tr>
      </w:tbl></w:body></w:document>"""
    val bytes = zip("word/document.xml" to body)
    val doc = DocxExtractor.extract(input(bytes, "p.docx", SourceKind.DOCX))
    val grid = doc.tables.single()
    // Runs split mid-word must concatenate with NO separator.
    assertEquals("Bench Press", grid.cell(1, 0))
    assertEquals("3x8", grid.cell(1, 1))
    assertTrue("the table also reaches the text path", doc.text.contains("Bench Press\t3x8"))
    assertTrue(doc.text.contains("Week 1"))
  }

  @Test fun `docx tabs and breaks are separators, not nothing`() {
    val body = """<w:document xmlns:w="x"><w:body><w:p>
      <w:r><w:t>3x8</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>80kg</w:t></w:r>
    </w:p></w:body></w:document>"""
    val doc = DocxExtractor.extract(input(zip("word/document.xml" to body), "p.docx", SourceKind.DOCX))
    assertTrue("without the tab this would read 3x880kg", doc.text.contains("3x8\t80kg"))
  }

  // ── ods ──────────────────────────────────────────────────────────────────────

  @Test fun `ods repeated columns are expanded so cells line up with their headers`() {
    val content = """<office:document-content xmlns:office="o" xmlns:table="t" xmlns:text="x">
      <office:body><office:spreadsheet>
      <table:table table:name="Week 1">
        <table:table-row>
          <table:table-cell><text:p>Exercise</text:p></table:table-cell>
          <table:table-cell table:number-columns-repeated="2"/>
          <table:table-cell><text:p>Reps</text:p></table:table-cell>
        </table:table-row>
        <table:table-row>
          <table:table-cell><text:p>Bench</text:p></table:table-cell>
          <table:table-cell table:number-columns-repeated="2"/>
          <table:table-cell><text:p>8</text:p></table:table-cell>
        </table:table-row>
      </table:table></office:spreadsheet></office:body></office:document-content>"""
    val bytes = zip("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to content)
    val doc = OpenDocumentExtractor.extract(input(bytes, "p.ods", SourceKind.ODS))
    val g = doc.tables.single()
    assertEquals("Week 1", g.name)
    // Without honouring the repeat, "Reps" would sit in column 1 and every value would misalign.
    assertEquals("Reps", g.cell(0, 3))
    assertEquals("8", g.cell(1, 3))
  }

  @Test fun `ods percentage cells read as percentages`() {
    val content = """<office:document-content xmlns:office="o" xmlns:table="t" xmlns:text="x">
      <office:body><office:spreadsheet><table:table table:name="S">
      <table:table-row><table:table-cell office:value-type="percentage" office:value="0.85">
      <text:p>85%</text:p></table:table-cell></table:table-row>
      </table:table></office:spreadsheet></office:body></office:document-content>"""
    val bytes = zip("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to content)
    val g = OpenDocumentExtractor.extract(input(bytes, "p.ods", SourceKind.ODS)).tables.single()
    assertEquals("85%", g.cell(0, 0))
  }

  // ── rtf ──────────────────────────────────────────────────────────────────────

  @Test fun `rtf keeps par and cell as structure`() {
    val rtf = """{\rtf1\ansi{\fonttbl{\f0 Arial;}}Week 1\par Bench Press\cell 3x8\cell\row Row\cell 3x10\cell\row}"""
    val doc = RtfExtractor.extract(input(rtf.toByteArray(Charsets.ISO_8859_1), "p.rtf", SourceKind.RTF))
    val lines = doc.text.lines().filter { it.isNotBlank() }
    assertEquals("Week 1", lines[0])
    assertTrue(lines[1].startsWith("Bench Press\t3x8"))
    assertTrue("the font table is metadata, not content", "Arial" !in doc.text)
  }

  @Test fun `rtf hex and unicode escapes decode`() {
    val rtf = """{\rtf1 Kniebeuge \'e9 \u233 ?\par}"""
    val doc = RtfExtractor.extract(input(rtf.toByteArray(Charsets.ISO_8859_1), "p.rtf", SourceKind.RTF))
    assertTrue(doc.text.contains("é é"))
  }

  // ── html ─────────────────────────────────────────────────────────────────────

  @Test fun `html tables become grids and blocks become lines`() {
    val html = """<html><head><title>Upper Lower</title><style>.x{color:red}</style></head><body>
      <h1>Week 1</h1><p>Push day</p>
      <table><tr><th>Exercise</th><th>Sets</th></tr><tr><td>Bench Press</td><td>3x8</td></tr></table>
      <script>alert(1)</script></body></html>"""
    val doc = HtmlExtractor.extract(input(html.toByteArray(), "p.html", SourceKind.HTML))
    assertEquals("Upper Lower", doc.title)
    assertEquals("Bench Press", doc.tables.single().cell(1, 0))
    assertTrue("blocks must become newlines", doc.text.lines().contains("Week 1"))
    assertTrue("script and style are not text", "alert" !in doc.text && "color:red" !in doc.text)
  }

  @Test fun `quoted-printable mhtml is decoded`() {
    assertEquals("3x8 =/= 4x8", HtmlExtractor.decodeQuotedPrintable("3x8 =3D/=3D 4x8"))
    assertEquals("BenchPress", HtmlExtractor.decodeQuotedPrintable("Bench=\nPress"))
  }

  // ── structured ───────────────────────────────────────────────────────────────

  @Test fun `an unknown JSON export still becomes a readable grid`() {
    val json = """{"program":"Block A","exercises":[
      {"exercise":"Bench Press","sets":3,"reps":8},
      {"exercise":"Row","sets":3,"reps":10}]}"""
    val doc = StructuredExtractor.extract(input(json.toByteArray(), "e.json", SourceKind.JSON))
    val g = doc.tables.first()
    assertEquals(listOf("exercise", "sets", "reps"), g.rows.first())
    assertEquals("Bench Press", g.cell(1, 0))
  }

  @Test fun `xml siblings with the same shape become a grid`() {
    val xml = """<program><day><exercise>Bench</exercise><sets>3</sets></day>
      <day><exercise>Row</exercise><sets>3</sets></day></program>"""
    val doc = StructuredExtractor.extract(input(xml.toByteArray(), "p.xml", SourceKind.XML))
    assertNotNull(doc.tables.firstOrNull { it.rowCount == 3 })
  }

  // ── archives ─────────────────────────────────────────────────────────────────

  @Test fun `a zip picks the most program-like entry and says what it skipped`() {
    val bytes = zip(
      "README.txt" to "how to use this",
      "block.csv" to "Exercise,Sets,Reps\nBench,3,8\nRow,3,10\n"
    )
    val doc = ArchiveExtractor.extract(input(bytes, "block.zip", SourceKind.ZIP))
    assertEquals("Bench", doc.tables.single().cell(1, 0))
    assertTrue(doc.warnings.any { it.message.contains("block.csv") })
  }

  @Test fun `zip slip entries are dropped rather than trusted`() {
    assertEquals(null, ZipReader.safeName("../../databases/gym_tracker.db"))
    assertEquals(null, ZipReader.safeName("/etc/passwd"))
    assertEquals(null, ZipReader.safeName("""..\..\windows\system32"""))
    assertEquals("xl/workbook.xml", ZipReader.safeName("""xl\workbook.xml"""))
  }

  @Test fun `a zip bomb is refused before it is decompressed`() {
    // 8 MB of zeroes compresses to almost nothing; a 1 MB budget must stop it.
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
      z.putNextEntry(ZipEntry("bomb.txt"))
      z.write(ByteArray(8 * 1024 * 1024))
      z.closeEntry()
    }
    val e = runCatching {
      ZipReader.read(out.toByteArray(), IngestLimits(maxDecompressedBytes = 1024 * 1024))
    }.exceptionOrNull()
    assertTrue("expected TooLarge, got $e", e is IngestError.TooLarge)
  }
}

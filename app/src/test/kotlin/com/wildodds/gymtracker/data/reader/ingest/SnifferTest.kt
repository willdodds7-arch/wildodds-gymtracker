package com.wildodds.gymtracker.data.reader.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sniffer decides everything downstream, so its two jobs are pinned here: identify by content,
 * and never let a filename override the bytes. Users rename things constantly and share sheets get
 * delivered as `.txt` — the old extension-only branch is exactly why a renamed xlsx read back as
 * mojibake and "imported" as a one-line program.
 */
class SnifferTest {

  private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

  @Test fun `a PDF is a PDF even when it is called txt`() {
    assertEquals(SourceKind.PDF_DIGITAL, Sniffer.sniff(bytes("%PDF-1.7\nstuff"), "program.txt"))
  }

  @Test fun `a zip is identified by its entries, not its extension`() {
    val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    assertEquals(SourceKind.XLSX, Sniffer.sniff(zipBytes, "coach.bin", zipEntryNames = listOf("xl/workbook.xml")))
    assertEquals(SourceKind.DOCX, Sniffer.sniff(zipBytes, "coach.xlsx", zipEntryNames = listOf("word/document.xml")))
    assertEquals(SourceKind.ODS, Sniffer.sniff(zipBytes, "x", zipEntryNames = listOf("mimetype", "content.xml", "styles-spreadsheet")))
  }

  @Test fun `an OLE2 header is a legacy xls, whatever it claims to be`() {
    val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)
    assertEquals(SourceKind.XLS, Sniffer.sniff(ole, "program.xlsx"))
  }

  @Test fun `images are recognised from their magic bytes`() {
    assertEquals(SourceKind.IMAGE, Sniffer.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "a"))
    assertEquals(SourceKind.IMAGE, Sniffer.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0), "a"))
    val heic = "0000001C".chunked(2).map { it.toInt(16).toByte() }.toByteArray() +
      "ftypheic".toByteArray(Charsets.US_ASCII)
    assertEquals(SourceKind.IMAGE, Sniffer.sniff(heic, "photo.jpg"))
  }

  @Test fun `a pasted program is text, not CSV, when its commas are per-set reps`() {
    // The bug this pins: "Front Squat 6, 8, 10" used to trip comma counting and the whole program
    // was read as a three-column table with the exercise name as column one.
    val text = """
      Day 1 - Full
      Front Squat 6, 8, 10 @RPE8
      Bench Press 10, 8, 6
      RDL 3x6
    """.trimIndent()
    assertNull("per-set commas must not look like a delimiter", Sniffer.inferDelimiter(text))
    assertEquals(SourceKind.PLAIN_TEXT, Sniffer.sniff(bytes(text), "program.txt"))
  }

  @Test fun `a real CSV is detected by consistent column counts`() {
    val csv = "Exercise,Sets,Reps\nBench Press,3,8\nRow,3,10\nSquat,5,5\n"
    assertEquals(',', Sniffer.inferDelimiter(csv))
    assertEquals(SourceKind.DELIMITED, Sniffer.sniff(bytes(csv), "whatever.dat"))
  }

  @Test fun `a European semicolon CSV is detected`() {
    val csv = "Ubung;Satze;Wdh\nBankdrucken;3;8\nRudern;3;10\nKniebeuge;5;5\n"
    assertEquals(';', Sniffer.inferDelimiter(csv))
  }

  @Test fun `a tab-separated file beats a comma that appears inside its cells`() {
    val tsv = "Exercise\tNotes\nBench Press\tkeep elbows in, chin tucked\nRow\tsqueeze, pause\n"
    assertEquals('\t', Sniffer.inferDelimiter(tsv))
  }

  @Test fun `markdown is markdown`() {
    assertEquals(SourceKind.MARKDOWN, Sniffer.sniff(bytes("## Day 1\n\nBench Press 3x8\n"), "x"))
  }

  @Test fun `html is html and not xml`() {
    assertEquals(SourceKind.HTML, Sniffer.sniff(bytes("<!DOCTYPE html><html><body>hi</body></html>"), "x"))
  }

  @Test fun `json needs more than a leading brace`() {
    assertEquals(SourceKind.JSON, Sniffer.sniff(bytes("""{"name":"Block","weeks":4}"""), "x"))
    // A pasted program that happens to start with a brace is not JSON.
    assertTrue(Sniffer.sniff(bytes("{ this is a note }\nBench 3x8\nRow 3x10"), "x") != SourceKind.JSON)
  }

  @Test fun `a program with Day 1 colon headers is not mistaken for YAML`() {
    val text = "Day 1:\nBench Press 3x8\nDay 2:\nSquat 5x5\n"
    assertTrue("set notation rules out YAML", Sniffer.sniff(bytes(text), "x") != SourceKind.YAML)
  }

  @Test fun `an empty file is UNKNOWN rather than an empty program`() {
    assertEquals(SourceKind.UNKNOWN, Sniffer.sniff(ByteArray(0), "empty.csv"))
  }
}

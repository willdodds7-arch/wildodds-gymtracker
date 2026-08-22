package com.wildodds.gymtracker.data.reader.ingest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Every binary extractor, against truncated and bit-flipped input.
 *
 * The contract is narrow and absolute: an extractor may throw [IngestError] and nothing else. Not
 * `IndexOutOfBoundsException` from a short buffer, not `OutOfMemoryError` from a length field that
 * says four billion, not `StackOverflowError` from a self-referential structure — and never a crash
 * that takes the app down while the user is holding a file they were told to try.
 *
 * The seed is fixed, so a failure is reproducible rather than a flake someone reruns until it
 * passes. Every iteration derives its mutation from the iteration index, so the failing case is
 * identified exactly by the index in the assertion message.
 */
class ExtractorFuzzTest {

  private val limits = IngestLimits(
    maxDecompressedBytes = 8L * 1024 * 1024,
    maxCells = 50_000,
    maxTextChars = 1 shl 20
  )

  private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
      for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b.toByteArray()); z.closeEntry() }
    }
    return out.toByteArray()
  }

  private val xlsx = zipOf(
    "xl/workbook.xml" to """<workbook xmlns:r="r"><sheets><sheet name="Week 1" r:id="rId1"/></sheets></workbook>""",
    "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
    "xl/sharedStrings.xml" to """<sst><si><t>Bench Press</t></si><si><t>Squat</t></si></sst>""",
    "xl/worksheets/sheet1.xml" to
      """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>3</v></c></row>
         <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>5</v></c></row></sheetData></worksheet>"""
  )

  private val docx = zipOf("word/document.xml" to
    """<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>Bench Press 3x8</w:t></w:r></w:p></w:body></w:document>""")

  private val ods = zipOf(
    "mimetype" to "application/vnd.oasis.opendocument.spreadsheet",
    "content.xml" to """<office:document-content xmlns:office="o" xmlns:table="t" xmlns:text="x">
      <office:body><office:spreadsheet><table:table table:name="S">
      <table:table-row><table:table-cell><text:p>Bench</text:p></table:table-cell></table:table-row>
      </table:table></office:spreadsheet></office:body></office:document-content>""")

  private val rtf = """{\rtf1\ansi Bench Press 3x8\par Squat 5x5\par}""".toByteArray(Charsets.ISO_8859_1)

  private val html = "<html><body><table><tr><td>Bench</td><td>3x8</td></tr></table></body></html>".toByteArray()

  private val csv = "Exercise,Sets,Reps\nBench,3,8\nSquat,5,5\n".toByteArray()

  private val json = """{"exercises":[{"exercise":"Bench","sets":3},{"exercise":"Squat","sets":5}]}""".toByteArray()

  /** A minimal OLE2 header so the CFB reader gets past the signature and into the interesting part. */
  private val xls = ByteArray(2048).also { b ->
    val sig = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
                          0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    sig.copyInto(b)
    b[30] = 9   // sector shift => 512-byte sectors
    b[48] = 1   // directory start sector
  }

  private val corpus: List<Triple<String, ByteArray, Pair<Extractor, SourceKind>>> = listOf(
    Triple("xlsx", xlsx, XlsxExtractor as Extractor to SourceKind.XLSX),
    Triple("docx", docx, DocxExtractor as Extractor to SourceKind.DOCX),
    Triple("ods", ods, OpenDocumentExtractor as Extractor to SourceKind.ODS),
    Triple("rtf", rtf, RtfExtractor as Extractor to SourceKind.RTF),
    Triple("html", html, HtmlExtractor as Extractor to SourceKind.HTML),
    Triple("csv", csv, DelimitedExtractor as Extractor to SourceKind.DELIMITED),
    Triple("json", json, StructuredExtractor as Extractor to SourceKind.JSON),
    Triple("zip", zipOf("a.csv" to "Exercise,Sets\nBench,3\n"), ArchiveExtractor as Extractor to SourceKind.ZIP),
    Triple("xls", xls, XlsExtractor as Extractor to SourceKind.XLS)
  )

  @Test fun `the clean fixtures all extract`() {
    for ((name, bytes, ek) in corpus) {
      val (extractor, kind) = ek
      val result = runCatching { extractor.extract(ExtractInput(bytes, "f.$name", kind = kind, limits = limits)) }
      // The .xls stub is a header with no workbook stream, so it legitimately refuses.
      if (name == "xls") {
        assertTrue("xls stub should refuse cleanly", result.exceptionOrNull() is IngestError)
      } else {
        assertTrue("$name should extract: ${result.exceptionOrNull()}", result.isSuccess)
      }
    }
  }

  @Test fun `truncation never produces anything but an IngestError`() {
    for ((name, bytes, ek) in corpus) {
      val (extractor, kind) = ek
      // Every length from 0 to the full file, sampled so the test stays fast but covers the shape.
      val cuts = generateSequence(0) { it + maxOf(1, bytes.size / 40) }.takeWhile { it <= bytes.size }
      for (cut in cuts) {
        check(name, "truncated to $cut", extractor, kind, bytes.copyOf(cut))
      }
    }
  }

  @Test fun `bit flips never produce anything but an IngestError`() {
    val rnd = Random(20260822)   // fixed seed: a failure here must be reproducible
    for ((name, bytes, ek) in corpus) {
      val (extractor, kind) = ek
      repeat(120) { i ->
        val copy = bytes.copyOf()
        if (copy.isNotEmpty()) {
          val at = rnd.nextInt(copy.size)
          copy[at] = (copy[at].toInt() xor (1 shl rnd.nextInt(8))).toByte()
        }
        check(name, "bit flip #$i", extractor, kind, copy)
      }
    }
  }

  @Test fun `random noise never produces anything but an IngestError`() {
    val rnd = Random(7)
    for ((name, _, ek) in corpus) {
      val (extractor, kind) = ek
      repeat(30) { i ->
        val noise = ByteArray(rnd.nextInt(0, 4096)) { rnd.nextInt().toByte() }
        check(name, "noise #$i", extractor, kind, noise)
      }
    }
  }

  private fun check(name: String, what: String, extractor: Extractor, kind: SourceKind, bytes: ByteArray) {
    try {
      extractor.extract(ExtractInput(bytes, "f.$name", kind = kind, limits = limits))
    } catch (e: IngestError) {
      // The contract, honoured.
    } catch (e: Throwable) {
      throw AssertionError("$name $what threw ${e::class.java.name}: ${e.message}", e)
    }
  }
}

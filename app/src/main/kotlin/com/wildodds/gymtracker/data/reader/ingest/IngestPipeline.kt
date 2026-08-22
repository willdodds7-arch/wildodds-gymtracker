package com.wildodds.gymtracker.data.reader.ingest

/**
 * **sniff → extract → normalise → route.**
 *
 * The replacement for `AiFileExtractor`, whose name was a lie (it was always fully offline) and
 * whose logic was a three-branch `when` on the file extension. What changed:
 *
 *  - format is decided by [Sniffer] from magic bytes and structure, not by the extension;
 *  - every family has its own [Extractor] and they all return the same [ExtractedDocument], so text
 *    and grid formats flow into one downstream pipeline and every format benefits from every parser
 *    improvement;
 *  - a digital PDF with no selectable text is recognised as a SCAN and routed to OCR rather than
 *    returning an empty document;
 *  - the program is named from the document, not from the filename ([nameFor]);
 *  - failures are typed [IngestError]s with a message a user can act on.
 *
 * Pure: no `Context`, no `Uri`. The Android layer reads the bytes and supplies the two extractors
 * that need the platform. That keeps the corpus a plain JVM test.
 */
class IngestPipeline(
  private val registry: ExtractorRegistry = ExtractorRegistry.pureOnly(),
  private val limits: IngestLimits = IngestLimits.DEFAULT
) {

  data class Result(
    val document: ExtractedDocument,
    val kind: SourceKind,
    /** The program name, resolved by [nameFor]. Never a bare filename when anything better exists. */
    val suggestedName: String
  )

  fun ingest(
    bytes: ByteArray,
    fileName: String = "",
    mimeType: String = "",
    progress: IngestProgress = IngestProgress.NONE
  ): Result {
    if (bytes.isEmpty()) throw IngestError.Empty("That file is empty.")
    if (bytes.size > limits.maxFileBytes) {
      throw IngestError.TooLarge(
        "That file is ${bytes.size / (1024 * 1024)} MB — the limit is ${limits.maxFileBytes / (1024 * 1024)} MB.")
    }
    progress.report("Reading the file")

    // A zip has to be opened before it can be identified, so open it once and pass it along rather
    // than unzipping twice.
    val archive = if (isZip(bytes)) runCatching { ZipReader.read(bytes, limits, progress) }.getOrNull() else null
    val kind = Sniffer.sniff(bytes, fileName, mimeType, archive?.names().orEmpty())

    val input = ExtractInput(bytes, fileName, mimeType, kind, limits, progress, archive)
    val doc = extract(input)

    // A "digital" PDF with no text is a scan. This is a routing decision, not a failure.
    if (doc.sourceKind == SourceKind.PDF_SCANNED && doc.text.isBlank()) {
      progress.report("No selectable text — reading the page images")
      val ocr = registry.forKind(SourceKind.PDF_SCANNED)
        ?: throw IngestError.Unsupported("pdf-scan",
          "That PDF is a scan and this build can't read page images. Paste the text instead.")
      val scanned = ocr.extract(input.copy(kind = SourceKind.PDF_SCANNED))
      return finish(scanned, SourceKind.PDF_SCANNED, fileName)
    }

    return finish(doc, kind, fileName)
  }

  private fun extract(input: ExtractInput): ExtractedDocument {
    // The Apple bundle path needs the full registry so a PDF preview reaches the real PDF extractor.
    if (input.kind == SourceKind.APPLE_BUNDLE) return AppleBundleExtractor.extractWith(input, registry)
    val extractor = registry.forKind(input.kind)
      ?: throw IngestError.Unsupported(input.kind.name, unsupportedMessage(input))
    return extractor.extract(input)
  }

  private fun finish(doc: ExtractedDocument, kind: SourceKind, fileName: String): Result {
    if (doc.isEmpty) throw IngestError.Empty("Nothing readable was found in that file.")
    val normalised = doc.copy(text = TextDecoding.normalise(doc.text))
    return Result(normalised, kind, nameFor(normalised, fileName))
  }

  /**
   * Where a program's name comes from, in order:
   *   1. an explicit program title inside the document (a `# Heading`, a `<title>`, a title cell);
   *   2. the sheet or tab name, when it is meaningful (not "Sheet1");
   *   3. the document's metadata title;
   *   4. the filename.
   *
   * `program2.txt` producing a program called "program2" is a bug, not a behaviour, and it was the
   * old extractor's only strategy.
   */
  fun nameFor(doc: ExtractedDocument, fileName: String): String {
    titleFromText(doc.text)?.let { return it }
    doc.tables.firstOrNull { meaningfulSheetName(it.name) }?.let { return it.name.trim() }
    doc.title?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("Microsoft ") }?.let { return it }
    val stem = fileName.substringAfterLast('/').substringBeforeLast('.').trim()
    // Even from a filename, do a little work: "4_week_upper_lower" reads better as words.
    return stem.replace(Regex("""[_\-]+"""), " ").trim().ifBlank { "Imported Program" }
  }

  /** Generic tab names carry no information; a real one is the best title in a workbook. */
  private val GENERIC_SHEET = Regex("""(?i)^(sheet\d*|tab\d*|page\d*|table\d*|blad\d*|hoja\d*|foglio\d*|w\d+d\d+|data|export)$""")

  private fun meaningfulSheetName(name: String): Boolean {
    val n = name.trim()
    return n.length in 3..60 && !GENERIC_SHEET.matches(n) && n.any { it.isLetter() }
  }

  /**
   * A title inside the document: the first non-blank line, when it reads like a title rather than
   * like an exercise or a header. Deliberately strict — a wrong title is visible on every screen.
   */
  fun titleFromText(text: String): String? {
    for (raw in text.lineSequence().take(12)) {
      val line = raw.trim().trim('#', '*', '=', '-', '_', ' ').trim()
      if (line.isBlank()) continue
      if (line.length !in 4..80) continue
      // Not a title if it prescribes anything, or if it is a week/day header.
      if (Regex("""\d\s*[xX×]\s*\d""").containsMatchIn(line)) return null
      if (Regex("""(?i)^(week|day|session|phase|block|mon|tue|wed|thu|fri|sat|sun)\b""").containsMatchIn(line)) return null
      if (!line.any { it.isLetter() }) continue
      // A title has words; a lone exercise name is usually one or two.
      val words = line.split(Regex("""\s+""")).size
      return if (words in 2..12) line else null
    }
    return null
  }

  private fun unsupportedMessage(input: ExtractInput): String = when (input.kind) {
    SourceKind.IMAGE, SourceKind.PDF_SCANNED ->
      "Reading photos and scans needs the on-device text recogniser, which isn't available here."
    SourceKind.PDF_DIGITAL ->
      "Reading PDFs isn't available here."
    SourceKind.UNKNOWN ->
      "This app couldn't work out what kind of file that is. Try a .csv, .xlsx, .pdf, or paste the text."
    else ->
      "This app can't read ${input.kind.name.lowercase().replace('_', ' ')} files yet."
  }

  private fun isZip(b: ByteArray) = b.size >= 2 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
}

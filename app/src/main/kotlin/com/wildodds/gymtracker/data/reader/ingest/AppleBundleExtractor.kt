package com.wildodds.gymtracker.data.reader.ingest

/**
 * `.numbers` and `.pages`.
 *
 * Their real payload is IWA — Apple's Snappy-compressed protobuf — which is undocumented, unstable
 * across versions, and not something to reverse-engineer into a training app. But every bundle Apple
 * writes also contains a rendered **preview PDF** (or a QuickLook JPEG) for other apps to show, and
 * that preview is a perfectly readable copy of the document.
 *
 * So this extracts the preview and hands it to the PDF path — and **says so**, because a preview is
 * a render: it has the page's visual layout but not its cell structure, hidden rows or formulas.
 * Reporting that as a clean spreadsheet read would be a lie about what was understood.
 *
 * If the bundle has no preview (Numbers omits it when the user disables it), the honest answer is
 * that we cannot read it, and the message tells the user the one-step fix: export to xlsx or PDF.
 */
object AppleBundleExtractor : Extractor {

  override val handles = setOf(SourceKind.APPLE_BUNDLE)

  /** The preview locations Apple has used, best first. */
  private val PREVIEW_PATHS = listOf(
    "preview.pdf", "Preview.pdf", "QuickLook/Preview.pdf",
    "preview-micro.jpg", "preview-web.jpg", "QuickLook/Thumbnail.jpg", "preview.jpg"
  )

  /**
   * @param delegate resolves the extracted preview. In production this is the registry, so a PDF
   *   preview goes through the real PDF path; in tests it can be anything.
   */
  fun extractWith(input: ExtractInput, delegate: ExtractorRegistry): ExtractedDocument {
    input.cancelCheck()
    val zip = input.archive ?: ZipReader.read(input.bytes, input.limits, input.progress)

    val previewName = PREVIEW_PATHS.firstOrNull { zip[it] != null }
      ?: zip.names().firstOrNull { it.substringAfterLast('/').startsWith("preview", true) }
      ?: throw IngestError.Unsupported("apple-bundle",
        "This app can't read Apple ${kindWord(input.fileName)} files directly, and this one has no " +
        "preview inside it. In Numbers or Pages choose File ▸ Export To ▸ Excel or PDF, then import that.")

    val preview = zip[previewName]!!
    val kind = Sniffer.sniff(preview, previewName)
    val extractor = delegate.forKind(kind)
      ?: throw IngestError.Unsupported("apple-bundle-preview",
        "That Apple file's preview is in a format this app can't read. Export it to Excel or PDF instead.")

    val doc = extractor.extract(input.copy(bytes = preview, fileName = previewName, kind = kind, archive = null))
    return doc.copy(
      sourceKind = SourceKind.APPLE_BUNDLE,
      // A render of a spreadsheet is not the spreadsheet. Down-weight it so downstream confidence
      // reflects that the cell structure was inferred from a picture of the cells.
      confidence = doc.confidence * 0.85,
      warnings = doc.warnings + IngestWarning(
        "Read from the preview inside that Apple file, not from the document itself — " +
        "hidden rows, formulas and exact cell boundaries aren't available. " +
        "Export to Excel for a complete read.")
    )
  }

  /**
   * The registry hands every extractor the same entry point, and this one needs a delegate, so the
   * default path resolves against the pure registry. [IngestPipeline] calls [extractWith] with the
   * full registry so a PDF preview uses the real PDF extractor rather than failing.
   */
  override fun extract(input: ExtractInput): ExtractedDocument =
    extractWith(input, ExtractorRegistry.pureOnly())

  private fun kindWord(fileName: String) =
    when (fileName.substringAfterLast('.', "").lowercase()) {
      "numbers" -> "Numbers"
      "pages" -> "Pages"
      else -> "iWork"
    }
}

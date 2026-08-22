package com.wildodds.gymtracker.data.reader.ingest

/**
 * One format family, one implementation. Adding a format is a new file plus one registry line.
 *
 * Every implementation must: work on `ByteArray`, be pure (no Android types), throw only
 * [IngestError], and never crash on truncated or bit-flipped input — `ExtractorFuzzTest` proves the
 * last one against every binary extractor.
 */
interface Extractor {
  /** The kinds this extractor claims. Checked against [Sniffer]'s verdict, not the extension. */
  val handles: Set<SourceKind>

  fun extract(input: ExtractInput): ExtractedDocument
}

/**
 * Everything an extractor is allowed to see. Deliberately not a `Uri` or a `File`: the bytes have
 * already been read, capped and decoded by the pipeline, so an extractor cannot do its own I/O.
 */
data class ExtractInput(
  val bytes: ByteArray,
  val fileName: String = "",
  val mimeType: String = "",
  val kind: SourceKind = SourceKind.UNKNOWN,
  val limits: IngestLimits = IngestLimits.DEFAULT,
  val progress: IngestProgress = IngestProgress.NONE,
  /** Pre-read archive, when the pipeline already opened it to sniff. Avoids unzipping twice. */
  val archive: ZipReader.Archive? = null
) {
  override fun equals(other: Any?) = this === other
  override fun hashCode() = System.identityHashCode(this)

  fun cancelCheck() { if (progress.isCancelled()) throw IngestError.Cancelled() }
}

/**
 * The set of extractors in effect. Pure ones are always present; the two that need Android
 * (PDF rendering, OCR) are added by the Android layer, so the JVM corpus runs without them and says
 * so honestly rather than pretending a scanned PDF read as empty text.
 */
class ExtractorRegistry(extractors: List<Extractor>) {

  private val byKind: Map<SourceKind, Extractor> =
    // Last registration wins, so the Android layer can override a pure placeholder.
    extractors.flatMap { e -> e.handles.map { it to e } }.toMap()

  fun forKind(kind: SourceKind): Extractor? = byKind[kind]

  fun plus(extra: List<Extractor>): ExtractorRegistry =
    ExtractorRegistry(byKind.values.distinct() + extra)

  companion object {
    /**
     * Everything that can run on a bare JVM. This is the registry the corpus tests use, which is
     * why it must stay free of Android types.
     */
    fun pureOnly(): ExtractorRegistry = ExtractorRegistry(listOf(
      PlainTextExtractor,
      DelimitedExtractor,
      XlsxExtractor,
      XlsExtractor,
      OpenDocumentExtractor,
      DocxExtractor,
      RtfExtractor,
      HtmlExtractor,
      StructuredExtractor,
      ArchiveExtractor
    ))
  }
}

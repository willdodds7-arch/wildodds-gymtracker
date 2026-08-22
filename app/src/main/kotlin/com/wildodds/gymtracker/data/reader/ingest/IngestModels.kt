package com.wildodds.gymtracker.data.reader.ingest

/**
 * The contract between "getting bytes off a file" and "understanding a program".
 *
 * Everything in this package is **pure Kotlin working on `ByteArray`** — no `Context`, no `Uri`, no
 * `android.*` below the extraction boundary. That is what makes the corpus in `reader/corpus/` a
 * plain JVM test rather than a Robolectric one, and it is the reason a new format costs one file
 * plus one registry line. The two extractors that genuinely cannot be pure (PDF rendering and OCR)
 * sit behind [PageImageSource] and [OcrEngine], which the Android layer supplies.
 *
 * Fully offline, always. Nothing here opens a socket, and `ReaderNoNetworkTest` fails the build if
 * anything in these packages so much as references a networking API.
 */

/** What the sniffer decided the bytes are. Extension is a hint of last resort, never the answer. */
enum class SourceKind {
  PLAIN_TEXT, MARKDOWN, DELIMITED, XLSX, XLS, ODS, ODT, DOCX, RTF, HTML, MHTML,
  PDF_DIGITAL, PDF_SCANNED, IMAGE, JSON, YAML, XML, ZIP, APPLE_BUNDLE, UNKNOWN
}

/**
 * A rectangular block of cells. Row-major, ragged allowed, every cell already trimmed.
 * `[row][col]`; a missing cell is `""` rather than null so downstream code never branches on both.
 */
data class Grid(
  val name: String,
  val rows: List<List<String>>,
  /** Rows the source marked hidden — often stale drafts a coach left behind. */
  val hiddenRows: Set<Int> = emptySet(),
  val hiddenCols: Set<Int> = emptySet(),
  /** The frozen-pane split row, if any — a strong hint about where the header is. */
  val frozenRow: Int? = null,
  /** Cells the source formatted as a percentage, so 0.85 is known to mean 85%. */
  val percentCells: Set<Pair<Int, Int>> = emptySet(),
  /** Cells with a background fill, keyed to a stable colour token — shading is often semantics. */
  val fillColors: Map<Pair<Int, Int>, String> = emptyMap()
) {
  val rowCount: Int get() = rows.size
  val colCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
  fun cell(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c).orEmpty()
  fun isEmpty(): Boolean = rows.all { row -> row.all { it.isBlank() } }
}

/** Severity mirrors [com.wildodds.gymtracker.data.importer.FlagSeverity] but stays dependency-free. */
data class IngestWarning(val message: String, val detail: String = "")

/**
 * The result of reading one document. [text] and [tables] are BOTH populated where the format has
 * both, so a text-only format and a grid-bearing format flow into the same downstream pipeline and
 * every format benefits from every parser improvement.
 *
 * [confidence] is the extractor's own honesty about what it produced: 1.0 for a lossless text read,
 * lower for a positional PDF reconstruction, lower still for OCR. It is multiplied into every slot
 * confidence downstream, so a 0.6-confidence OCR line can never produce a 0.90 slot.
 */
data class ExtractedDocument(
  val text: String = "",
  val tables: List<Grid> = emptyList(),
  /** A title found INSIDE the document — never the filename. See [IngestPipeline] for the order. */
  val title: String? = null,
  val sourceKind: SourceKind = SourceKind.UNKNOWN,
  val confidence: Double = 1.0,
  val warnings: List<IngestWarning> = emptyList(),
  /** Per-line confidence, parallel to [text]'s lines, when the extractor can measure it (OCR). */
  val lineConfidence: List<Double> = emptyList(),
  /**
   * The document's own text, before any rendering for the downstream pipeline.
   *
   * Only differs from [text] where an extractor deliberately reshapes its input: JSON and YAML are
   * flattened to `key: value` lines so that an UNKNOWN export still reaches the line-oriented
   * parser. That flattening is right for the general path and wrong for an app-export adapter,
   * which needs the actual JSON. Empty means [text] is already the original.
   */
  val raw: String = ""
) {
  /** What an adapter should parse: the untouched source when there is one, otherwise [text]. */
  val sourceText: String get() = raw.ifBlank { text }

  val isEmpty: Boolean get() = text.isBlank() && tables.all { it.isEmpty() }
}

/**
 * Every failure an extractor is allowed to produce. Typed, never a bare exception, and never a
 * crash: a corrupt zip or a truncated xlsx is a normal thing for a user to hand us.
 */
sealed class IngestError(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class Unsupported(val kind: String, message: String) : IngestError(message)
  class Corrupt(message: String, cause: Throwable? = null) : IngestError(message, cause)
  class TooLarge(message: String) : IngestError(message)
  class Empty(message: String) : IngestError(message)
  /** The bytes were read fine but there is not enough legible content to be worth parsing. */
  class Illegible(message: String) : IngestError(message)
  class Cancelled : IngestError("Import cancelled.")
}

/**
 * Hard caps. Every one of these exists because the alternative is an OOM on a mid-range phone with
 * a file the user believed was fine. They are constructor parameters rather than constants so tests
 * can shrink them and actually exercise the limit paths.
 */
data class IngestLimits(
  val maxFileBytes: Long = 64L * 1024 * 1024,
  /** Guards a zip bomb: a 42 KB archive can decompress to petabytes. */
  val maxDecompressedBytes: Long = 256L * 1024 * 1024,
  val maxZipEntries: Int = 2_000,
  val maxZipDepth: Int = 2,
  val maxPages: Int = 100,
  val maxCells: Int = 500_000,
  val maxSheets: Int = 64,
  val maxTextChars: Int = 8 * 1024 * 1024,
  val maxOcrPages: Int = 20,
  val maxOcrMillis: Long = 120_000
) {
  companion object { val DEFAULT = IngestLimits() }
}

/** Cooperative cancellation + progress, so a 12-page PDF is a progress bar rather than a freeze. */
interface IngestProgress {
  fun report(stage: String, current: Int = 0, total: Int = 0)
  /** Checked between units of work; extractors throw [IngestError.Cancelled] when it returns true. */
  fun isCancelled(): Boolean = false

  companion object {
    val NONE: IngestProgress = object : IngestProgress {
      override fun report(stage: String, current: Int, total: Int) = Unit
    }
  }
}

/** One rasterised page, handed to [OcrEngine]. Supplied by the Android layer; pure below. */
data class PageImage(val pageIndex: Int, val width: Int, val height: Int, val argb: IntArray) {
  override fun equals(other: Any?) = this === other
  override fun hashCode() = pageIndex * 31 + width
}

/** Renders PDF pages to bitmaps. Android-only in production; a fake in tests. */
interface PageImageSource {
  fun pageCount(): Int
  fun render(pageIndex: Int, targetWidthPx: Int): PageImage
}

/** One OCR'd line with the geometry the repair pass needs to rebuild columns. */
data class OcrLine(
  val text: String,
  val confidence: Double,
  val left: Int, val top: Int, val right: Int, val bottom: Int
) {
  val baselineY: Int get() = bottom
}

/** On-device text recognition. The production implementation is ML Kit with a BUNDLED model. */
interface OcrEngine {
  fun recognise(image: PageImage): List<OcrLine>
}

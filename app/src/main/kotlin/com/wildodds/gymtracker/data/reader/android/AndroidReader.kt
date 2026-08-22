package com.wildodds.gymtracker.data.reader.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.wildodds.gymtracker.data.reader.ProgramReader
import com.wildodds.gymtracker.data.reader.GridProgramReader
import com.wildodds.gymtracker.data.reader.ingest.ExtractInput
import com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument
import com.wildodds.gymtracker.data.reader.ingest.Extractor
import com.wildodds.gymtracker.data.reader.ingest.ExtractorRegistry
import com.wildodds.gymtracker.data.reader.ingest.IngestError
import com.wildodds.gymtracker.data.reader.ingest.IngestLimits
import com.wildodds.gymtracker.data.reader.ingest.IngestProgress
import com.wildodds.gymtracker.data.reader.ingest.MultiImageSource
import com.wildodds.gymtracker.data.reader.ingest.OcrExtractor
import com.wildodds.gymtracker.data.reader.ingest.SourceKind

/**
 * The Android edge of the reader: reads a `Uri`'s bytes, adds the two extractors that need the
 * platform, and hands everything else to the pure [ProgramReader].
 *
 * This is the ONLY file in the reader that knows about `Context` or `Uri`. Everything below it works
 * on `ByteArray`, which is what makes the 120-document corpus a plain JVM test rather than a
 * Robolectric one.
 *
 * Still fully offline: PDFBox is bundled, ML Kit uses its bundled model, and nothing here opens a
 * socket. `ReaderNoNetworkTest` fails the build if that stops being true.
 */
object AndroidReader {

  /**
   * PDFBox-Android needs its resources initialised once per process before any document is loaded.
   * Doing it lazily here — rather than in `Application.onCreate` — keeps the cost off app startup,
   * which matters because most sessions never import anything.
   */
  @Volatile private var pdfBoxReady = false

  private fun ensurePdfBox(context: Context) {
    if (!pdfBoxReady) synchronized(this) {
      if (!pdfBoxReady) { PDFBoxResourceLoader.init(context.applicationContext); pdfBoxReady = true }
    }
  }

  fun reader(context: Context, limits: IngestLimits = IngestLimits.DEFAULT,
             gridReader: GridProgramReader = GridProgramReader.NONE): ProgramReader {
    ensurePdfBox(context)
    return ProgramReader(registry(context), limits, gridReader)
  }

  /** The pure registry plus PDF text, PDF scans and images. */
  fun registry(context: Context): ExtractorRegistry {
    val cacheDir = context.applicationContext.cacheDir
    return ExtractorRegistry.pureOnly().plus(listOf(
      PdfExtractor(),
      ScanExtractor(cacheDir)
    ))
  }

  /**
   * Routes the two OCR cases to the right image source: a scanned PDF is rasterised page by page
   * with `PdfRenderer`; a photo is decoded once, EXIF-rotated and downsampled.
   *
   * It is one [Extractor] rather than two because [OcrExtractor] is identical for both — only where
   * the pixels come from differs.
   */
  private class ScanExtractor(private val cacheDir: java.io.File) : Extractor {
    override val handles = setOf(SourceKind.PDF_SCANNED, SourceKind.IMAGE)

    override fun extract(input: ExtractInput): ExtractedDocument {
      val engine = MlKitOcrEngine()
      return if (input.kind == SourceKind.PDF_SCANNED) {
        PdfPageImageSource(input.bytes, cacheDir).use { source ->
          OcrExtractor(engine, source).extract(input)
        }
      } else {
        val page = BitmapImageSource.decode(input.bytes)
        OcrExtractor(engine, MultiImageSource(listOf(page))).extract(input)
      }
    }
  }

  /**
   * Just the text of a picked file, for the one caller that parses it its own way (the opt-in
   * `ClaudeApiClient` path). It goes through the same offline ingest pipeline as everything else,
   * so there is exactly one place that turns a `Uri` into text and the AI path inherits every
   * extraction improvement without a second code path to keep in step.
   */
  fun extractText(context: Context, uri: Uri, progress: IngestProgress = IngestProgress.NONE): String {
    ensurePdfBox(context)
    val bytes = readBytes(context, uri)
    return com.wildodds.gymtracker.data.reader.ingest.IngestPipeline(registry(context))
      .ingest(bytes, displayName(context, uri), mimeType(context, uri), progress)
      .document.text
  }

  /**
   * Several images picked at once — four photos of a printed program — read as ONE document in the
   * order the user picked them. Reading them separately produces four one-day programs, which is
   * never what anyone wanted.
   */
  fun readImages(context: Context, uris: List<Uri>, progress: IngestProgress = IngestProgress.NONE): ExtractedDocument {
    val pages = uris.mapIndexed { i, uri ->
      progress.report("Reading image ${i + 1} of ${uris.size}", i + 1, uris.size)
      BitmapImageSource.decode(readBytes(context, uri), i)
    }
    if (pages.isEmpty()) throw IngestError.Empty("No images were picked.")
    val input = ExtractInput(ByteArray(0), kind = SourceKind.IMAGE, progress = progress)
    return OcrExtractor(MlKitOcrEngine(), MultiImageSource(pages)).extract(input)
  }

  /** Reads a picked file, respecting the size cap BEFORE allocating a byte array for it. */
  fun readBytes(context: Context, uri: Uri, limits: IngestLimits = IngestLimits.DEFAULT): ByteArray {
    val resolver = context.applicationContext.contentResolver
    val declared = declaredSize(context, uri)
    if (declared != null && declared > limits.maxFileBytes) {
      throw IngestError.TooLarge(
        "That file is ${declared / (1024 * 1024)} MB — the limit is ${limits.maxFileBytes / (1024 * 1024)} MB.")
    }
    val stream = resolver.openInputStream(uri)
      ?: throw IngestError.Corrupt("That file couldn't be opened.")
    return stream.use { input ->
      val out = java.io.ByteArrayOutputStream()
      val buf = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        total += n
        // Re-check while reading: a content provider can under-report, or lie.
        if (total > limits.maxFileBytes) {
          throw IngestError.TooLarge("That file is larger than ${limits.maxFileBytes / (1024 * 1024)} MB.")
        }
        out.write(buf, 0, n)
      }
      out.toByteArray()
    }
  }

  fun displayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
      context.applicationContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { return it }
      }
    }
    return uri.path?.substringAfterLast('/').orEmpty()
  }

  fun mimeType(context: Context, uri: Uri): String =
    context.applicationContext.contentResolver.getType(uri).orEmpty()

  private fun declaredSize(context: Context, uri: Uri): Long? {
    if (uri.scheme != "content") return null
    context.applicationContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
      val idx = c.getColumnIndex(OpenableColumns.SIZE)
      if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
    }
    return null
  }
}

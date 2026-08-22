package com.wildodds.gymtracker.data.reader.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wildodds.gymtracker.data.reader.ingest.IngestError
import com.wildodds.gymtracker.data.reader.ingest.OcrEngine
import com.wildodds.gymtracker.data.reader.ingest.OcrLine
import com.wildodds.gymtracker.data.reader.ingest.PageImage
import com.wildodds.gymtracker.data.reader.ingest.PageImageSource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ML Kit text recognition, **bundled model**.
 *
 * The dependency is `com.google.mlkit:text-recognition`, not
 * `com.google.android.gms:play-services-mlkit-text-recognition`. That distinction is the whole
 * offline story: the Play-services variant downloads its model on first use, so the first photo
 * import on a new phone would need a network round trip and would simply fail in airplane mode. The
 * bundled variant ships the Latin model inside the APK and works on first launch, offline, with no
 * account and no Play Services.
 *
 * ML Kit's API is callback-based and this sits inside a synchronous [OcrEngine], so each page is
 * awaited on a latch. That is safe here because the whole ingest pipeline already runs off the main
 * thread; blocking the main thread on this would be a bug, and [com.wildodds.gymtracker.data.reader
 * .ingest.IngestPipeline] is the only caller.
 */
class MlKitOcrEngine : OcrEngine {

  private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

  /** A page that takes longer than this is not going to succeed; fail rather than hang the import. */
  private val perPageTimeoutSeconds = 30L

  override fun recognise(image: PageImage): List<OcrLine> {
    val bitmap = image.toBitmap()
    val input = InputImage.fromBitmap(bitmap, 0)
    var result: List<OcrLine> = emptyList()
    var failure: Exception? = null
    val latch = CountDownLatch(1)

    recognizer.process(input)
      .addOnSuccessListener { text ->
        result = text.textBlocks.asSequence()
          .flatMap { it.lines.asSequence() }
          .mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(
              text = line.text,
              // ML Kit exposes a nullable per-line confidence; a missing one is not evidence of a
              // GOOD read, so it is treated as merely acceptable rather than as certain.
              confidence = line.confidence?.toDouble() ?: 0.7,
              left = box.left, top = box.top, right = box.right, bottom = box.bottom
            )
          }
          .toList()
        latch.countDown()
      }
      .addOnFailureListener { e -> failure = e as? Exception ?: RuntimeException(e); latch.countDown() }

    if (!latch.await(perPageTimeoutSeconds, TimeUnit.SECONDS)) {
      throw IngestError.Corrupt("Reading that page timed out.")
    }
    failure?.let { throw IngestError.Corrupt("On-device text recognition failed.", it) }
    bitmap.recycle()
    return result
  }
}

/** ARGB ints -> Bitmap. The pure layer never sees a Bitmap; the conversion happens exactly here. */
internal fun PageImage.toBitmap(): Bitmap =
  Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)

internal fun Bitmap.toPageImage(index: Int): PageImage {
  val px = IntArray(width * height)
  getPixels(px, 0, width, 0, 0, width, height)
  return PageImage(index, width, height, px)
}

/**
 * Rasterises PDF pages with the platform's own `PdfRenderer` — no extra dependency, and it renders
 * the same pages the user sees in any PDF viewer.
 *
 * `PdfRenderer` insists on a seekable file descriptor, so the bytes are spooled to a temp file in
 * the app's cache. The file is deleted in [close]; callers must use this in a `use { }`.
 */
class PdfPageImageSource(bytes: ByteArray, cacheDir: File) : PageImageSource, AutoCloseable {

  private val temp = File.createTempFile("reader-ocr", ".pdf", cacheDir).apply { writeBytes(bytes) }
  private val fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
  private val renderer = try {
    PdfRenderer(fd)
  } catch (e: Exception) {
    fd.close(); temp.delete()
    throw IngestError.Corrupt("That PDF couldn't be opened for scanning.", e)
  }

  override fun pageCount(): Int = renderer.pageCount

  override fun render(pageIndex: Int, targetWidthPx: Int): PageImage =
    renderer.openPage(pageIndex).use { page ->
      // Render at the requested width rather than at the page's nominal size: a 72-dpi page
      // rasterised 1:1 gives text about 8 px tall, which no recogniser can read.
      val scale = (targetWidthPx.toFloat() / page.width).coerceIn(1f, 6f)
      val w = (page.width * scale).toInt().coerceAtLeast(1)
      val h = (page.height * scale).toInt().coerceAtLeast(1)
      val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      // A transparent bitmap renders text onto nothing and OCR sees an empty page; fill it white.
      bmp.eraseColor(android.graphics.Color.WHITE)
      page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      bmp.toPageImage(pageIndex).also { bmp.recycle() }
    }

  override fun close() {
    runCatching { renderer.close() }
    runCatching { fd.close() }
    temp.delete()
  }
}

/**
 * A single picked image. Two things happen here that decide whether OCR works at all:
 *
 *  - **EXIF rotation.** A phone photo is almost always stored landscape with an orientation tag.
 *    Decoding without honouring it hands the recogniser a page rotated 90°, and it returns nothing
 *    at all — which reads to the user as "photo import is broken".
 *  - **Downsampling on decode.** A 12-megapixel photo is 48 MB as ARGB_8888 and will OOM a
 *    mid-range phone before OCR even starts, so it is sampled down during decode, not after.
 */
class BitmapImageSource(private val decoded: List<PageImage>) : PageImageSource {
  override fun pageCount(): Int = decoded.size
  override fun render(pageIndex: Int, targetWidthPx: Int): PageImage = decoded[pageIndex]

  companion object {
    private const val MAX_DIMENSION = 3000

    fun decode(bytes: ByteArray, index: Int = 0): PageImage {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IngestError.Corrupt("That image couldn't be decoded.")
      }
      var sample = 1
      while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2

      val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
      val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: throw IngestError.Corrupt("That image couldn't be decoded.")

      val rotated = applyExifRotation(bmp, bytes)
      return rotated.toPageImage(index).also { if (rotated !== bmp) rotated.recycle(); bmp.recycle() }
    }

    private fun applyExifRotation(bmp: Bitmap, bytes: ByteArray): Bitmap {
      val orientation = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(
          ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

      val m = Matrix()
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        else -> return bmp
      }
      return runCatching { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }.getOrDefault(bmp)
    }
  }
}

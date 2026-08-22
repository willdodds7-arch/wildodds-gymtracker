package com.wildodds.gymtracker.data.reader.ingest

/**
 * Pure image preprocessing for OCR — deskew, threshold, upscale.
 *
 * Recognisers are trained on flat, high-contrast, upright text. A phone photo of a printout is none
 * of those: it is taken at an angle under a ceiling light, so one half of the page is in shadow and
 * the lines run downhill. Handing that to the recogniser directly is where "OCR doesn't work" comes
 * from; almost all of the difference is in this file rather than in the recogniser.
 *
 * Operates on ARGB int arrays so it stays pure and unit-testable — [PageImage] carries no Android
 * `Bitmap`. The Android layer converts once at each end.
 */
object ImagePrep {

  /** Recognisers want roughly this cap-height in pixels; below it, small print is unreadable. */
  const val TARGET_TEXT_HEIGHT_PX = 32

  /**
   * The full chain, in the order that matters: grey → deskew → adaptive threshold → upscale.
   *
   * Threshold AFTER deskew, because rotating a already-binarised image resamples hard black/white
   * edges into grey fringes that the recogniser reads as noise.
   */
  fun prepare(image: PageImage, minWidth: Int = 1400): PageImage {
    val grey = toGrey(image)
    val angle = estimateSkew(grey)
    val deskewed = if (kotlin.math.abs(angle) > 0.3) rotate(grey, -angle) else grey
    val binary = adaptiveThreshold(deskewed)
    return if (binary.width < minWidth) upscale(binary, minWidth) else binary
  }

  /** Luminance, kept in all three channels so downstream code can treat it as a normal image. */
  fun toGrey(img: PageImage): PageImage {
    val out = IntArray(img.argb.size)
    for (i in img.argb.indices) {
      val p = img.argb[i]
      val r = (p shr 16) and 0xFF
      val g = (p shr 8) and 0xFF
      val b = p and 0xFF
      // Rec. 601 luma: green dominates perceived brightness, and printed text is usually black ink.
      val y = (r * 299 + g * 587 + b * 114) / 1000
      out[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
    }
    return PageImage(img.pageIndex, img.width, img.height, out)
  }

  /**
   * Adaptive (local-mean) threshold over a window, rather than one global cutoff.
   *
   * A global Otsu threshold fails on exactly the photo people take: bright at the top, shadowed at
   * the bottom. A local mean follows the illumination, so the shadowed half binarises correctly.
   * The small negative bias keeps thin strokes from being eaten by their own neighbourhood.
   */
  fun adaptiveThreshold(img: PageImage, window: Int = 25, bias: Int = 10): PageImage {
    val w = img.width
    val h = img.height
    if (w < 3 || h < 3) return img
    // Summed-area table, so the window mean is O(1) per pixel regardless of window size.
    val sat = LongArray((w + 1) * (h + 1))
    for (y in 0 until h) {
      var rowSum = 0L
      for (x in 0 until w) {
        rowSum += img.argb[y * w + x] and 0xFF
        sat[(y + 1) * (w + 1) + (x + 1)] = sat[y * (w + 1) + (x + 1)] + rowSum
      }
    }
    val r = window / 2
    val out = IntArray(w * h)
    for (y in 0 until h) {
      val y0 = (y - r).coerceAtLeast(0)
      val y1 = (y + r).coerceAtMost(h - 1)
      for (x in 0 until w) {
        val x0 = (x - r).coerceAtLeast(0)
        val x1 = (x + r).coerceAtMost(w - 1)
        val area = ((y1 - y0 + 1) * (x1 - x0 + 1)).toLong()
        val sum = sat[(y1 + 1) * (w + 1) + (x1 + 1)] - sat[y0 * (w + 1) + (x1 + 1)] -
          sat[(y1 + 1) * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
        val mean = (sum / area).toInt()
        val v = img.argb[y * w + x] and 0xFF
        val on = v < mean - bias
        val c = if (on) 0 else 255
        out[y * w + x] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
      }
    }
    return PageImage(img.pageIndex, w, h, out)
  }

  /**
   * Skew angle in degrees, by projection-profile variance: rotate a coarse copy through a range of
   * candidate angles and keep the one whose horizontal ink histogram has the sharpest peaks. Text
   * lines are the peaks, so the sharpest profile is the upright one.
   *
   * Bounded to ±8°, because a page rotated further than that is not "slightly skewed", it is a
   * different orientation, and pretending otherwise produces a confidently wrong deskew.
   */
  fun estimateSkew(grey: PageImage, maxDegrees: Double = 8.0, step: Double = 0.5): Double {
    val w = grey.width
    val h = grey.height
    if (w < 32 || h < 32) return 0.0
    // Work on a downsampled copy: skew is a global property and full resolution buys nothing.
    val scale = maxOf(1, maxOf(w, h) / 600)
    val sw = w / scale
    val sh = h / scale
    val ink = BooleanArray(sw * sh)
    for (y in 0 until sh) for (x in 0 until sw) {
      ink[y * sw + x] = (grey.argb[(y * scale) * w + x * scale] and 0xFF) < 128
    }

    var best = 0.0
    var bestScore = -1.0
    var a = -maxDegrees
    while (a <= maxDegrees) {
      val rad = Math.toRadians(a)
      val tan = kotlin.math.tan(rad)
      val profile = IntArray(sh)
      for (y in 0 until sh) for (x in 0 until sw) {
        if (!ink[y * sw + x]) continue
        val yy = y - (x * tan).toInt()
        if (yy in 0 until sh) profile[yy]++
      }
      // Variance of the profile: peaky (aligned lines) beats flat (smeared lines).
      val mean = profile.sum().toDouble() / sh
      val score = profile.sumOf { val d = it - mean; d * d }
      if (score > bestScore) { bestScore = score; best = a }
      a += step
    }
    return best
  }

  /** Nearest-neighbour rotation about the centre. Fast, and adequate for a deskew of a few degrees. */
  fun rotate(img: PageImage, degrees: Double): PageImage {
    val rad = Math.toRadians(degrees)
    val cos = kotlin.math.cos(rad)
    val sin = kotlin.math.sin(rad)
    val w = img.width
    val h = img.height
    val cx = w / 2.0
    val cy = h / 2.0
    val out = IntArray(w * h) { 0xFFFFFFFF.toInt() }   // white, not black: the page is white
    for (y in 0 until h) for (x in 0 until w) {
      val dx = x - cx
      val dy = y - cy
      val sx = (dx * cos + dy * sin + cx).toInt()
      val sy = (-dx * sin + dy * cos + cy).toInt()
      if (sx in 0 until w && sy in 0 until h) out[y * w + x] = img.argb[sy * w + sx]
    }
    return PageImage(img.pageIndex, w, h, out)
  }

  /** Nearest-neighbour upscale. Small text needs pixels, and interpolation would blur the strokes. */
  fun upscale(img: PageImage, targetWidth: Int): PageImage {
    if (img.width <= 0 || targetWidth <= img.width) return img
    val factor = targetWidth.toDouble() / img.width
    val nw = targetWidth
    val nh = (img.height * factor).toInt().coerceAtLeast(1)
    val out = IntArray(nw * nh)
    for (y in 0 until nh) {
      val sy = (y / factor).toInt().coerceAtMost(img.height - 1)
      for (x in 0 until nw) {
        val sx = (x / factor).toInt().coerceAtMost(img.width - 1)
        out[y * nw + x] = img.argb[sy * img.width + sx]
      }
    }
    return PageImage(img.pageIndex, nw, nh, out)
  }
}

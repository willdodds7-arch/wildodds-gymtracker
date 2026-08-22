package com.wildodds.gymtracker.data.reader.ingest

/**
 * Legacy `.xls` (BIFF8), read-only and best-effort, with no third-party dependency.
 *
 * Coaches still email `.xls` files, and the old reader handed them to a UTF-8 text read, which
 * produced a screenful of binary garbage that then "imported" as a program. Failing honestly would
 * have been better; reading them is better still.
 *
 * Two layers:
 *  1. **OLE2 / CFB** — the container. A header, a FAT of 512-byte sectors, a directory of streams.
 *     We need exactly one stream, `Workbook` (or `Book` in very old files).
 *  2. **BIFF8 records** — a flat `[id:u16][len:u16][payload]` stream. We read BOF, BOUNDSHEET, SST,
 *     CONTINUE, LABELSST, LABEL, RSTRING, NUMBER, RK, MULRK, MULBLANK, FORMULA (cached value only)
 *     and BLANK. Everything else is skipped by length, which is what makes a partial implementation
 *     safe rather than fragile.
 *
 * Not supported, and said so rather than guessed: encrypted workbooks, BIFF5 and earlier, charts,
 * and formulas without a cached value. `.doc` is OLE2 too and is rejected explicitly — a Word
 * document read as a spreadsheet is exactly the "silent garbage" failure mode this replaces.
 */
object XlsExtractor : Extractor {

  override val handles = setOf(SourceKind.XLS)

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val cfb = Cfb.open(input.bytes) ?: throw IngestError.Corrupt("That .xls file's container is unreadable.")

    if (cfb.hasStream("WordDocument")) {
      throw IngestError.Unsupported("doc",
        "That's a Word .doc, which this app can't read. Save it as .docx or paste the text instead.")
    }
    val stream = cfb.stream("Workbook") ?: cfb.stream("Book")
      ?: throw IngestError.Unsupported("xls",
        "That .xls file has no workbook stream — it may be a different Office format.")

    val warnings = ArrayList<IngestWarning>()
    val grids = readBiff(stream, input.limits, warnings, input.progress)
    if (grids.isEmpty()) throw IngestError.Empty("That workbook has no readable sheets.")

    return ExtractedDocument(
      text = grids.joinToString("\n\n") { g -> "# ${g.name}\n" + g.rows.joinToString("\n") { it.joinToString("\t") } },
      tables = grids,
      sourceKind = SourceKind.XLS,
      // A best-effort legacy read is not a lossless read, and downstream confidence should say so.
      confidence = 0.9,
      warnings = warnings + IngestWarning(
        "Read as a legacy .xls — formatting, formulas and merged cells are approximate.")
    )
  }

  // ── BIFF8 ────────────────────────────────────────────────────────────────────

  private const val BOF = 0x0809
  private const val EOF_REC = 0x000A
  private const val BOUNDSHEET = 0x0085
  private const val SST = 0x00FC
  private const val CONTINUE = 0x003C
  private const val LABELSST = 0x00FD
  private const val LABEL = 0x0204
  private const val RSTRING = 0x00D6
  private const val NUMBER = 0x0203
  private const val RK = 0x027E
  private const val MULRK = 0x00BD
  private const val FORMULA = 0x0006
  private const val STRING_REC = 0x0207
  private const val FILEPASS = 0x002F

  private class Sheet(val name: String, val offset: Int) {
    val cells = HashMap<Long, String>()
    var maxRow = -1
    var maxCol = -1
    fun put(r: Int, c: Int, v: String) {
      if (v.isBlank()) return
      cells[r.toLong() * 65_536L + c] = v
      if (r > maxRow) maxRow = r
      if (c > maxCol) maxCol = c
    }
  }

  private fun readBiff(
    d: ByteArray,
    limits: IngestLimits,
    warnings: MutableList<IngestWarning>,
    progress: IngestProgress
  ): List<Grid> {
    val sheets = ArrayList<Sheet>()
    val sst = ArrayList<String>()
    var pos = 0

    // Pass 1: the globals substream — sheet directory and the shared string table.
    while (pos + 4 <= d.size) {
      val id = le16(d, pos); val len = le16(d, pos + 2); val body = pos + 4
      if (body + len > d.size) break
      when (id) {
        FILEPASS -> throw IngestError.Unsupported("xls-encrypted",
          "That .xls file is password-protected. Remove the password and try again.")
        BOUNDSHEET -> {
          val off = le32(d, body)
          val name = readShortUnicodeString(d, body + 6)
          sheets += Sheet(name, off)
        }
        SST -> readSst(d, body, len, sst)
        EOF_REC -> { pos = body + len; break }
      }
      pos = body + len
    }
    if (sheets.isEmpty()) return emptyList()

    // Pass 2: each sheet's substream, starting from its BOUNDSHEET offset.
    var cellBudget = limits.maxCells
    for ((i, sheet) in sheets.withIndex()) {
      if (progress.isCancelled()) throw IngestError.Cancelled()
      if (i >= limits.maxSheets) { warnings += IngestWarning("Only the first ${limits.maxSheets} sheets were read."); break }
      var p = sheet.offset
      var lastFormulaCell: Pair<Int, Int>? = null
      while (p + 4 <= d.size && cellBudget > 0) {
        val id = le16(d, p); val len = le16(d, p + 2); val b = p + 4
        if (b + len > d.size) { warnings += IngestWarning("Sheet \"${sheet.name}\" is truncated."); break }
        when (id) {
          EOF_REC -> { p = b + len; break }
          LABELSST -> {
            val r = le16(d, b); val c = le16(d, b + 2)
            sheet.put(r, c, sst.getOrElse(le32(d, b + 6)) { "" }); cellBudget--
          }
          LABEL, RSTRING -> {
            val r = le16(d, b); val c = le16(d, b + 2)
            sheet.put(r, c, readShortUnicodeString(d, b + 6)); cellBudget--
          }
          NUMBER -> {
            val r = le16(d, b); val c = le16(d, b + 2)
            sheet.put(r, c, fmt(java.lang.Double.longBitsToDouble(le64(d, b + 6)))); cellBudget--
          }
          RK -> {
            val r = le16(d, b); val c = le16(d, b + 2)
            sheet.put(r, c, fmt(rkValue(le32(d, b + 6)))); cellBudget--
          }
          MULRK -> {
            val r = le16(d, b); val first = le16(d, b + 2)
            val count = (len - 6) / 6
            for (k in 0 until count) {
              sheet.put(r, first + k, fmt(rkValue(le32(d, b + 4 + k * 6 + 2)))); cellBudget--
            }
          }
          FORMULA -> {
            val r = le16(d, b); val c = le16(d, b + 2)
            // Bytes 6..13 are the cached result. A "string" result is signalled by 0xFFFF in the
            // high word with byte 6 == 0, and the actual text arrives in the NEXT record.
            if (le16(d, b + 12) == 0xFFFF && d[b + 6].toInt() and 0xFF == 0) {
              lastFormulaCell = r to c
            } else {
              sheet.put(r, c, fmt(java.lang.Double.longBitsToDouble(le64(d, b + 6)))); cellBudget--
            }
          }
          STRING_REC -> lastFormulaCell?.let { (r, c) ->
            sheet.put(r, c, readShortUnicodeString(d, b)); cellBudget--; lastFormulaCell = null
          }
        }
        p = b + len
      }
      if (cellBudget <= 0) warnings += IngestWarning("The workbook exceeded the cell budget; later sheets were skipped.")
    }

    return sheets.filter { it.maxRow >= 0 }.map { s ->
      Grid(s.name, (0..s.maxRow).map { r ->
        (0..s.maxCol).map { c -> s.cells[r.toLong() * 65_536L + c].orEmpty() }
      })
    }
  }

  /**
   * The SST spans CONTINUE records, and a string can be split ACROSS that boundary — with the
   * compressed/uncompressed flag byte repeated at the start of the continuation. Getting this wrong
   * is the classic BIFF8 bug: every string after the first 8 KB comes out shifted by one byte.
   */
  private fun readSst(d: ByteArray, body: Int, len: Int, out: MutableList<String>) {
    // Stitch the SST record and its CONTINUE records into one buffer, dropping the leading flag
    // byte of each continuation only when it does not belong to a split string. Simpler and
    // safe: read straight through the concatenation, re-reading the flag byte at each boundary.
    val total = le32(d, body + 4)
    var p = body + 8
    var end = body + len
    var next = body + len
    // Collect the CONTINUE payload ranges that follow.
    val chunks = ArrayList<IntArray>()
    chunks += intArrayOf(p, end)
    var q = next
    while (q + 4 <= d.size && le16(d, q) == CONTINUE) {
      val cl = le16(d, q + 2)
      if (q + 4 + cl > d.size) break
      chunks += intArrayOf(q + 4, q + 4 + cl)
      q += 4 + cl
    }
    val buf = java.io.ByteArrayOutputStream()
    // Mark where each chunk starts in the flattened buffer, so the flag byte can be re-read there.
    val starts = ArrayList<Int>()
    for (ch in chunks) { starts += buf.size(); buf.write(d, ch[0], ch[1] - ch[0]) }
    val flat = buf.toByteArray()
    val boundary = starts.drop(1).toHashSet()

    var i = 0
    var read = 0
    while (read < total && i + 3 <= flat.size) {
      val cch = le16(flat, i)
      var flags = flat[i + 2].toInt() and 0xFF
      i += 3
      var rich = 0
      var ext = 0
      if (flags and 0x08 != 0) { rich = le16(flat, i); i += 2 }
      if (flags and 0x04 != 0) { ext = le32(flat, i); i += 4 }
      val sb = StringBuilder(cch)
      var chars = 0
      while (chars < cch && i < flat.size) {
        // At a chunk boundary the flag byte is repeated and applies to the remainder.
        if (i in boundary) { flags = flat[i].toInt() and 0xFF; i++ }
        if (flags and 0x01 != 0) {
          if (i + 1 >= flat.size) break
          sb.append(le16(flat, i).toChar()); i += 2
        } else {
          sb.append((flat[i].toInt() and 0xFF).toChar()); i++
        }
        chars++
      }
      i += rich * 4 + ext
      out += sb.toString()
      read++
    }
  }

  /** A BIFF "short" unicode string: `[cch:u16][flags:u8][data]`. */
  private fun readShortUnicodeString(d: ByteArray, at: Int): String {
    if (at + 3 > d.size) return ""
    val cch = le16(d, at)
    val flags = d[at + 2].toInt() and 0xFF
    var i = at + 3
    val sb = StringBuilder(cch)
    var n = 0
    while (n < cch && i < d.size) {
      if (flags and 0x01 != 0) { if (i + 1 >= d.size) break; sb.append(le16(d, i).toChar()); i += 2 }
      else { sb.append((d[i].toInt() and 0xFF).toChar()); i++ }
      n++
    }
    return sb.toString()
  }

  /** RK packs a number into 30 bits: optionally x100, optionally an integer rather than a double. */
  private fun rkValue(raw: Int): Double {
    val isInt = raw and 0x02 != 0
    val div100 = raw and 0x01 != 0
    val v = if (isInt) (raw shr 2).toDouble()
            else java.lang.Double.longBitsToDouble((raw.toLong() and 0xFFFFFFFCL) shl 32)
    return if (div100) v / 100.0 else v
  }

  private fun fmt(d: Double): String =
    if (d.isNaN() || d.isInfinite()) ""
    else if (d == d.toLong().toDouble() && kotlin.math.abs(d) < 1e15) d.toLong().toString()
    else java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP)
      .stripTrailingZeros().toPlainString()

  private fun le16(d: ByteArray, i: Int): Int =
    if (i + 1 >= d.size) 0 else (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)

  private fun le32(d: ByteArray, i: Int): Int =
    if (i + 3 >= d.size) 0
    else (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or
      ((d[i + 2].toInt() and 0xFF) shl 16) or ((d[i + 3].toInt() and 0xFF) shl 24)

  private fun le64(d: ByteArray, i: Int): Long =
    (le32(d, i).toLong() and 0xFFFFFFFFL) or ((le32(d, i + 4).toLong() and 0xFFFFFFFFL) shl 32)
}

/**
 * The OLE2 / Compound File Binary container that wraps a legacy `.xls` (and `.doc`, and `.ppt`).
 * Only what is needed to pull one named stream out: header, FAT, directory, mini-FAT.
 */
internal class Cfb private constructor(
  private val data: ByteArray,
  private val sectorSize: Int,
  private val fat: IntArray,
  private val miniFat: IntArray,
  private val miniStream: ByteArray,
  private val dir: Map<String, Pair<Int, Long>>   // name -> (start sector, size)
) {
  fun hasStream(name: String) = dir.containsKey(name)

  fun stream(name: String): ByteArray? {
    val (start, size) = dir[name] ?: return null
    if (size <= 0) return null
    // Streams under 4096 bytes live in the mini-stream, chained through the mini-FAT.
    return if (size < MINI_CUTOFF) chain(miniStream, miniFat, start, size, MINI_SECTOR)
           else chain(data, fat, start, size, sectorSize, headerOffset = sectorSize)
  }

  private fun chain(src: ByteArray, table: IntArray, start: Int, size: Long, sec: Int, headerOffset: Int = 0): ByteArray? {
    val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtMost(1 shl 24))
    var s = start
    var guard = 0
    while (s >= 0 && out.size() < size && guard++ < MAX_CHAIN) {
      val off = headerOffset + s * sec
      if (off < 0 || off >= src.size) break
      val n = minOf(sec, src.size - off, (size - out.size()).toInt())
      if (n <= 0) break
      out.write(src, off, n)
      s = table.getOrElse(s) { -1 }
    }
    return out.toByteArray().takeIf { it.isNotEmpty() }
  }

  companion object {
    private const val MINI_CUTOFF = 4096L
    private const val MINI_SECTOR = 64
    private const val MAX_CHAIN = 1 shl 20
    private val SIG = byteArrayOf(
      0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
      0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    fun open(d: ByteArray): Cfb? {
      if (d.size < 512) return null
      for (i in SIG.indices) if (d[i] != SIG[i]) return null
      val sectorSize = 1 shl u16(d, 30)
      if (sectorSize < 128 || sectorSize > 1 shl 16) return null
      val dirStart = i32(d, 48)
      val miniFatStart = i32(d, 60)
      val difStart = i32(d, 68)
      val difCount = i32(d, 72)

      // FAT sector list: 109 entries in the header, then the DIFAT chain.
      val fatSectors = ArrayList<Int>()
      for (k in 0 until 109) {
        val s = i32(d, 76 + k * 4)
        if (s < 0) break
        fatSectors += s
      }
      var dif = difStart
      var guard = 0
      while (dif >= 0 && guard++ < 4096 && difCount > 0) {
        val off = sectorSize + dif * sectorSize
        if (off + sectorSize > d.size) break
        val per = sectorSize / 4 - 1
        for (k in 0 until per) {
          val s = i32(d, off + k * 4)
          if (s >= 0) fatSectors += s
        }
        dif = i32(d, off + per * 4)
      }

      val fat = readTable(d, fatSectors, sectorSize) ?: return null
      val miniFat = readChainTable(d, fat, miniFatStart, sectorSize)

      // The directory gives us the root entry (which owns the mini-stream) and every named stream.
      val dirBytes = readChain(d, fat, dirStart, sectorSize) ?: return null
      val dir = HashMap<String, Pair<Int, Long>>()
      var rootStart = -1
      var rootSize = 0L
      var i = 0
      while (i + 128 <= dirBytes.size) {
        val nameLen = u16(dirBytes, i + 64)
        val type = dirBytes[i + 66].toInt() and 0xFF
        if (nameLen in 2..64) {
          val sb = StringBuilder()
          var k = 0
          while (k < nameLen - 2) { sb.append(u16(dirBytes, i + k).toChar()); k += 2 }
          val name = sb.toString()
          val start = i32(dirBytes, i + 116)
          val size = (i32(dirBytes, i + 120).toLong() and 0xFFFFFFFFL)
          when (type) {
            5 -> { rootStart = start; rootSize = size }   // root storage owns the mini-stream
            2 -> dir[name] = start to size                // a stream
          }
        }
        i += 128
      }
      val mini = if (rootStart >= 0) readChain(d, fat, rootStart, sectorSize, rootSize) ?: ByteArray(0) else ByteArray(0)
      return Cfb(d, sectorSize, fat, miniFat, mini, dir)
    }

    private fun readTable(d: ByteArray, sectors: List<Int>, sec: Int): IntArray? {
      if (sectors.isEmpty()) return null
      val per = sec / 4
      val out = IntArray(sectors.size * per) { -1 }
      for ((si, s) in sectors.withIndex()) {
        val off = sec + s * sec
        if (off < 0 || off + sec > d.size) continue
        for (k in 0 until per) out[si * per + k] = i32(d, off + k * 4)
      }
      return out
    }

    private fun readChainTable(d: ByteArray, fat: IntArray, start: Int, sec: Int): IntArray {
      val bytes = readChain(d, fat, start, sec) ?: return IntArray(0)
      return IntArray(bytes.size / 4) { i32(bytes, it * 4) }
    }

    private fun readChain(d: ByteArray, fat: IntArray, start: Int, sec: Int, limit: Long = Long.MAX_VALUE): ByteArray? {
      if (start < 0) return null
      val out = java.io.ByteArrayOutputStream()
      var s = start
      var guard = 0
      while (s >= 0 && guard++ < MAX_CHAIN && out.size() < limit) {
        val off = sec + s * sec
        if (off < 0 || off >= d.size) break
        val n = minOf(sec, d.size - off, (limit - out.size()).toInt().coerceAtLeast(0))
        if (n <= 0) break
        out.write(d, off, n)
        s = fat.getOrElse(s) { -1 }
      }
      return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun u16(d: ByteArray, i: Int) =
      if (i + 1 >= d.size) 0 else (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)

    private fun i32(d: ByteArray, i: Int) =
      if (i + 3 >= d.size) -1
      else (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8) or
        ((d[i + 2].toInt() and 0xFF) shl 16) or ((d[i + 3].toInt() and 0xFF) shl 24)
  }
}

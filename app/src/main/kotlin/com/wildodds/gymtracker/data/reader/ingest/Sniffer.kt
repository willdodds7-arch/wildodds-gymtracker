package com.wildodds.gymtracker.data.reader.ingest

/**
 * Decides what a pile of bytes actually is.
 *
 * Order is **magic bytes and structure first, extension second, MIME third**, and never extension
 * alone — because users rename things, share sheets get delivered as `.txt`, chat apps hand over
 * `document.bin`, and a `.csv` from a coach is as likely to be tab-separated as comma-separated.
 * The old extractor branched on extension only, which is why a renamed xlsx read back as mojibake.
 *
 * Pure and deterministic: same bytes in, same [SourceKind] out, on every device and in every locale.
 */
object Sniffer {

  private val ZIP = byteArrayOf(0x50, 0x4B)                       // PK
  private val OLE2 = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
  private val PDF = "%PDF".toByteArray(Charsets.US_ASCII)
  private val RTF = "{\\rtf".toByteArray(Charsets.US_ASCII)
  private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
  private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
  private val GIF = "GIF8".toByteArray(Charsets.US_ASCII)
  private val BMP = "BM".toByteArray(Charsets.US_ASCII)

  /**
   * @param zipEntryNames when the bytes are a zip, the entry names — that is the only way to tell
   *   xlsx from docx from ods from a `.numbers` bundle from a plain archive of text files.
   */
  fun sniff(
    bytes: ByteArray,
    fileName: String = "",
    mimeType: String = "",
    zipEntryNames: List<String> = emptyList()
  ): SourceKind {
    if (bytes.isEmpty()) return SourceKind.UNKNOWN

    // 1. Magic bytes — the only evidence that cannot be renamed away.
    if (bytes.startsWith(PDF)) return SourceKind.PDF_DIGITAL
    if (bytes.startsWith(RTF)) return SourceKind.RTF
    if (bytes.startsWith(PNG) || bytes.startsWith(JPEG) || bytes.startsWith(GIF) || bytes.startsWith(BMP))
      return SourceKind.IMAGE
    if (isHeic(bytes) || isWebp(bytes)) return SourceKind.IMAGE
    if (bytes.startsWith(OLE2)) return SourceKind.XLS   // BIFF8 .xls (and .doc, rejected downstream)

    // 2. Zip container — structure decides which of the five zip formats it is.
    if (bytes.startsWith(ZIP)) return sniffZip(zipEntryNames, fileName)

    // 3. Textual formats — decode a prefix and look at the shape, not the extension.
    val head = TextDecoder.decode(bytes, limit = 8192).text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
    if (head.isBlank()) return SourceKind.UNKNOWN
    if (head.startsWith("MIME-Version:") || head.contains("Content-Type: multipart/related"))
      return SourceKind.MHTML
    if (looksLikeHtml(head)) return SourceKind.HTML
    if (head.startsWith("<?xml") || (head.startsWith("<") && !looksLikeHtml(head))) return SourceKind.XML
    if (looksLikeJson(head)) return SourceKind.JSON
    if (looksLikeYaml(head)) return SourceKind.YAML
    if (looksLikeDelimited(head)) return SourceKind.DELIMITED
    if (looksLikeMarkdown(head)) return SourceKind.MARKDOWN

    // 4. Only now does the name get a vote, and only to split text-ish from text-ish.
    return when (fileName.substringAfterLast('.', "").lowercase()) {
      "csv", "tsv", "psv" -> SourceKind.DELIMITED
      "md", "markdown" -> SourceKind.MARKDOWN
      "json" -> SourceKind.JSON
      "yml", "yaml" -> SourceKind.YAML
      "html", "htm" -> SourceKind.HTML
      "xml" -> SourceKind.XML
      else -> if (mimeType.contains("csv")) SourceKind.DELIMITED else SourceKind.PLAIN_TEXT
    }
  }

  /**
   * Which zip is it? The entry names are unambiguous; the extension is not, since all five formats
   * are `PK` archives and users rename them freely.
   */
  fun sniffZip(entries: List<String>, fileName: String = ""): SourceKind {
    val e = entries.map { it.lowercase() }
    return when {
      e.any { it.startsWith("xl/") } -> SourceKind.XLSX
      e.any { it.startsWith("word/") } -> SourceKind.DOCX
      e.any { it == "mimetype" || it == "content.xml" } -> sniffOpenDocument(e)
      // Apple bundles: the payload is a proprietary IWA archive we cannot read, but the bundle also
      // carries a rendered preview we CAN read. See AppleBundleExtractor.
      e.any { it.startsWith("preview") || it.startsWith("quicklook/") } -> SourceKind.APPLE_BUNDLE
      e.any { it.startsWith("index/") && it.endsWith(".iwa") } -> SourceKind.APPLE_BUNDLE
      e.any { it.startsWith("ppt/") } -> SourceKind.UNKNOWN
      else -> when (fileName.substringAfterLast('.', "").lowercase()) {
        "xlsx", "xlsm", "xltx" -> SourceKind.XLSX
        "docx" -> SourceKind.DOCX
        "ods" -> SourceKind.ODS
        "odt" -> SourceKind.ODT
        "numbers", "pages", "key" -> SourceKind.APPLE_BUNDLE
        else -> SourceKind.ZIP
      }
    }
  }

  private fun sniffOpenDocument(entries: List<String>): SourceKind =
    // The `mimetype` entry's CONTENT is authoritative in ODF, but its name alone tells us little;
    // the styles give it away, and the caller re-checks the mimetype bytes in OpenDocumentExtractor.
    if (entries.any { it.contains("spreadsheet") }) SourceKind.ODS else SourceKind.ODT

  // ── shape tests ──────────────────────────────────────────────────────────────

  private fun looksLikeHtml(head: String): Boolean {
    val h = head.lowercase()
    return h.startsWith("<!doctype html") || h.startsWith("<html") ||
      (h.contains("<body") && h.contains("<")) || h.contains("<table") && h.contains("</td>")
  }

  private fun looksLikeJson(head: String): Boolean {
    val t = head.trimStart()
    if (!t.startsWith("{") && !t.startsWith("[")) return false
    // A markdown code fence starting with `{` is not JSON; require a quoted key or a nested brace.
    return t.contains("\":") || t.contains("\" :") || t.startsWith("[{") || t.startsWith("[\"")
  }

  private val YAML_KEY = Regex("""(?m)^[A-Za-z_][\w .-]{0,48}:(\s|$)""")

  private fun looksLikeYaml(head: String): Boolean {
    if (head.startsWith("---\n") || head.startsWith("---\r\n")) return true
    val lines = head.lineSequence().filter { it.isNotBlank() }.take(12).toList()
    if (lines.size < 2) return false
    val keyed = lines.count { YAML_KEY.containsMatchIn(it) || it.trimStart().startsWith("- ") }
    // Two thirds keyed AND no set-notation tokens, or every pasted program with "Day 1:" is "YAML".
    return keyed >= lines.size * 2 / 3 && lines.none { SET_TOKEN.containsMatchIn(it) }
  }

  /** A sets x reps token. Its presence is the strongest single signal that this is prose, not data. */
  private val SET_TOKEN = Regex("""(?i)\b\d{1,2}\s*[x×*]\s*\d{1,3}\b""")

  private fun looksLikeMarkdown(head: String): Boolean =
    Regex("""(?m)^\s{0,3}#{1,6}\s""").containsMatchIn(head) ||
      Regex("""(?m)^\s*\|.*\|\s*$""").containsMatchIn(head) ||
      Regex("""\*\*[^*\n]+\*\*""").containsMatchIn(head)

  /**
   * Delimited detection by **column-count variance**, not by counting commas on the first line.
   *
   * Counting the first line is how "Front Squat 6, 8, 10" — per-set reps — got read as a 3-column
   * CSV. A real table has a consistent column count across most of its lines under exactly one
   * delimiter; prose does not.
   */
  fun looksLikeDelimited(head: String): Boolean = inferDelimiter(head) != null

  /** The delimiter that best explains the text as a table, or null if none does. */
  fun inferDelimiter(text: String): Char? {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(40).toList()
    if (lines.size < 2) return null
    var best: Char? = null
    var bestScore = 0.0
    for (d in charArrayOf('\t', ';', '|', ',')) {
      val counts = lines.map { countOutsideQuotes(it, d) }
      if (counts.all { it == 0 }) continue
      val modal = counts.groupingBy { it }.eachCount().filterKeys { it > 0 }.maxByOrNull { it.value } ?: continue
      if (modal.key < 1) continue
      // Consistency x width: two consistent columns beat five wildly varying ones.
      val consistency = modal.value.toDouble() / lines.size
      val score = consistency * (1.0 + minOf(modal.key, 12) / 12.0)
      // Tabs win ties: a tab is almost never incidental punctuation, a comma very often is.
      if (consistency >= 0.6 && score > bestScore) { bestScore = score; best = d }
    }
    return best
  }

  private fun countOutsideQuotes(line: String, delim: Char): Int {
    var inQ = false
    var n = 0
    var i = 0
    while (i < line.length) {
      val c = line[i]
      when {
        c == '"' -> if (inQ && i + 1 < line.length && line[i + 1] == '"') i++ else inQ = !inQ
        c == delim && !inQ -> n++
      }
      i++
    }
    return n
  }

  // ── byte helpers ─────────────────────────────────────────────────────────────

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
  }

  /** ISO-BMFF box with an `ftyp` brand of heic/heix/hevc/mif1. */
  private fun isHeic(b: ByteArray): Boolean {
    if (b.size < 12) return false
    if (!(b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
          b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte())) return false
    val brand = String(b, 8, 4, Charsets.US_ASCII)
    return brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif")
  }

  private fun isWebp(b: ByteArray): Boolean =
    b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" &&
      String(b, 8, 4, Charsets.US_ASCII) == "WEBP"
}

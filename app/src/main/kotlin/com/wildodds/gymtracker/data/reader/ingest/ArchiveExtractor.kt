package com.wildodds.gymtracker.data.reader.ingest

/**
 * A `.zip` of anything else — the shape a coach's "here's the block" email attachment usually takes.
 *
 * Enumerates the archive, scores every entry as a program candidate, and extracts the winner. It
 * does NOT merge everything it finds: three spreadsheets in one zip are three programs, and
 * concatenating them produces a fourth that nobody wrote. [candidates] is exposed so the UI can
 * offer a chooser instead, which is the honest interface when the margin is small.
 *
 * Depth-limited: a zip inside a zip is read one level down, and no further. Anything deeper is
 * almost certainly not a training program and definitely not worth the decompression budget.
 */
object ArchiveExtractor : Extractor {

  override val handles = setOf(SourceKind.ZIP)

  /** One thing inside the archive that could be the program. */
  data class Candidate(val name: String, val kind: SourceKind, val bytes: ByteArray, val score: Double) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = name.hashCode()
  }

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val zip = input.archive ?: ZipReader.read(input.bytes, input.limits, input.progress)
    val found = candidates(zip, input.limits)
    if (found.isEmpty()) throw IngestError.Empty("That archive has nothing readable in it.")

    val best = found.first()
    val runnerUp = found.getOrNull(1)
    val warnings = ArrayList<IngestWarning>(zip.warnings)
    if (found.size > 1) {
      warnings += IngestWarning(
        "The archive holds ${found.size} readable files — “${best.name}” was used.",
        found.joinToString(", ") { it.name })
      if (runnerUp != null && best.score - runnerUp.score < 0.15) {
        warnings += IngestWarning(
          "“${runnerUp.name}” looked almost as much like the program — check this is the right one.")
      }
    }

    val registry = ExtractorRegistry.pureOnly()
    val extractor = registry.forKind(best.kind)
      ?: throw IngestError.Unsupported(best.kind.name, "Can't read “${best.name}” from that archive.")
    val doc = extractor.extract(input.copy(
      bytes = best.bytes, fileName = best.name, kind = best.kind, archive = null
    ))
    return doc.copy(
      title = doc.title ?: best.name.substringAfterLast('/').substringBeforeLast('.'),
      warnings = warnings + doc.warnings
    )
  }

  /**
   * Every readable entry, best first. The score is deliberately crude and explainable rather than
   * clever: format weight (a spreadsheet is more likely to be the program than a readme), a size
   * band (empty files and 40 MB files are both unlikely), and a penalty for the names that are
   * never the program.
   */
  fun candidates(zip: ZipReader.Archive, limits: IngestLimits, depth: Int = 0): List<Candidate> {
    val out = ArrayList<Candidate>()
    for (name in zip.names()) {
      val bytes = zip[name] ?: continue
      if (bytes.isEmpty()) continue
      val lower = name.lowercase()
      if (lower.startsWith("__macosx/") || lower.substringAfterLast('/').startsWith("."))
        continue

      val inner = if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        runCatching { ZipReader.read(bytes, limits) }.getOrNull() else null
      val kind = Sniffer.sniff(bytes, name, zipEntryNames = inner?.names().orEmpty())

      if (kind == SourceKind.ZIP) {
        // A nested plain archive: descend once, no further.
        if (depth < limits.maxZipDepth - 1 && inner != null) out += candidates(inner, limits, depth + 1)
        continue
      }
      if (kind == SourceKind.UNKNOWN) continue
      out += Candidate(name, kind, bytes, score(name, kind, bytes.size))
    }
    // Ties break on name so the choice is identical on every device.
    return out.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.name })
  }

  private val NEVER_THE_PROGRAM = Regex(
    """(?i)(readme|licen[cs]e|changelog|notes?\.txt|instructions|thumbs\.db|\.ds_store|template)""")

  private fun score(name: String, kind: SourceKind, size: Int): Double {
    var s = when (kind) {
      SourceKind.XLSX, SourceKind.XLS, SourceKind.ODS -> 1.0
      SourceKind.DELIMITED -> 0.9
      SourceKind.PDF_DIGITAL, SourceKind.DOCX, SourceKind.ODT -> 0.8
      SourceKind.MARKDOWN -> 0.7
      SourceKind.PLAIN_TEXT, SourceKind.RTF, SourceKind.HTML -> 0.6
      SourceKind.JSON, SourceKind.YAML, SourceKind.XML -> 0.5
      SourceKind.IMAGE -> 0.4
      else -> 0.2
    }
    if (NEVER_THE_PROGRAM.containsMatchIn(name)) s -= 0.35
    // Depth penalty: the program is usually at the top of the archive, not four folders down.
    s -= 0.05 * name.count { it == '/' }
    // A file under 64 bytes has no program in it; one over 32 MB is a scan or a video.
    if (size < 64) s -= 0.4
    if (size > 32 * 1024 * 1024) s -= 0.2
    return s
  }
}

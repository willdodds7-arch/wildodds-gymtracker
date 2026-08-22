package com.wildodds.gymtracker.data.reader.ingest

/**
 * `.rtf` — strip the control words, keep the structure.
 *
 * "Keep the structure" is what separates this from a regex that deletes everything between `\` and
 * a space: `\par` is a line break and `\cell` is a column break, and dropping them turns a whole
 * program into one 4,000-character line that no downstream stage can segment.
 *
 * Also handled because real RTF from Word and TextEdit contains all of it: `\'hh` hex escapes in the
 * document's codepage, `\uN` unicode escapes with their ASCII fallback character, `{\*\...}`
 * ignorable destinations (which hold fonts, colours and revision data, none of it content), and
 * nested groups.
 */
object RtfExtractor : Extractor {

  override val handles = setOf(SourceKind.RTF)

  override fun extract(input: ExtractInput): ExtractedDocument {
    input.cancelCheck()
    val raw = String(input.bytes, Charsets.ISO_8859_1)   // RTF is 7-bit ASCII plus escapes
    val text = strip(raw)
    if (text.isBlank()) throw IngestError.Empty("That RTF has no text in it.")
    return ExtractedDocument(text = text, sourceKind = SourceKind.RTF)
  }

  /** Destinations whose contents are metadata, never document text. */
  private val SKIP_DESTINATIONS = setOf(
    "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "themedata",
    "colorschememapping", "latentstyles", "datastore", "generator", "listtable",
    "listoverridetable", "rsidtbl", "xmlnstbl", "mmathPr", "fldinst"
  )

  fun strip(rtf: String): String {
    val out = StringBuilder()
    var i = 0
    var depth = 0
    // Depth at which we started skipping an ignorable destination; -1 = not skipping.
    var skipDepth = -1
    // How many characters to swallow after a \uN escape (its ASCII fallback).
    var skipChars = 0

    fun emit(c: Char) { if (skipDepth < 0) { if (skipChars > 0) skipChars-- else out.append(c) } }

    while (i < rtf.length) {
      when (val c = rtf[i]) {
        '{' -> { depth++; i++ }
        '}' -> { depth--; if (skipDepth >= 0 && depth < skipDepth) skipDepth = -1; i++ }
        '\\' -> {
          i++
          if (i >= rtf.length) break
          val n = rtf[i]
          when {
            // Escaped literals.
            n == '\\' || n == '{' || n == '}' -> { emit(n); i++ }
            n == '\'' -> {                                    // \'hh — a byte in the codepage
              val hex = rtf.substring(i + 1, minOf(i + 3, rtf.length))
              hex.toIntOrNull(16)?.let { emit(it.toChar()) }
              i += 3
            }
            n == '~' -> { emit(' '); i++ }                     // non-breaking space
            n == '-' || n == '_' -> i++                        // optional/non-breaking hyphen
            n == '*' -> {                                      // {\*\dest ...} — ignorable
              if (skipDepth < 0) skipDepth = depth
              i++
            }
            n == '\n' || n == '\r' -> { emit('\n'); i++ }
            n.isLetter() -> {
              val start = i
              while (i < rtf.length && rtf[i].isLetter()) i++
              val word = rtf.substring(start, i)
              var param: Int? = null
              if (i < rtf.length && (rtf[i] == '-' || rtf[i].isDigit())) {
                val ps = i
                if (rtf[i] == '-') i++
                while (i < rtf.length && rtf[i].isDigit()) i++
                param = rtf.substring(ps, i).toIntOrNull()
              }
              if (i < rtf.length && rtf[i] == ' ') i++         // the delimiting space is consumed

              when {
                word in SKIP_DESTINATIONS -> if (skipDepth < 0) skipDepth = depth
                word == "u" && param != null -> {
                  // \uN is followed by a fallback character (or several, per \ucN) to discard.
                  emit(param.let { if (it < 0) it + 65536 else it }.toChar())
                  skipChars = 1
                }
                word == "uc" && param != null -> skipChars = 0
                word == "par" || word == "line" || word == "sect" || word == "page" -> emit('\n')
                // A row ends the line; a cell ends the column.
                word == "row" || word == "nestrow" -> emit('\n')
                word == "cell" || word == "nestcell" -> emit('\t')
                word == "tab" -> emit('\t')
              }
            }
            else -> i++
          }
        }
        '\n', '\r' -> i++                                       // raw newlines are formatting only
        else -> { emit(c); i++ }
      }
    }

    return out.lineSequence()
      .map { it.trim().trimEnd('\t') }
      .joinToString("\n")
      .replace(Regex("\n{3,}"), "\n\n")
      .trim()
  }
}

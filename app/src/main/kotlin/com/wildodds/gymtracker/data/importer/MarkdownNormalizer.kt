package com.wildodds.gymtracker.data.importer

/**
 * Turns the markdown people actually paste into the flat text [TextProgramImporter] understands.
 *
 * Most pasted programs now come from a chat assistant, which means headings (`## Day 1`), bold day
 * names (`**Day 1 — Squat**`), bulleted or numbered exercise lists, em/en dashes, and sometimes a
 * whole markdown table. Without this pass the importer sees `### Monday — Upper Body` as an
 * *exercise* rather than a session, and the entire program collapses into one nameless day.
 *
 * Deliberately a separate, pure step: normalising is a text problem with obvious right answers,
 * and keeping it out of the parser means both stay testable.
 *
 * Nothing is invented — this only removes formatting and reflows tables into the equivalent
 * one-line-per-exercise form.
 */
object MarkdownNormalizer {

  /** `#`, `##`, … at the start of a line. */
  private val heading = Regex("""^\s{0,3}#{1,6}\s*""")

  /** `- `, `* `, `+ `, `1. `, `1) ` — but NOT `*Bench*` or a bare `-` used as a dash. */
  private val bullet = Regex("""^\s{0,6}(?:[-*+]\s+|\d{1,2}[.)]\s+)""")

  /** `**bold**`, `__bold__`, `*italic*`, `_italic_`, and stray leftovers. */
  private val emphasis = Regex("""(\*\*|__|\*|_)""")

  /** `---`, `***`, `___` on their own line. */
  private val horizontalRule = Regex("""^\s*([-*_])\1{2,}\s*$""")

  private val blockquote = Regex("""^\s*>\s?""")

  /** A markdown table separator: `|---|:--:|` and friends. */
  private val tableSeparator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

  /** Column headings we know how to use. */
  private val nameHeaders = listOf("exercise", "movement", "lift")
  private val setHeaders = listOf("sets", "set")
  private val repHeaders = listOf("reps", "rep", "repetitions")
  private val loadHeaders = listOf("intensity", "load", "weight", "%", "percent", "rpe")
  private val restHeaders = listOf("rest")

  /** Cell values that mean "nothing here": em dash, en dash, hyphen, n/a. */
  private fun isEmptyCell(v: String) =
    v.isBlank() || v.trim() in setOf("-", "--", "—", "–", "n/a", "N/A", "na", "—", "‒")

  fun normalize(raw: String): String {
    val out = mutableListOf<String>()
    val lines = raw.replace("\r\n", "\n").split("\n")

    var i = 0
    while (i < lines.size) {
      val line = lines[i]

      // A table has to be handled as a block, not line by line.
      if (looksLikeTableRow(line) && i + 1 < lines.size && tableSeparator.matches(lines[i + 1])) {
        val (rows, consumed) = readTable(lines, i)
        out += rows
        i += consumed
        continue
      }

      cleanLine(line)?.let { out += it }
      i++
    }
    return out.joinToString("\n")
  }

  /** Strip formatting from one line. Returns null for lines that carry no content. */
  private fun cleanLine(raw: String): String? {
    var s = raw

    if (horizontalRule.matches(s)) return null
    s = s.replace(blockquote, "")
    s = s.replace(heading, "")
    s = s.replace(bullet, "")
    s = s.replace(emphasis, "")
    s = normalizeDashes(s)
    // A table row that isn't part of a recognised table still shouldn't keep its pipes.
    if (s.contains('|')) {
      if (tableSeparator.matches(s)) return null
      s = s.trim().trim('|').split('|').joinToString(" ") { it.trim() }
    }
    s = s.trim()

    return s.ifBlank { null }
  }

  /**
   * Em dash, en dash and the various minus signs all become a plain hyphen so that `Day 1 — Squat`
   * and `Day 1 - Squat` parse identically, and `8–12` reads as a rep range.
   */
  internal fun normalizeDashes(s: String): String =
    s.replace('—', '-')   // em dash
      .replace('–', '-')  // en dash
      .replace('−', '-')  // minus sign
      .replace('‒', '-')  // figure dash
      .replace('‐', '-')  // hyphen
      .replace(' ', ' ')  // non-breaking space

  private fun looksLikeTableRow(line: String) = line.trim().startsWith("|") && line.count { it == '|' } >= 2

  private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { normalizeDashes(it.replace(emphasis, "")).trim() }

  /**
   * Read a markdown table and reflow each data row into one exercise line the parser can read:
   * `Back Squat 3x5 @ 85% TM`. Returns the emitted lines and how many source lines were consumed.
   *
   * Falls back to joining the cells with spaces when the header doesn't name the columns we need,
   * so an unusual table still produces something rather than nothing.
   */
  private fun readTable(lines: List<String>, start: Int): Pair<List<String>, Int> {
    val header = splitRow(lines[start]).map { it.lowercase() }
    val nameCol = header.indexOfFirst { h -> nameHeaders.any { h.contains(it) } }
    val setCol = header.indexOfFirst { h -> setHeaders.any { h == it || h.startsWith("$it ") } }
    val repCol = header.indexOfFirst { h -> repHeaders.any { h == it || h.startsWith("$it ") } }
    val loadCol = header.indexOfFirst { h -> loadHeaders.any { h.contains(it) } }
    val restCol = header.indexOfFirst { h -> restHeaders.any { h.contains(it) } }

    val emitted = mutableListOf<String>()
    var i = start + 2  // skip the header and the |---| separator
    while (i < lines.size && looksLikeTableRow(lines[i])) {
      val cells = splitRow(lines[i])
      if (tableSeparator.matches(lines[i])) { i++; continue }

      val name = cells.getOrNull(nameCol)?.takeIf { !isEmptyCell(it) }
      if (name == null) {
        // No recognisable name column — keep the row's text rather than silently dropping it.
        val joined = cells.filterNot { isEmptyCell(it) }.joinToString(" ")
        if (joined.isNotBlank()) emitted += joined
        i++
        continue
      }

      val sets = cells.getOrNull(setCol)?.takeIf { !isEmptyCell(it) }
      val reps = cells.getOrNull(repCol)?.takeIf { !isEmptyCell(it) }
      val load = cells.getOrNull(loadCol)?.takeIf { !isEmptyCell(it) }
      val rest = cells.getOrNull(restCol)?.takeIf { !isEmptyCell(it) }

      emitted += buildString {
        append(name)
        if (sets != null && reps != null) append(" ${sets}x$reps")
        else if (reps != null) append(" $reps")
        if (load != null) append(" @ $load")
        if (rest != null) append(" rest $rest")
      }
      i++
    }
    return emitted to (i - start)
  }

  // ── Prose ──────────────────────────────────────────────────────────────────

  /**
   * True when a line is explanatory text rather than an exercise.
   *
   * A generated program is full of these — "Rest 2-3 minutes between working sets.", "This block
   * focuses on…" — and turning them into unparseable exercises is worse than dropping them.
   *
   * The test is deliberately conservative, because a false positive silently deletes something the
   * user wrote: a line is prose only when it carries NO set notation and either reads like a
   * sentence (ends in a full stop) or is longer than any real exercise name.
   */
  fun isProse(line: String, hasSetNotation: Boolean): Boolean {
    if (hasSetNotation) return false
    val t = line.trim()
    if (t.isEmpty()) return true
    val words = t.split(Regex("""\s+""")).size
    val endsLikeSentence = t.endsWith(".") || t.endsWith("!") || t.endsWith(":") && words > MAX_NAME_WORDS
    return words > MAX_NAME_WORDS || endsLikeSentence
  }

  /** Longest plausible exercise name, in words — "Single Arm Dumbbell Overhead Press" is five. */
  private const val MAX_NAME_WORDS = 6
}

package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary
import java.text.Normalizer

/**
 * Turns the text people actually paste into the flat, regular text the segmenter understands.
 *
 * Was `MarkdownNormalizer`, and the rename is the point: markdown was only ever the most visible
 * source of noise. The rest arrives from PDFs (page furniture repeated on every page, hard-wrapped
 * lines), phones (smart quotes, non-breaking spaces, emoji), spreadsheets (tab-aligned pseudo
 * tables), assistants (numbered-list renumbering artefacts) and non-English keyboards (full-width
 * digits and multiplication signs).
 *
 * Every transform here removes FORMATTING or restores STRUCTURE. None of them invents content, and
 * none of them changes a number — `TextNormalizerTest` asserts that the digits in equal the digits
 * out for every fixture.
 *
 * Pure, and locale-independent: NFKC normalisation and `Locale.ROOT` casing throughout, so the same
 * paste produces the same lines on a Turkish phone as on an English one.
 */
object TextNormalizer {

  /** `#`, `##`, … at the start of a line. */
  private val heading = Regex("""^\s{0,3}#{1,6}\s*""")

  /** `- `, `* `, `+ `, `1. `, `1) `, `•`, `‣`, `◦`, `→` — but NOT `*Bench*` or a bare dash. */
  private val bullet = Regex("""^\s{0,6}(?:[-*+•‣◦▪▫●○·–—→»]\s+|\d{1,2}[.)]\s+)""")

  /** `**bold**`, `__bold__`, `*italic*`, `_italic_`, and stray leftovers. */
  private val emphasis = Regex("""(\*\*|__|\*|_)""")

  /** `---`, `***`, `___`, `===` on their own line. */
  private val horizontalRule = Regex("""^\s*([-*_=])\1{2,}\s*$""")

  private val blockquote = Regex("""^\s*>\s?""")

  /** A markdown table separator: `|---|:--:|` and friends. */
  private val tableSeparator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

  /** Column headings we know how to use, read from the shared vocabulary. */
  private val vocab = ReaderVocabulary

  /** Cell values that mean "nothing here": em dash, en dash, hyphen, n/a. */
  private fun isEmptyCell(v: String) =
    v.isBlank() || v.trim().lowercase(java.util.Locale.ROOT) in setOf("-", "--", "—", "–", "n/a", "na", "none", "‒", "x")

  // ── entry point ──────────────────────────────────────────────────────────────

  fun normalize(raw: String): String {
    val unicode = normalizeUnicode(raw)
    val out = mutableListOf<String>()
    val lines = unicode.split("\n")

    var i = 0
    while (i < lines.size) {
      val line = lines[i]

      // A markdown table has to be handled as a block, not line by line.
      if (looksLikeTableRow(line) && i + 1 < lines.size && tableSeparator.matches(lines[i + 1])) {
        val (rows, consumed) = readTable(lines, i)
        out += rows
        i += consumed
        continue
      }

      // A tab-aligned pseudo-table — the shape you get from pasting a spreadsheet selection —
      // reads as one exercise per line already; the tabs just need to become spaces in the right
      // places. Handled by [cleanLine] via the pipe/tab collapse.
      cleanLine(line)?.let { out += it }
      i++
    }

    val dropped = dropRepeatedPageFurniture(out)
    return rejoinHardWraps(dropped).joinToString("\n")
  }

  // ── unicode ──────────────────────────────────────────────────────────────────

  /**
   * NFKC does most of the work in one pass: full-width digits (`３ｘ８`) become ASCII, ligatures
   * decompose, and superscripts flatten. What it does NOT do is unify the dash family, the quote
   * family, or the space family, so those are explicit.
   *
   * Emoji are stripped but the ones that carry meaning in a training context are kept first as a
   * word — 🔥 really does mark a finisher in programs people paste, and deleting it silently loses
   * the coach's emphasis.
   */
  fun normalizeUnicode(raw: String): String {
    var s = raw.replace("\r\n", "\n").replace('\r', '\n')
    s = Normalizer.normalize(s, Normalizer.Form.NFKC)
    s = s.replace('×', 'x')          // × multiplication sign
    s = normalizeDashes(s)
    s = s.replace('‘', '\'').replace('’', '\'')   // smart single quotes
    s = s.replace('“', '"').replace('”', '"')     // smart double quotes
    s = s.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')  // NBSPs
    s = s.replace("​", "").replace("‌", "").replace("‍", "")     // zero-width
    s = s.replace("﻿", "").replace("⁠", "")                           // BOM, word joiner
    s = replaceMeaningfulEmoji(s)
    s = stripRemainingEmoji(s)
    return s
  }

  /**
   * Em dash, en dash and the various minus signs all become a plain hyphen so that `Day 1 — Squat`
   * and `Day 1 - Squat` parse identically, and `8–12` reads as a rep range.
   */
  fun normalizeDashes(s: String): String =
    s.replace('—', '-')   // em dash
      .replace('–', '-')  // en dash
      .replace('−', '-')  // minus sign
      .replace('‒', '-')  // figure dash
      .replace('‐', '-')  // hyphen
      .replace('‑', '-')  // non-breaking hyphen

  /** Emoji that carry a training meaning become the word they stand for, before the rest are cut. */
  private val MEANINGFUL_EMOJI = mapOf(
    "🔥" to " finisher ",   // 🔥
    "⏱" to " time ",             // ⏱
    "⚡" to " explosive ",        // ⚡
    "💥" to " max ",        // 💥
    "🛌" to " rest ",       // 🛌
    "🧘" to " mobility "    // 🧘
  )

  private fun replaceMeaningfulEmoji(s: String): String {
    var out = s
    for ((emoji, word) in MEANINGFUL_EMOJI) if (out.contains(emoji)) out = out.replace(emoji, word)
    return out
  }

  /**
   * Everything left in the emoji and pictograph blocks goes. Deliberately block-based rather than a
   * curated list: a decorative emoji in an exercise name is noise the resolver has to fight, and
   * there is no emoji whose loss changes what was prescribed.
   */
  private fun stripRemainingEmoji(s: String): String {
    if (s.none { it.code >= 0x2190 }) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val cp = s.codePointAt(i)
      val width = Character.charCount(cp)
      val isEmoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
        cp in 0x2190..0x21FF || cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F1E6..0x1F1FF
      if (!isEmoji) sb.appendCodePoint(cp)
      i += width
    }
    return sb.toString().replace(Regex(" {2,}"), " ")
  }

  // ── per-line cleaning ────────────────────────────────────────────────────────

  /** Strip formatting from one line. Returns null for lines that carry no content. */
  private fun cleanLine(raw: String): String? {
    var s = raw

    if (horizontalRule.matches(s)) return null
    s = s.replace(blockquote, "")
    s = s.replace(heading, "")
    s = s.replace(bullet, "")
    s = s.replace(emphasis, "")
    // A table row that isn't part of a recognised table still shouldn't keep its pipes.
    if (s.contains('|')) {
      if (tableSeparator.matches(s)) return null
      s = s.trim().trim('|').split('|').joinToString(" ") { it.trim() }
    }
    // Leading whitespace is KEPT. Indentation is the clearest signal a document gives that a line
    // continues the one above it ("  set 2: 5 @ 80%"), and trimming it here destroyed that evidence
    // before the segmenter ever saw it.
    s = s.trimEnd()
    return s.ifBlank { null }
  }

  private fun looksLikeTableRow(line: String) = line.trim().startsWith("|") && line.count { it == '|' } >= 2

  private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { normalizeDashes(it.replace(emphasis, "")).trim() }

  /**
   * Read a markdown table and reflow each data row into one exercise line the parser can read:
   * `Back Squat 3x5 @ 85% TM`. Returns the emitted lines and how many source lines were consumed.
   *
   * Column roles come from the SHARED vocabulary, so a header name added for the CSV path is
   * understood here too without a second edit.
   */
  private fun readTable(lines: List<String>, start: Int): Pair<List<String>, Int> {
    val header = splitRow(lines[start]).map { vocab.lower(it) }
    fun col(role: String): Int =
      header.indexOfFirst { h -> vocab.columnRoleHints[role].orEmpty().any { h == it || h.startsWith("$it ") || h.contains(it) } }

    val nameCol = col("exercise")
    val setCol = col("sets")
    val repCol = col("reps")
    val loadCol = if (col("load") >= 0) col("load") else col("percent")
    val restCol = col("rest")
    val rpeCol = col("rpe")
    val notesCol = col("notes")

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
      val rpe = cells.getOrNull(rpeCol)?.takeIf { !isEmptyCell(it) }
      val notes = cells.getOrNull(notesCol)?.takeIf { !isEmptyCell(it) }

      emitted += buildString {
        append(name)
        if (sets != null && reps != null) append(" ${sets}x$reps")
        else if (reps != null) append(" $reps")
        else if (sets != null) append(" $sets sets")
        if (load != null) append(" @ $load")
        if (rpe != null) append(if (rpe.contains("rir", true)) " $rpe" else " @RPE$rpe")
        if (rest != null) append(" rest $rest")
        if (notes != null) append(" — $notes")
      }
      i++
    }
    return emitted to (i - start)
  }

  // ── page furniture ───────────────────────────────────────────────────────────

  /**
   * A header or footer repeated across PDF pages ("Smith Strength — Block 2", "Page 3 of 12") is
   * not part of the program. Detected by CROSS-PAGE REPETITION rather than by position, because a
   * coach's footer is as likely to be at the top of the page as the bottom.
   *
   * A repeated line that carries a prescription is never dropped: a program legitimately contains
   * "3x8" many times, and losing those would be far worse than keeping a header.
   */
  fun dropRepeatedPageFurniture(lines: List<String>, minRepeats: Int = 3): List<String> {
    if (lines.size < minRepeats * 6) return lines
    val counts = lines.map { it.trim() }.filter { it.length in 3..90 }.groupingBy { it }.eachCount()
    val furniture = counts.filter { (line, n) ->
      n >= minRepeats && !SetNotationParser.hasPrescription(line)
    }.keys
    if (furniture.isEmpty()) return lines
    // "Page 3 of 12" differs every page, so the page-number FORM is matched as well as exact repeats.
    val pageNumber = Regex("""(?i)^\s*(page\s*)?\d{1,3}(\s*(of|/)\s*\d{1,3})?\s*$""")
    return lines.filterNot { it.trim() in furniture || pageNumber.matches(it) }
  }

  // ── hard wraps ───────────────────────────────────────────────────────────────

  /**
   * A PDF or a narrow email wraps long lines, so "Barbell Bench Press 3x8 @ 80kg, pause on" and
   * "the chest" arrive as two lines. Rejoined only when the continuation is CLEARLY part of the
   * previous exercise: it starts lowercase, carries no prescription of its own, and is not a header.
   *
   * Deliberately conservative — a false join merges two exercises into one, which is far more
   * damaging than leaving a stray fragment for the segmenter to classify as a note.
   */
  fun rejoinHardWraps(lines: List<String>): List<String> {
    if (lines.size < 2) return lines
    val out = ArrayList<String>(lines.size)
    for (line in lines) {
      val prev = out.lastOrNull()
      if (prev != null && isContinuation(prev, line)) {
        out[out.lastIndex] = "$prev ${line.trim()}"
      } else {
        out += line
      }
    }
    return out
  }

  private fun isContinuation(prev: String, line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty() || t.length > 60) return false
    // A line indented MORE than the one above it is a structured child, not a wrapped tail.
    // Joining "Back Squat" to its indented "set 1: 5 @ 70%" destroys the only thing that said the
    // second line belonged to the first.
    if (indentOf(line) > indentOf(prev)) return false
    val first = t.first()
    // A continuation starts mid-sentence: lowercase, or a closing bracket/quote.
    if (!(first.isLowerCase() || first in setOf(')', ']', '"', '\''))) return false
    // Never join something that prescribes anything — that is a new exercise.
    if (SetNotationParser.hasPrescription(t)) return false
    // The previous line has to look unfinished: it ends without terminal punctuation.
    val p = prev.trimEnd()
    if (p.isEmpty() || p.last() in setOf('.', '!', '?', ':')) return false
    return true
  }

  private fun indentOf(line: String): Int {
    var n = 0
    for (c in line) { if (c == ' ') n += 1 else if (c == '\t') n += 4 else break }
    return n
  }

  // ── prose ────────────────────────────────────────────────────────────────────

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

/**
 * The old name, kept as an alias so the change is reviewable as a rename rather than as a rewrite
 * of every call site. New code should use [TextNormalizer].
 */
@Deprecated("Renamed to TextNormalizer", ReplaceWith("TextNormalizer"))
typealias MarkdownNormalizer = TextNormalizer

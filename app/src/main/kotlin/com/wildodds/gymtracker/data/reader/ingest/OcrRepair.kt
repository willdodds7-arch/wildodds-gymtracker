package com.wildodds.gymtracker.data.reader.ingest

/**
 * Turns raw OCR output into something the notation parser can read — **without** correcting what it
 * cannot verify.
 *
 * Feeding raw OCR straight into the parser is where naive implementations die. Recognisers return
 * words in whatever order they scanned them, with no columns, with `0` and `O` interchanged, and
 * with a photo of a printout split across two text blocks that belong on one line. Every one of
 * those produces a *plausible* program that is not the one in the picture, which is the single worst
 * failure mode this subsystem can have.
 *
 * So the rules here are narrow on purpose:
 *  - Confusable characters are repaired **only inside numeric contexts** — `3xO` becomes `3x0`, but
 *    the word `Row` is never touched. A repair that can change an exercise name is not a repair.
 *  - Lines are merged by **baseline clustering**, using geometry, not by guessing from the text.
 *  - Columns are found from an **x-gap histogram** over the whole page, so a table stays a table.
 *  - Per-line confidence is carried through and multiplied into slot confidence downstream, so a
 *    0.6-confidence line can never produce a 0.90 slot.
 *
 * Pure: it takes [OcrLine]s and returns text. The recogniser that produces them is Android's problem.
 */
object OcrRepair {

  data class Repaired(
    val text: String,
    /** Parallel to [text]'s lines. */
    val lineConfidence: List<Double>,
    val meanConfidence: Double,
    val alphanumericGlyphs: Int,
    val columnCount: Int
  )

  /** Below this mean confidence the page is not worth importing, and we say so rather than guess. */
  const val MIN_MEAN_CONFIDENCE = 0.55

  /** Fewer legible glyphs than this and there is no program in the picture. */
  const val MIN_GLYPHS = 40

  /**
   * @param lineHeightHint typical text height in pixels; used to decide when two lines are really
   *   one. Derived from the lines themselves when not supplied.
   */
  fun repair(lines: List<OcrLine>, lineHeightHint: Int? = null): Repaired {
    if (lines.isEmpty()) return Repaired("", emptyList(), 0.0, 0, 0)

    val height = lineHeightHint ?: medianHeight(lines)
    val rows = clusterByBaseline(lines, height)
    val columns = detectColumns(lines, height)

    val outLines = ArrayList<String>(rows.size)
    val outConf = ArrayList<Double>(rows.size)
    for (row in rows) {
      val sorted = row.sortedBy { it.left }
      val text = if (columns.size >= 2) renderColumns(sorted, columns) else sorted.joinToString(" ") { it.text }
      val repaired = repairNumerics(text)
      if (repaired.isNotBlank()) {
        outLines += repaired
        // A line is only as trustworthy as its least-trustworthy fragment.
        outConf += row.minOf { it.confidence }
      }
    }

    val glyphs = outLines.sumOf { l -> l.count { it.isLetterOrDigit() } }
    val mean = if (outConf.isEmpty()) 0.0 else outConf.average()
    return Repaired(outLines.joinToString("\n"), outConf, mean, glyphs, columns.size)
  }

  // ── line reconstruction ──────────────────────────────────────────────────────

  private fun medianHeight(lines: List<OcrLine>): Int {
    val hs = lines.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()
    return hs[hs.size / 2]
  }

  /**
   * Group fragments whose baselines are within half a line height. A recogniser routinely splits
   * "Back Squat" and "3x5 @ 80kg" into separate blocks on the same physical line; without this they
   * become two lines and the exercise loses its prescription.
   */
  fun clusterByBaseline(lines: List<OcrLine>, lineHeight: Int): List<List<OcrLine>> {
    val tolerance = (lineHeight * 0.5).toInt().coerceAtLeast(2)
    val sorted = lines.sortedWith(compareBy({ it.baselineY }, { it.left }))
    val rows = ArrayList<MutableList<OcrLine>>()
    for (l in sorted) {
      val row = rows.lastOrNull()
      if (row != null && kotlin.math.abs(row.last().baselineY - l.baselineY) <= tolerance) row.add(l)
      else rows.add(mutableListOf(l))
    }
    return rows
  }

  /**
   * Column boundaries from an x-gap histogram: project every fragment's horizontal extent onto the
   * x axis, then find the runs of x with no ink at all that are wider than a couple of characters.
   * Those gutters are the columns. This is what keeps a four-column printed program readable as four
   * columns rather than as a run-on sentence.
   */
  fun detectColumns(lines: List<OcrLine>, lineHeight: Int): List<IntRange> {
    if (lines.size < 6) return emptyList()
    val minX = lines.minOf { it.left }
    val maxX = lines.maxOf { it.right }
    if (maxX <= minX) return emptyList()
    val width = maxX - minX
    val occupied = BooleanArray(width + 1)
    for (l in lines) for (x in (l.left - minX)..(l.right - minX).coerceAtMost(width)) {
      if (x >= 0) occupied[x] = true
    }
    // A gutter has to be wider than an inter-word space, or every space becomes a column boundary.
    val minGutter = (lineHeight * 1.5).toInt().coerceAtLeast(8)

    val cols = ArrayList<IntRange>()
    var start = -1
    var gapStart = -1
    for (x in occupied.indices) {
      if (occupied[x]) {
        if (start < 0) start = x
        if (gapStart >= 0 && x - gapStart >= minGutter) {
          cols += (minX + start)..(minX + gapStart - 1)
          start = x
        }
        gapStart = -1
      } else if (gapStart < 0) gapStart = x
    }
    if (start >= 0) cols += (minX + start)..maxX
    // One "column" spanning the page is not a table.
    return if (cols.size >= 2) cols else emptyList()
  }

  /** Place each fragment in its column and join with tabs, so the grid path can read it. */
  private fun renderColumns(row: List<OcrLine>, columns: List<IntRange>): String {
    val cells = Array(columns.size) { StringBuilder() }
    for (l in row) {
      val centre = (l.left + l.right) / 2
      val idx = columns.indexOfFirst { centre in it }
        .takeIf { it >= 0 }
        ?: columns.indices.minByOrNull { i ->
          kotlin.math.min(kotlin.math.abs(columns[i].first - centre), kotlin.math.abs(columns[i].last - centre))
        } ?: 0
      if (cells[idx].isNotEmpty()) cells[idx].append(' ')
      cells[idx].append(l.text)
    }
    return cells.joinToString("\t") { it.toString().trim() }.trimEnd('\t')
  }

  // ── confusable repair, numeric contexts only ─────────────────────────────────

  /**
   * A token is a numeric context when it already contains a digit, or when it sits where a number
   * must be (immediately around an `x`, a `@`, a `%`, or a unit). Only inside such a token are
   * `O→0`, `l/I→1`, `S→5`, `B→8` and `Z→2` applied.
   *
   * The asymmetry is deliberate: turning `1O` into `10` fixes a number; turning `Row` into `R0w`
   * destroys an exercise name, and the second mistake is far more expensive than the first.
   */
  fun repairNumerics(line: String): String {
    if (line.isBlank()) return line
    val out = StringBuilder(line.length)
    var i = 0
    while (i < line.length) {
      val c = line[i]
      if (c.isWhitespace()) { out.append(c); i++; continue }
      val start = i
      while (i < line.length && !line[i].isWhitespace()) i++
      out.append(repairToken(line.substring(start, i)))
    }
    return unifyMultiplication(out.toString())
  }

  private val UNIT_SUFFIX = Regex("""(?i)(kg|kgs|lb|lbs|#|s|sec|secs|m|min|mins|km|cal|%|reps?)$""")

  /** Characters that can appear in a numeric token without making it a word: the operators. */
  private val NUMERIC_OPERATORS = setOf('x', 'X', '*', '/', '+', '-', '.', ',', ':', '@', '%', '(', ')')

  /**
   * Repairs one token, and only when the token is UNAMBIGUOUSLY numeric.
   *
   * "Unambiguously" is doing real work here. An earlier version accepted any short token ending in
   * a unit-like letter, which meant "Press" — five letters ending in `s` — was treated as a number
   * and came back as "Pre5". Turning a movement name into gibberish is far worse than leaving a
   * misread digit alone, so the test is now: strip a trailing unit, and every remaining character
   * must be a digit, a confusable, or a numeric operator. "3xlO" and "8O" qualify; "Press", "Row"
   * and "T2B" do not.
   */
  private fun repairToken(token: String): String {
    if (token.isEmpty()) return token
    // Leave a trailing unit alone: the `S` in "5S" is seconds, not a five.
    val unit = UNIT_SUFFIX.find(token)?.value.orEmpty()
    val body = token.dropLast(unit.length)
    if (body.isEmpty()) return token

    val allNumeric = body.all { it.isDigit() || it in CONFUSABLE || it in NUMERIC_OPERATORS }
    if (!allNumeric) return token
    // A token of nothing but operators ("+/-") is punctuation, not a number.
    if (body.none { it.isDigit() || it in CONFUSABLE }) return token
    // With no real digit anywhere, a single letter is not enough evidence to call this a number:
    // "Bs" would become "8s". "lO" — two confusables in a row — still is.
    if (body.none { it.isDigit() } && body.length < 2) return token
    // With no real digit, only the l/I/O shapes count: "DB", "BB", "SS" are gym abbreviations
    // (dumbbell, barbell, superset) and came back as "08", "88" and "55".
    if (body.none { it.isDigit() } && !body.all { it in "lIOo" || it in NUMERIC_OPERATORS }) return token

    val fixed = buildString(body.length) {
      for (ch in body) append(CONFUSABLE[ch] ?: ch)
    }
    return fixed + unit
  }

  /** Only characters whose *numeric* reading is unambiguous. `G`/`6` and `q`/`9` are not here. */
  private val CONFUSABLE: Map<Char, Char> = mapOf(
    'O' to '0', 'o' to '0', 'Q' to '0', 'D' to '0',
    'l' to '1', 'I' to '1', '|' to '1', 'i' to '1',
    'S' to '5', 's' to '5',
    'B' to '8',
    'Z' to '2', 'z' to '2'
  )

  /**
   * `×`, `X`, `*` and a lone `x` between digits all mean the same operator. Unifying them to a
   * lowercase `x` here is safe and saves the grammar four alternations — but ONLY between digits,
   * because an uppercase `X` in a tempo (`31X0`) means something else entirely and must survive.
   */
  fun unifyMultiplication(s: String): String =
    Regex("""(?<=\d)\s*[×*]\s*(?=\d)""").replace(s, "x")

}

package com.wildodds.gymtracker.data.importer.layout

import com.wildodds.gymtracker.data.importer.ExerciseRef
import com.wildodds.gymtracker.data.importer.SessionDraft
import com.wildodds.gymtracker.data.importer.SessionType
import com.wildodds.gymtracker.data.importer.SetNotationParser
import com.wildodds.gymtracker.data.importer.SlotDraft
import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary
import com.wildodds.gymtracker.data.reader.ingest.Grid

/**
 * The shared machinery every layout scorer builds on: finding the header row, reading a block of
 * rows into slots, and turning one row into an exercise.
 *
 * It exists so the twelve scorers differ only in HOW THEY CARVE THE SHEET, not in how they read a
 * row. Every scorer therefore inherits every notation improvement, and a fix to row reading lands
 * once rather than twelve times — which is precisely what went wrong in the engine this replaces,
 * where each strategy had grown its own row logic.
 */
object GridReader {

  private val vocab = ReaderVocabulary

  /**
   * The header row: the first row in the top few whose cells look like column headings, preferring
   * the frozen-pane split when the sheet has one.
   *
   * A frozen pane is a deliberate act by whoever built the sheet — they froze it so the headings
   * would stay visible — which makes it the single most reliable header signal available, and one
   * the previous reader ignored entirely.
   */
  fun findHeaderRow(grid: Grid, searchDepth: Int = 12): Int? {
    grid.frozenRow?.let { fr ->
      val candidate = fr - 1
      if (candidate in 0 until grid.rowCount && headerScore(grid, candidate) > 0) return candidate
    }
    var best = -1
    var bestScore = 0.0
    for (r in 0 until minOf(searchDepth, grid.rowCount)) {
      val s = headerScore(grid, r)
      if (s > bestScore) { bestScore = s; best = r }
    }
    return best.takeIf { it >= 0 && bestScore >= 1.5 }
  }

  /** How many recognised column names a row contains, weighted by how central they are. */
  private fun headerScore(grid: Grid, row: Int): Double {
    var s = 0.0
    for (c in 0 until grid.colCount) {
      val v = vocab.lower(grid.cell(row, c).trim())
      if (v.isBlank()) continue
      // A header cell is a WORD, never a prescription.
      if (v.any { it.isDigit() } && v.length > 4) continue
      for ((role, forms) in vocab.columnRoleHints) {
        if (forms.any { it == v }) {
          // The exercise column is the anchor; a row naming it is almost certainly the header.
          s += if (role == "exercise") 1.5 else 0.7
          break
        }
      }
    }
    return s
  }

  /**
   * Reads rows [from, to) of a sheet into slots under a resolved column assignment.
   *
   * The row is rebuilt into a notation line and handed to the ONE grammar, rather than being read
   * field by field. That is deliberate: a second, table-only parser would be a second place every
   * notation fix has to land, and the two would drift.
   */
  fun readSlots(grid: Grid, assignment: ColumnRoles.Assignment, from: Int, to: Int): List<SlotDraft> {
    val cEx = assignment.columnFor(ColumnRole.EXERCISE) ?: return emptyList()
    val out = ArrayList<SlotDraft>()
    for (r in from until minOf(to, grid.rowCount)) {
      if (r in grid.hiddenRows) continue     // a hidden row is very often a stale draft
      val name = grid.cell(r, cEx).trim()
      if (name.isBlank()) continue
      // A row whose "exercise" cell is a section label is a divider, not an exercise.
      if (vocab.sectionFor(name) != null && rowIsMostlyEmpty(grid, assignment, r)) continue
      readRow(grid, assignment, r, out.size)?.let { out += it }
    }
    return out
  }

  private fun rowIsMostlyEmpty(grid: Grid, a: ColumnRoles.Assignment, row: Int): Boolean {
    val filled = a.roles.entries.count { (c, role) ->
      role != ColumnRole.EXERCISE && role != ColumnRole.IGNORE && grid.cell(row, c).isNotBlank()
    }
    return filled == 0
  }

  fun readRow(grid: Grid, a: ColumnRoles.Assignment, row: Int, order: Int): SlotDraft? {
    fun cell(role: ColumnRole) = a.columnFor(role)?.let { grid.cell(row, it).trim() }.orEmpty()

    val name = cell(ColumnRole.EXERCISE)
    if (name.isBlank()) return null

    val sets = cell(ColumnRole.SETS)
    val reps = cell(ColumnRole.REPS)
    val load = cell(ColumnRole.LOAD)
    val pct = cell(ColumnRole.PERCENT)
    val rpe = cell(ColumnRole.RPE)
    val rest = cell(ColumnRole.REST)
    val tempo = cell(ColumnRole.TEMPO)
    val notes = a.roles.entries.filter { it.value == ColumnRole.NOTES }
      .mapNotNull { grid.cell(row, it.key).trim().takeIf(String::isNotBlank) }
      .joinToString(" · ")

    val line = buildString {
      append(name)
      when {
        sets.isNotBlank() && reps.isNotBlank() -> append(" ${firstInt(sets) ?: sets}x$reps")
        // "reps" is spelled out so the grammar anchors on it. Without the word, a bare "5" after
        // the name is ambiguous and gets swallowed into the exercise name — which is how a set log
        // came out as an exercise called "Back Squat 5".
        reps.isNotBlank() -> append(" $reps reps")
        sets.isNotBlank() -> append(" ${firstInt(sets) ?: sets} sets")
      }
      // The unit is very often in the COLUMN HEADING rather than the cell ("Weight (kg)"), so it
      // is appended here. Without it every spreadsheet weight arrives with an unknown unit and the
      // review screen has to ask a question the sheet already answered.
      if (load.isNotBlank()) {
        val unit = if (load.any { it.isLetter() }) "" else unitFromHeader(grid, a, row)
        append(" @ $load$unit")
      }
      if (pct.isNotBlank()) append(" ${if (pct.contains('%')) pct else "$pct%"}")
      if (rpe.isNotBlank()) append(if (rpe.contains("rir", true)) " $rpe" else " @RPE$rpe")
      if (tempo.isNotBlank()) append(" $tempo")
      if (rest.isNotBlank()) append(" rest $rest")
    }

    val parsed = SetNotationParser.parseExerciseLine(line, order)
      ?: SlotDraft(rawText = line, exercise = ExerciseRef(name, name), orderIndex = order, confidence = 0.4)

    val superset = cell(ColumnRole.SUPERSET)
    return parsed.copy(
      // The RAW ROW is the source line for a spreadsheet, not the reconstructed notation — that is
      // what a re-parse with a future grammar has to work from.
      rawText = rawRow(grid, row),
      supersetGroup = superset.take(1).takeIf { it.isNotBlank() && it[0].isLetter() }.orEmpty()
        .ifBlank { parsed.supersetGroup },
      supersetOrder = superset.drop(1).toIntOrNull() ?: parsed.supersetOrder,
      notes = listOf(parsed.notes, notes).filter { it.isNotBlank() }.joinToString(" · "),
      orderIndex = order
    )
  }

  /** "kg" or "lb" when the load column's heading names one, otherwise "". */
  private fun unitFromHeader(grid: Grid, a: ColumnRoles.Assignment, row: Int): String {
    val header = a.headerRow ?: return ""
    val col = a.columnFor(ColumnRole.LOAD) ?: return ""
    val h = vocab.lower(grid.cell(header, col))
    return when {
      vocab.units["kg"].orEmpty().any { h.contains(it) } -> "kg"
      vocab.units["lb"].orEmpty().any { h.contains(it) } -> "lb"
      else -> ""
    }
  }

  /** The whole row, tab-separated — the verbatim source of a spreadsheet exercise. */
  fun rawRow(grid: Grid, row: Int): String =
    (0 until grid.colCount).map { grid.cell(row, it) }.joinToString("\t").trimEnd('\t')

  fun firstInt(s: String): Int? = Regex("""\d+""").find(s)?.value?.toIntOrNull()

  /** A session built from a contiguous block of rows, with a label taken from above the block. */
  fun sessionFrom(
    grid: Grid,
    assignment: ColumnRoles.Assignment,
    label: String,
    dayNumber: Int,
    from: Int,
    to: Int
  ): SessionDraft {
    val slots = readSlots(grid, assignment, from, to)
    val type = if (vocab.isRestDayLabel(label)) SessionType.REST else SessionType.TRAINING
    return SessionDraft(
      label = label.ifBlank { "Day $dayNumber" },
      dayNumber = dayNumber,
      weekdayHint = vocab.weekdayFor(label.substringBefore(' ')).orEmpty(),
      type = type,
      slots = slots
    )
  }

  /**
   * A row that names a day rather than prescribing an exercise: mostly empty, one text cell, and
   * that cell reads as a day label. These are what separate one session from the next inside a
   * single sheet, and finding them is what most in-sheet layouts come down to.
   */
  fun isDayDividerRow(grid: Grid, row: Int): Boolean {
    val values = (0 until grid.colCount).map { grid.cell(row, it).trim() }.filter { it.isNotBlank() }
    if (values.size != 1) return false
    val v = values.first()
    if (SetNotationParser.hasPrescription(v)) return false
    return isDayLabel(v)
  }

  private val DAY_LABEL = Regex(
    """(?i)^(day|session|workout|training\s*day)\s*[\dA-Z]{1,3}\b|^(w\d+d\d+)$""")

  fun isDayLabel(text: String): Boolean {
    val t = text.trim()
    if (t.isBlank() || t.length > 60) return false
    if (DAY_LABEL.containsMatchIn(t)) return true
    if (vocab.weekdayFor(t.substringBefore(' ').trim(':', '-')) != null) return true
    if (vocab.isRestDayLabel(t)) return true
    val head = vocab.lower(t).trim(':', '-', ' ')
    return vocab.splitKeywords.any { head == it || head.startsWith("$it ") || head.startsWith("$it/") }
  }

  private val WEEK_LABEL = Regex("""(?i)\b(week|wk)\s*(\d{1,2})\b|^w(\d{1,2})$""")

  fun weekNumberIn(text: String): Int? {
    val m = WEEK_LABEL.find(text.trim()) ?: return null
    return (2..3).firstNotNullOfOrNull { m.groupValues.getOrNull(it)?.toIntOrNull() }
  }

  fun isWeekLabel(text: String): Boolean = weekNumberIn(text) != null
}

package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.importer.layout.ColumnRole
import com.wildodds.gymtracker.data.importer.layout.ColumnRoles
import com.wildodds.gymtracker.data.importer.layout.GridReader
import com.wildodds.gymtracker.data.reader.ingest.Grid

/**
 * Deterministic importer for CSV/TSV tables — the common "coach spreadsheet exported to CSV" case.
 * Detects the delimiter, finds the header row (it isn't always row 1), and maps columns by fuzzy
 * header names (week / day / exercise / sets / reps / rpe / %1rm / rest / notes / weight). Each data
 * row becomes one slot under its (week, day). Cells the header didn't cover are ignored, not guessed.
 */
object CsvProgramImporter {

  private val headerAliases: Map<String, List<String>> = mapOf(
    "week"     to listOf("week", "wk", "mesocycle"),
    "day"      to listOf("day", "session", "workout", "d"),
    "exercise" to listOf("exercise", "movement", "lift", "name"),
    "sets"     to listOf("sets", "set"),
    "reps"     to listOf("reps", "rep", "repetitions"),
    "rpe"      to listOf("rpe", "rir"),
    "pct"      to listOf("%1rm", "1rm%", "percent", "%", "intensity", "tm%", "%tm"),
    "rest"     to listOf("rest", "recovery"),
    "weight"   to listOf("weight", "load", "kg", "lb"),
    "notes"    to listOf("notes", "note", "cue", "comment", "coaching")
  )

  /**
   * True only for genuinely tabular input. A plain-text program that happens to use comma-separated
   * per-set reps (e.g. "Front Squat 6, 8, 10") must NOT be treated as CSV — that was a real bug — so
   * we require a recognisable header row, tabs, or a *consistent* multi-column comma structure rather
   * than just the presence of commas.
   */
  fun looksLikeCsv(raw: String): Boolean {
    val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(12).toList()
    if (lines.size < 2) return false
    if (lines.any { it.contains('\t') }) return true

    // A header row that names an exercise column plus another known column is a strong signal.
    val nameCols = setOf("exercise", "movement", "lift", "name")
    val otherCols = setOf("sets", "set", "reps", "rep", "day", "week", "rpe", "%1rm", "intensity", "load", "weight")
    val hasHeader = lines.any { l ->
      val cells = l.lowercase().split(',').map { it.trim() }
      cells.size >= 2 && cells.any { it in nameCols } && cells.any { it in otherCols }
    }
    if (hasHeader) return true

    // Otherwise require most lines to share the same comma count (≥2) — an actual table, not a
    // handful of per-set commas on some lines.
    val counts = lines.map { it.count { c -> c == ',' } }
    val topMultiCol = counts.filter { it >= 2 }.groupingBy { it }.eachCount().maxByOrNull { it.value }
    return topMultiCol != null && topMultiCol.value >= (lines.size * 0.6)
  }

  /**
   * Reads a headerless table through the layout engine's column roles, or null when that is not
   * what this is.
   *
   * Two guards, both about not making things worse than the line-join fallback: the rows have to be
   * consistently multi-column (a handful of per-set commas is not a table), and the roles have to
   * find an exercise column plus at least one other. An assignment that finds only a name column
   * has learned nothing the join did not already know.
   */
  private fun readWithoutHeader(rows: List<List<String>>): List<SlotDraft>? {
    val multiColumn = rows.count { it.size >= 2 }
    if (multiColumn < maxOf(2, (rows.size * 0.6).toInt())) return null

    val grid = Grid("csv", rows)
    val assignment = ColumnRoles.assign(grid, headerRow = null)
    val nameCol = assignment.columnFor(ColumnRole.EXERCISE) ?: return null

    // The name column of a real table holds names. "Front Squat 6, 8, 10" splits into
    // ["Front Squat 6", "8", "10"], which looks exactly like a three-column row and is one exercise
    // with three sets - reading it as a table would turn it into 6 sets of 8. A digit in the name
    // cell means these are not columns, and the line reader below handles it correctly.
    val named = rows.mapNotNull { it.getOrNull(nameCol)?.trim() }.filter { it.isNotBlank() }
    if (named.isEmpty()) return null
    if (named.count { cell -> cell.any(Char::isDigit) } * 2 > named.size) return null
    val others = listOf(
      ColumnRole.SETS, ColumnRole.REPS, ColumnRole.LOAD, ColumnRole.PERCENT,
      ColumnRole.RPE, ColumnRole.REST, ColumnRole.TEMPO)
    if (others.none { assignment.columnFor(it) != null }) return null

    return GridReader.readSlots(grid, assignment, 0, grid.rowCount).ifEmpty { null }
  }

  fun import(name: String, raw: String): ProgramImportDraft {
    val rawLines = raw.lines().filter { it.isNotBlank() }
    if (rawLines.isEmpty()) return ProgramImportDraft(name.ifBlank { "Imported Program" }, weeks = listOf(WeekDraft(1, sessions = emptyList())))
    val delim = if (rawLines.first().contains('\t')) '\t' else ','

    // Header row = the first row that names at least an exercise column.
    val rows = rawLines.map { splitCsv(it, delim) }
    val headerIdx = rows.indexOfFirst { row -> colIndex(row, "exercise") != null }
    if (headerIdx < 0) {
      // No recognisable header. Joining the cells back into one line and re-parsing it was losing
      // the table: "Back Squat", "5", "5", "100kg" became the line "Back Squat 5 5 100kg", which
      // names a movement and prescribes nothing. A headerless table is precisely what the layout
      // engine's column-role assignment was built for — it reads roles from CONTENT statistics when
      // there is no header text — so a pasted table without headings now reads the same way the
      // identical spreadsheet does.
      val fromGrid = readWithoutHeader(rows)
      if (fromGrid != null) {
        return ProgramImportDraft(name.ifBlank { "Imported Program" }, source = "csv (no header)",
          weeks = listOf(WeekDraft(1, sessions = listOf(SessionDraft("Day 1", 1, slots = fromGrid)))))
      }
      // Genuinely one column, or a table the roles could not read: the line reader is still the
      // right answer for it. Read the ORIGINAL line, not the cells joined back with spaces —
      // rejoining threw the delimiters away, so "Front Squat 6, 8, 10" arrived as
      // "Front Squat 6 8 10", which parses as nothing at all and dropped the exercise entirely.
      val slots = rawLines.mapIndexedNotNull { i, line ->
        SetNotationParser.parseExerciseLine(line.replace('\t', ' '), i)
      }
      return ProgramImportDraft(name.ifBlank { "Imported Program" }, source = "csv",
        weeks = listOf(WeekDraft(1, sessions = listOf(SessionDraft("Day 1", 1, slots = slots)))))
    }

    val header = rows[headerIdx]
    val col = { key: String -> colIndex(header, key) }
    val dataRows = rows.drop(headerIdx + 1)

    // Group rows into (week, day). Missing week → 1; missing day → carry the previous row's day.
    data class Key(val week: Int, val day: Int, val label: String)
    val grouped = LinkedHashMap<Key, MutableList<SlotDraft>>()
    var lastWeek = 1; var lastDay = 1; var lastLabel = "Day 1"; var order = 0

    for (r in dataRows) {
      val exName = col("exercise")?.let { r.getOrNull(it) }?.trim().orEmpty()
      if (exName.isBlank()) continue
      lastWeek = col("week")?.let { r.getOrNull(it)?.trim()?.let(::firstInt) } ?: lastWeek
      val dayCell = col("day")?.let { r.getOrNull(it)?.trim() }.orEmpty()
      if (dayCell.isNotBlank()) {
        lastDay = firstInt(dayCell) ?: lastDay
        lastLabel = if (dayCell.any { it.isDigit() } && dayCell.length <= 6) "Day $lastDay" else dayCell
      }
      val key = Key(lastWeek, lastDay, lastLabel)
      val slots = grouped.getOrPut(key) { order = 0; mutableListOf() }
      slots += buildSlot(r, header, order++)
    }

    val byWeek = grouped.entries.groupBy { it.key.week }.toSortedMap()
    val weeks = byWeek.map { (wk, entries) ->
      WeekDraft(index = wk, sessions = entries.map { (k, slots) -> SessionDraft(k.label, k.day, slots = slots) })
    }
    val multiWeek = weeks.size > 1
    return ProgramImportDraft(
      name = name.ifBlank { "Imported Program" }, source = "csv", weeks = weeks,
      structure = if (multiWeek) ProgramStructure.WEEK_BY_WEEK else ProgramStructure.SINGLE_WEEK_REPEATED,
      repeatWeeks = 1
    )
  }

  private fun buildSlot(row: List<String>, header: List<String>, order: Int): SlotDraft {
    fun cell(key: String) = colIndex(header, key)?.let { row.getOrNull(it)?.trim() }.orEmpty()
    val name = cell("exercise")
    // Reconstruct a notation line so one parser handles both text and tabular sources.
    val reps = cell("reps"); val sets = cell("sets"); val rpe = cell("rpe"); val pct = cell("pct")
    val rest = cell("rest"); val weight = cell("weight"); val notes = cell("notes")
    val line = buildString {
      append(name)
      if (sets.isNotBlank() && reps.isNotBlank()) append(" ${firstInt(sets) ?: sets}x$reps")
      else if (reps.isNotBlank()) append(" $reps")
      if (weight.isNotBlank()) append(" $weight")
      if (pct.isNotBlank()) append(" ${if (pct.contains('%')) pct else "$pct%"}")
      if (rpe.isNotBlank()) append(" @RPE$rpe")
      if (rest.isNotBlank()) append(" rest $rest")
    }
    val parsed = SetNotationParser.parseExerciseLine(line, order)
      ?: SlotDraft(rawText = name, exercise = ExerciseRef(name, name), orderIndex = order, confidence = 0.4)
    return if (notes.isNotBlank()) parsed.copy(notes = listOf(parsed.notes, notes).filter { it.isNotBlank() }.joinToString(" · ")) else parsed
  }

  // ── helpers ──────────────────────────────────────────────────────────────────
  /**
   * The column a header names, matched on WHOLE WORDS.
   *
   * Substring matching looked harmless and ate whole imports: "lift" is an alias for the exercise
   * column and "Romanian Deadlift" contains it, so a pasted headerless table whose second row
   * happened to name a deadlift had that row taken for the header — leaving no data rows at all and
   * returning a program with zero weeks. "name" inside "Hammer" and "set" inside "Offset" are the
   * same trap.
   *
   * A header cell is short and says what the column is, so equality or a whole-word hit is all that
   * is ever needed. `#`-prefixed and parenthesised forms ("# sets", "rest (s)") still match because
   * the split is on non-letters.
   */
  private fun colIndex(header: List<String>, key: String): Int? {
    val aliases = headerAliases[key] ?: return null
    return header.indexOfFirst { cell ->
      val h = cell.trim().lowercase(java.util.Locale.ROOT)
      if (h.isEmpty() || h.length > 24) return@indexOfFirst false
      val words = h.split(Regex("""[^\p{L}\p{N}%]+""")).filter { it.isNotEmpty() }
      aliases.any { a -> h == a || a in words }
    }.takeIf { it >= 0 }
  }

  private fun firstInt(s: String): Int? = Regex("""\d+""").find(s)?.value?.toIntOrNull()

  /** Minimal CSV field splitter with basic double-quote support. */
  private fun splitCsv(line: String, delim: Char): List<String> {
    val out = mutableListOf<String>(); val sb = StringBuilder(); var inQ = false
    for (c in line) when {
      c == '"' -> inQ = !inQ
      c == delim && !inQ -> { out.add(sb.toString()); sb.clear() }
      else -> sb.append(c)
    }
    out.add(sb.toString())
    return out.map { it.trim() }
  }
}

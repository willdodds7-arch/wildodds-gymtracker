package com.wildodds.gymtracker.data.importer

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

  fun import(name: String, raw: String): ProgramImportDraft {
    val rawLines = raw.lines().filter { it.isNotBlank() }
    if (rawLines.isEmpty()) return ProgramImportDraft(name.ifBlank { "Imported Program" }, weeks = listOf(WeekDraft(1, sessions = emptyList())))
    val delim = if (rawLines.first().contains('\t')) '\t' else ','

    // Header row = the first row that names at least an exercise column.
    val rows = rawLines.map { splitCsv(it, delim) }
    val headerIdx = rows.indexOfFirst { row -> colIndex(row, "exercise") != null }
    if (headerIdx < 0) {
      // No recognisable header — treat every row's first cell as an exercise line for Day 1.
      val slots = rows.mapIndexedNotNull { i, r -> SetNotationParser.parseExerciseLine(r.joinToString(" "), i) }
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
  private fun colIndex(header: List<String>, key: String): Int? {
    val aliases = headerAliases[key] ?: return null
    return header.indexOfFirst { cell ->
      val h = cell.trim().lowercase()
      aliases.any { a -> h == a || h.startsWith(a) || h.contains(a) }
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

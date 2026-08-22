package com.wildodds.gymtracker.data.parser

import java.io.InputStream

/**
 * Parses the "bulk upload" workbook into many [ParsedProgram]s, with phase support.
 *
 * Format (one row per exercise; a header row anywhere in the sheet drives the column
 * mapping by name, so column order is flexible). Recognised headers:
 *
 *   Program | Phase | Phase Name | Phase Weeks | Day | Exercise |
 *   Sets | Reps | RPE | %1RM | Rest | Superset | Notes
 *
 * Each (Program, Phase, Day) defines ONE representative week; the app repeats it for
 * the phase's "Phase Weeks". Program / Phase / Phase Name / Phase Weeks / Day carry
 * forward down the sheet when left blank (merged-cell style). Week numbers in the DB
 * stay global and continuous across phases.
 *
 * Multiple programs can live in one sheet (distinguished by the Program column) and/or
 * be split across multiple sheets. Sheets named like "Instructions"/"Guide"/"Example"
 * are skipped.
 */
class BulkProgramXlsxParser {

  fun parse(stream: InputStream): List<ParsedProgram> {
  val sheets = loadSheets(stream)
  // program name -> ordered phases -> day name -> exercises
  val programs = LinkedHashMap<String, ProgramAcc>()

  for ((sheetName, cells) in sheets) {
  val lower = sheetName.lowercase()
  if (lower.contains("instruction") || lower.contains("guide") ||
  lower.contains("example") || lower.contains("read me") ||
  lower.contains("readme") || sheetName.startsWith("📋")) continue
  parseSheet(cells, programs)
  }

  return programs.values.mapNotNull { it.toParsedProgram() }
  }

  // ── Accumulators ──────────────────────────────────────────────────────────

  private data class ExAcc(
  val name: String, val sets: Int, val reps: String,
  val rpe: String, val pct1rm: String, val rest: String,
  val superset: String, val notes: String
  )

  private class DayAcc(val name: String) {
  val exercises = mutableListOf<ExAcc>()
  }

  private class PhaseAcc(val number: Int) {
  var name: String = ""
  var declaredWeeks: Int = 0
  // preserves insertion order of days
  val days = LinkedHashMap<String, DayAcc>()
  }

  private class ProgramAcc(val name: String) {
  val phases = LinkedHashMap<Int, PhaseAcc>()

  fun toParsedProgram(): ParsedProgram? {
  if (phases.isEmpty()) return null
  val orderedPhases = phases.values.sortedBy { it.number }
  if (orderedPhases.all { it.days.isEmpty() }) return null

  val parsedPhases = mutableListOf<ParsedPhase>()
  val sessions = mutableListOf<ParsedSession>()
  var globalWeek = 0
  var anyRpe = false
  var anyPct = false

  orderedPhases.forEachIndexed { idx, phase ->
  val phaseNumber = idx + 1
  val weeks = if (phase.declaredWeeks > 0) phase.declaredWeeks else 1
  val phaseName = phase.name.ifBlank { "Phase $phaseNumber" }
  parsedPhases.add(ParsedPhase(phaseNumber, phaseName, weeks))

  val dayList = phase.days.values.toList()
  for (w in 1..weeks) {
  globalWeek++
  dayList.forEachIndexed { dayIdx, day ->
  val exercises = day.exercises.mapIndexed { exIdx, ex ->
  if (ex.rpe.isNotBlank()) anyRpe = true
  if (ex.pct1rm.isNotBlank()) anyPct = true
  val noteParts = mutableListOf<String>()
  if (ex.notes.isNotBlank()) noteParts.add(ex.notes)
  if (ex.rest.isNotBlank()) noteParts.add("Rest ${ex.rest}")
  if (ex.superset.isNotBlank()) noteParts.add("Superset ${ex.superset}")
  ParsedExercise(
  name = ex.name, sets = ex.sets, repsTarget = ex.reps,
  notes = noteParts.joinToString(", "), orderIndex = exIdx,
  rpeTarget = ex.rpe, pct1rmTarget = ex.pct1rm
  )
  }
  val muscles = day.exercises.take(3)
  .joinToString(" / ") { it.name.split(" ").firstOrNull() ?: it.name }
  .ifBlank { "Custom" }
  sessions.add(ParsedSession(
  weekNumber = globalWeek, dayNumber = dayIdx + 1,
  name = day.name, muscleGroups = muscles,
  exercises = exercises, phaseNumber = phaseNumber
  ))
  }
  }
  }

  if (sessions.isEmpty()) return null

  return ParsedProgram(
  name = name.ifBlank { "Imported Program" },
  totalWeeks = globalWeek,
  sessions = sessions,
  isUserCreated = true,
  trackRpe = anyRpe,
  trackOneRm = anyPct,
  phases = parsedPhases
  )
  }
  }

  // ── Row parsing ─────────────────────────────────────────────────────────────

  private fun parseSheet(cells: Map<String, String>, programs: LinkedHashMap<String, ProgramAcc>) {
  val maxRow = cells.keys.mapNotNull { rowOf(it) }.maxOrNull() ?: return

  // Locate header row (must contain "exercise")
  var headerRow = -1
  val colMap = mutableMapOf<String, String>()  // canonical key -> column letter
  for (row in 1..maxRow) {
  val rowCols = mutableMapOf<String, String>()
  var foundExercise = false
  for (colIdx in 1..30) {
  val col = colLetter(colIdx)
  val h = cells["$col$row"]?.trim()?.lowercase() ?: continue
  val key = when {
  h == "program" || h.startsWith("program name") -> "program"
  h == "phase" || h == "phase #" || h == "phase number" -> "phase"
  h.startsWith("phase name") -> "phasename"
  h.startsWith("phase week") || h == "weeks" || h == "phase duration" -> "phaseweeks"
  h == "day" || h.startsWith("day/") || h.startsWith("day ") || h == "session" -> "day"
  h == "exercise" || h == "movement" -> { foundExercise = true; "exercise" }
  h == "sets" -> "sets"
  h == "reps" || h == "rep" -> "reps"
  h == "rpe" || h.startsWith("rpe") -> "rpe"
  h.contains("1rm") || h.contains("tm%") || h == "%1rm" || h == "intensity" -> "pct1rm"
  h == "rest" || h.startsWith("rest") -> "rest"
  h == "superset" || h == "ss" -> "superset"
  h == "notes" || h == "note" -> "notes"
  else -> null
  }
  if (key != null) rowCols[key] = col
  }
  if (foundExercise && rowCols.containsKey("exercise")) {
  headerRow = row
  colMap.putAll(rowCols)
  break
  }
  }
  if (headerRow < 0) return

  fun cell(key: String, row: Int): String =
  colMap[key]?.let { cells["$it$row"]?.trim() } ?: ""

  var curProgram = ""
  var curPhaseNum = 1
  var curPhaseName = ""
  var curPhaseWeeks = 0
  var curDay = ""

  for (row in (headerRow + 1)..maxRow) {
  val exName = cell("exercise", row)
  val programCell = cell("program", row)
  val phaseCell = cell("phase", row)
  val phaseNameCell = cell("phasename", row)
  val phaseWeeksCell = cell("phaseweeks", row)
  val dayCell = cell("day", row)

  // New program → reset all phase context so values don't bleed across programs.
  if (programCell.isNotBlank() && programCell != curProgram) {
  curProgram = programCell
  curPhaseNum = 1
  curPhaseName = ""
  curPhaseWeeks = 0
  curDay = ""
  }
  // New phase → reset phase-scoped context.
  if (phaseCell.isNotBlank()) {
  val n = phaseCell.filter { it.isDigit() }.toIntOrNull() ?: curPhaseNum
  if (n != curPhaseNum) {
  curPhaseNum = n
  curPhaseName = ""
  curPhaseWeeks = 0
  curDay = ""
  }
  }
  if (phaseNameCell.isNotBlank()) curPhaseName = phaseNameCell
  if (phaseWeeksCell.isNotBlank()) {
  curPhaseWeeks = phaseWeeksCell.filter { it.isDigit() }.toIntOrNull() ?: curPhaseWeeks
  }
  if (dayCell.isNotBlank()) curDay = dayCell

  if (exName.isBlank() || exName.equals("Exercise", ignoreCase = true)) continue
  if (curProgram.isBlank()) curProgram = "Imported Program"
  if (curDay.isBlank()) curDay = "Day 1"

  val prog = programs.getOrPut(curProgram) { ProgramAcc(curProgram) }
  val phase = prog.phases.getOrPut(curPhaseNum) { PhaseAcc(curPhaseNum) }
  if (curPhaseName.isNotBlank()) phase.name = curPhaseName
  if (curPhaseWeeks > 0) phase.declaredWeeks = curPhaseWeeks
  val day = phase.days.getOrPut(curDay) { DayAcc(curDay) }

  // NEVER invented. This used to default to 3 sets of "8-12" for any row that did not say,
  // which put numbers into users' programs that appear nowhere in their spreadsheet. A
  // missing set count renders as one set (the schema needs something to show) and a missing
  // rep target stays EMPTY, which is what the source actually said.
  val sets = cell("sets", row).split(Regex("[ \\-/]")).firstOrNull()?.toIntOrNull()
  day.exercises.add(ExAcc(
  name = exName,
  sets = sets ?: 1,
  reps = cell("reps", row),
  rpe = cell("rpe", row),
  pct1rm = cell("pct1rm", row),
  rest = cell("rest", row),
  superset = cell("superset", row),
  notes = cell("notes", row)
  ))
  }
  }

  // ── Cell ref helpers ──────────────────────────────────────────────────────

  private fun rowOf(ref: String): Int? = ref.filter { it.isDigit() }.toIntOrNull()

  private fun colLetter(idx: Int): String {
  if (idx <= 26) return ('A' + idx - 1).toString()
  val first = ('A' + (idx - 1) / 26 - 1)
  val second = ('A' + (idx - 1) % 26)
  return "$first$second"
  }
  // ── Sheet reading, folded onto the shared engine ──────────────────────────
  //
  // This used to be a second hand-rolled zip + XML reader, one of THREE in the codebase, and it
  // drifted: it had no number formats (a percent-formatted 0.85 read as "0.85"), no merged cells,
  // no inline strings (so Google Sheets exports lost every label), no cached formula values and no
  // hidden-row awareness. Delegating to XlsxExtractor means bulk import inherits all of that, and
  // every future fix, without a second place to maintain.

  private fun loadSheets(stream: InputStream): Map<String, Map<String, String>> {
  val bytes = stream.readBytes()
  val zip = try {
  com.wildodds.gymtracker.data.reader.ingest.ZipReader.read(
  bytes, com.wildodds.gymtracker.data.reader.ingest.IngestLimits.DEFAULT)
  } catch (e: com.wildodds.gymtracker.data.reader.ingest.IngestError) {
  return emptyMap()
  }
  val book = try {
  com.wildodds.gymtracker.data.reader.ingest.XlsxExtractor.read(
  zip, com.wildodds.gymtracker.data.reader.ingest.IngestLimits.DEFAULT)
  } catch (e: com.wildodds.gymtracker.data.reader.ingest.IngestError) {
  return emptyMap()
  }

  // The accumulation logic below is genuinely this parser's own — multi-program workbooks with
  // phases are a shape no other path handles — so the grid is presented in the A1-keyed form it
  // already expects rather than being rewritten around it.
  return book.grids.associate { g ->
  g.name to buildMap {
  for (r in 0 until g.rowCount) {
  if (r in g.hiddenRows) continue
  for (c in 0 until g.colCount) {
  val v = g.cell(r, c)
  if (v.isNotBlank()) put(
  com.wildodds.gymtracker.data.reader.ingest.A1.letters(c) + (r + 1), v)
  }
  }
  }
  }
  }
}

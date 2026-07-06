package com.wildodds.gymtracker.data.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

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

  val sets = cell("sets", row).split(Regex("[ \\-/]")).firstOrNull()?.toIntOrNull() ?: 3
  day.exercises.add(ExAcc(
  name = exName,
  sets = sets,
  reps = cell("reps", row).ifBlank { "8-12" },
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

  // ── XLSX reading (mirrors DefaultProgramXlsxParser) ───────────────────────

  private fun loadSheets(stream: InputStream): Map<String, Map<String, String>> {
  val entries = mutableMapOf<String, ByteArray>()
  ZipInputStream(stream.buffered()).use { zip ->
  var entry = zip.nextEntry
  while (entry != null) {
  entries[entry.name] = zip.readBytes()
  entry = zip.nextEntry
  }
  }
  val sharedStrings = parseSharedStrings(entries["xl/sharedStrings.xml"])
  val workbookRels = parseWorkbookRels(entries["xl/_rels/workbook.xml.rels"] ?: byteArrayOf())
  val workbook = parseWorkbook(entries["xl/workbook.xml"] ?: return emptyMap())

  val sheets = LinkedHashMap<String, Map<String, String>>()
  for ((name, rId) in workbook) {
  val target = workbookRels[rId] ?: continue
  val normTarget = target.trimStart('/')
  val path = when {
  normTarget.startsWith("xl/worksheets/") -> normTarget
  normTarget.startsWith("worksheets/") -> "xl/$normTarget"
  else -> "xl/worksheets/$normTarget"
  }
  val data = entries[path] ?: entries[normTarget] ?: continue
  sheets[name] = parseSheetXml(data, sharedStrings)
  }
  return sheets
  }

  private fun parseSharedStrings(data: ByteArray?): List<String> {
  if (data == null) return emptyList()
  val strings = mutableListOf<String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var inT = false
  val sb = StringBuilder()
  var eventType = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  when (eventType) {
  XmlPullParser.START_TAG -> {
  if (parser.name == "t") { inT = true; sb.clear() }
  else if (parser.name == "si") sb.clear()
  }
  XmlPullParser.TEXT -> if (inT) sb.append(parser.text)
  XmlPullParser.END_TAG -> {
  if (parser.name == "t") inT = false
  else if (parser.name == "si") strings.add(sb.toString())
  }
  }
  eventType = parser.next()
  }
  return strings
  }

  private fun parseWorkbook(data: ByteArray): Map<String, String> {
  val map = LinkedHashMap<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var eventType = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  if (eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
  val name = parser.getAttributeValue(null, "name") ?: ""
  val rId = parser.getAttributeValue(null, "r:id")
  ?: parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
  ?: ""
  if (name.isNotEmpty() && rId.isNotEmpty()) map[name] = rId
  }
  eventType = parser.next()
  }
  return map
  }

  private fun parseWorkbookRels(data: ByteArray): Map<String, String> {
  if (data.isEmpty()) return emptyMap()
  val map = mutableMapOf<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var eventType = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  if (eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
  val id = parser.getAttributeValue(null, "Id") ?: ""
  val target = parser.getAttributeValue(null, "Target") ?: ""
  if (id.isNotEmpty()) map[id] = target
  }
  eventType = parser.next()
  }
  return map
  }

  private fun parseSheetXml(data: ByteArray, sharedStrings: List<String>): Map<String, String> {
  val cells = mutableMapOf<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")

  var currentCell = ""
  var currentType = ""
  var inV = false
  var inIs = false
  val text = StringBuilder()
  var eventType = parser.eventType

  while (eventType != XmlPullParser.END_DOCUMENT) {
  when (eventType) {
  XmlPullParser.START_TAG -> when (parser.name) {
  "c" -> {
  currentCell = parser.getAttributeValue(null, "r") ?: ""
  currentType = parser.getAttributeValue(null, "t") ?: ""
  inV = false
  }
  "v" -> { inV = true; text.clear() }
  "is" -> { inIs = true; text.clear() }
  "t" -> if (inIs) text.clear()
  }
  XmlPullParser.TEXT -> if (inV || inIs) text.append(parser.text)
  XmlPullParser.END_TAG -> when (parser.name) {
  "v" -> {
  inV = false
  val raw = text.toString().trim()
  if (currentCell.isNotEmpty()) {
  cells[currentCell] = when (currentType) {
  "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
  else -> raw
  }
  }
  }
  "is" -> {
  inIs = false
  if (currentCell.isNotEmpty()) cells[currentCell] = text.toString().trim()
  }
  }
  }
  eventType = parser.next()
  }
  return cells
  }
}

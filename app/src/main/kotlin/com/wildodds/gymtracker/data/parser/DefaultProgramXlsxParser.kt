package com.wildodds.gymtracker.data.parser

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parses the bundled default_programs.xlsx asset into a list of [ParsedProgram].
 *
 * The Excel has one sheet per program (named "1-Starting Strength", "2-...", etc.)
 * plus a "Program Index" overview sheet.
 *
 * Each program sheet layout:
 *  Row 1  : Program title (merged cell)
 *  Row 2  : Creator / Level / Goal / Total Weeks / Days per Week metadata
 *  Row 3  : Description
 *  Row 4  : Column headers  (Week | Day/Focus | Exercise | Sets | Reps | RPE | %1RM/TM% | Notes)
 *  Row 5+ : Data rows - Week and Day columns carry forward when blank (merged-cell style)
 */
class DefaultProgramXlsxParser(private val context: Context) {

  companion object {
  private const val ASSET_NAME = "default_programs.xlsx"
  }

  // ── Public entry point ────────────────────────────────────────────────────

  fun parse(): List<ParsedProgram> =
  try {
  context.assets.open(ASSET_NAME).use { parseStream(it) }
  } catch (_: Exception) {
  emptyList()  // missing/unreadable asset → empty catalogue
  }

  /**
   * Parses a workbook from an arbitrary stream (the asset, or a test fixture). Returns whatever
   * parsed successfully — a single bad sheet never aborts the whole catalogue.
   */
  internal fun parseStream(stream: InputStream): List<ParsedProgram> {
  val results = mutableListOf<ParsedProgram>()
  try {
  val (sheets, _) = loadSheets(stream)
  val indexMeta  = parseIndex(sheets)

  for ((sheetName, cells) in sheets) {
  if (sheetName.contains("Index", ignoreCase = true) ||
  sheetName.startsWith("📋")) continue
  val prog = parseProgram(sheetName, cells, indexMeta) ?: continue
  results.add(prog)
  }
  } catch (_: Exception) {
  // Return whatever was parsed so far.
  }
  // Sort by the numeric prefix on sheet names ("1-...", "12-...", etc.)
  return results.sortedBy { extractSortKey(it.name) }
  }

  // ── Sheet loading ─────────────────────────────────────────────────────────

  private fun loadSheets(stream: InputStream): Pair<Map<String, Map<String, String>>, List<String>> {
  val entries = mutableMapOf<String, ByteArray>()
  ZipInputStream(stream.buffered()).use { zip ->
  var entry = zip.nextEntry
  while (entry != null) {
  entries[entry.name] = zip.readBytes()
  entry = zip.nextEntry
  }
  }
  val sharedStrings = parseSharedStrings(entries["xl/sharedStrings.xml"])
  val workbookRels  = parseWorkbookRels(entries["xl/_rels/workbook.xml.rels"] ?: byteArrayOf())
  val workbook  = parseWorkbook(entries["xl/workbook.xml"] ?: return emptyMap<String, Map<String, String>>() to emptyList())

  val sheets = mutableMapOf<String, Map<String, String>>()
  for ((name, rId) in workbook) {
  val target = workbookRels[rId] ?: continue
  val normTarget = target.trimStart('/')
  val path = when {
    normTarget.startsWith("xl/worksheets/") -> normTarget
    normTarget.startsWith("worksheets/") -> "xl/$normTarget"
    else -> "xl/worksheets/$normTarget"
  }
  val data  = entries[path] ?: entries[normTarget] ?: continue
  sheets[name] = parseSheet(data, sharedStrings)
  }
  return sheets to sharedStrings
  }

  // ── Program Index parsing ─────────────────────────────────────────────────

  private data class IndexMeta(
  val category: String,
  val intensitySystem: String  // "RPE", "%1RM", "RPE/%1RM", "Linear", ""
  )

  private fun parseIndex(sheets: Map<String, Map<String, String>>): Map<String, IndexMeta> {
  val indexCells = sheets.entries
  .find { it.key.contains("Index", ignoreCase = true) || it.key.startsWith("📋") }
  ?.value ?: return emptyMap()

  // Detect column positions from row 2 headers
  var goalCol  = "E"
  var sheetTabCol  = "H"
  var intensityCol = "I"

  for (colIdx in 1..12) {
  val col  = colLetter(colIdx)
  val header = indexCells["${col}2"]?.lowercase() ?: continue
  when {
  header.contains("goal")  -> goalCol  = col
  header.contains("sheet") || header.contains("tab") -> sheetTabCol = col
  header.contains("intensity") -> intensityCol = col
  }
  }

  // Read EVERY data row — no hard cap. The loop is bounded only by the index sheet's own last
  // populated row, so the catalogue can grow without programs silently losing their index
  // category/tracking. (Previously capped at a fixed row count, which broke later programs.)
  val maxRow = indexCells.keys
  .mapNotNull { ref -> ref.filter { it.isDigit() }.toIntOrNull() }
  .maxOrNull() ?: 2

  val result = mutableMapOf<String, IndexMeta>()
  for (row in 3..maxRow) {
  val sheetTab = indexCells["${sheetTabCol}$row"]?.trim()
  if (sheetTab.isNullOrBlank()) continue

  val goal  = indexCells["${goalCol}$row"]?.trim() ?: ""
  val intensity = indexCells["${intensityCol}$row"]?.trim() ?: ""
  result[sheetTab] = IndexMeta(
  category  = mapGoalToCategory(goal),
  intensitySystem = intensity
  )
  }
  return result
  }

  private fun mapGoalToCategory(goal: String): String = when {
  goal.contains("Hypertrophy",  ignoreCase = true) -> "Hypertrophy"
  goal.contains("Powerlifting",  ignoreCase = true) -> "Powerlifting"
  goal.contains("Powerbuilding", ignoreCase = true) -> "Powerbuilding"
  goal.contains("Strength",  ignoreCase = true) -> "Strength"
  goal.contains("Athletic",  ignoreCase = true) -> "GPP"
  goal.contains("GPP",  ignoreCase = true) -> "GPP"
  goal.contains("Conditioning",  ignoreCase = true) -> "Conditioning"
  goal.contains("Bodyweight",  ignoreCase = true) -> "GPP"
  else -> "Strength"
  }

  // ── Per-program sheet parsing ─────────────────────────────────────────────

  private val weekRegex  = Regex("""Week\s*(\d+)""", RegexOption.IGNORE_CASE)
  private val weekdayRegex  = Regex(
  """^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s*[—–\-]\s*""",
  RegexOption.IGNORE_CASE
  )
  private val dayNumPrefixRx = Regex("""^Day\s+\d+\s*[—–\-]\s*""", RegexOption.IGNORE_CASE)


  private fun parseProgram(
  sheetName: String,
  cells: Map<String, String>,
  meta: Map<String, IndexMeta>
  ): ParsedProgram? {

  // Program title from row 1 (merged cell → col A)
  val title = cells["A1"]?.trim()?.ifBlank { null }?.let { stripEmoji(it) } ?: sheetName

  // Metadata from row 2: scan label cells (tolerant of a trailing ":") and read the adjacent value.
  var totalWeeks = 1
  var goalFromSheet = ""
  var coach = ""
  var coachBio = ""
  var split = ""
  var style = ""
  var daysPerWeek = 0
  fun nextVal(colIdx: Int): String = cells["${colLetter(colIdx + 1)}2"]?.trim() ?: ""
  for (colIdx in 1..12) {
  val col = colLetter(colIdx)
  val raw = cells["${col}2"]?.trim() ?: continue
  val label = raw.trimEnd(':').trim()
  when {
  label.contains("Total Weeks", ignoreCase = true) || label.equals("Weeks", ignoreCase = true) ->
  totalWeeks = nextVal(colIdx).toIntOrNull() ?: totalWeeks
  label.equals("Goal", ignoreCase = true) -> goalFromSheet = nextVal(colIdx)
  label.equals("Coach", ignoreCase = true) || label.equals("Creator", ignoreCase = true) ||
  label.equals("Author", ignoreCase = true) -> coach = nextVal(colIdx)
  label.equals("Coach Bio", ignoreCase = true) || label.equals("Bio", ignoreCase = true) ->
  coachBio = nextVal(colIdx)
  label.contains("Days per Week", ignoreCase = true) || label.equals("Days", ignoreCase = true) ->
  daysPerWeek = nextVal(colIdx).filter { it.isDigit() }.toIntOrNull() ?: daysPerWeek
  label.equals("Split", ignoreCase = true) -> split = nextVal(colIdx)
  label.equals("Style", ignoreCase = true) -> style = nextVal(colIdx)
  }
  }

  // Description: row 3, col A (a free-text blurb spanning the row).
  val description = cells["A3"]?.trim().orEmpty()

  // Use index meta for category/intensity if available, else derive from sheet
  val indexMeta  = meta[sheetName]
  val category  = when {
  indexMeta?.category?.isNotBlank() == true -> indexMeta.category
  goalFromSheet.isNotBlank()  -> mapGoalToCategory(goalFromSheet)
  else  -> "Strength"
  }
  val intensitySystem = indexMeta?.intensitySystem ?: ""

  // Find the max row with data
  val maxRow = cells.keys
  .mapNotNull { ref -> ref.filter { it.isDigit() }.toIntOrNull() }
  .maxOrNull() ?: return null

  if (maxRow < 5) return null

  // ── Parse data rows from row 5 ─────────────────────────────────────

  data class ExRow(
  val week: Int, val dayNum: Int, val dayName: String,
  val name: String, val sets: Int, val reps: String,
  val rpe: String, val pct1rm: String, val notes: String
  )

  val rows = mutableListOf<ExRow>()
  var currentWeek  = 1
  var currentDayNum  = 0  // position within current week
  var currentDayName  = ""
  var prevDayName  = ""  // to detect day transitions

  for (row in 5..maxRow) {
  val weekCell = cells["A$row"]?.trim() ?: ""
  val dayCell  = cells["B$row"]?.trim() ?: ""
  val exName  = cells["C$row"]?.trim() ?: ""

  // Update week number when non-blank
  if (weekCell.isNotBlank()) {
  val newWeek = weekRegex.find(weekCell)?.groupValues?.get(1)?.toIntOrNull()
  if (newWeek != null && newWeek != currentWeek) {
  currentWeek  = newWeek
  currentDayNum  = 0
  prevDayName  = ""
  }
  }

  // Update day when non-blank and different from last seen
  if (dayCell.isNotBlank() && dayCell != prevDayName) {
  currentDayNum++
  currentDayName = dayCell
  prevDayName  = dayCell
  }

  // Skip rows without an exercise name or meta/header rows
  if (exName.isBlank()) continue
  if (exName.equals("Exercise", ignoreCase = true)) continue
  if (currentDayNum == 0) continue  // no day context yet

  val setsRaw = cells["D$row"]?.trim() ?: ""
  val sets  = setsRaw.split(Regex("[ - \\-/]")).firstOrNull()
  ?.trim()?.toIntOrNull() ?: 3
  val reps  = cells["E$row"]?.trim() ?: ""
  val rpe  = cells["F$row"]?.trim() ?: ""
  val pct1rm  = cells["G$row"]?.trim() ?: ""
  val notes  = cells["H$row"]?.trim() ?: ""

  rows.add(ExRow(currentWeek, currentDayNum, currentDayName,
  exName, sets,
  reps.ifBlank { "8-12" },
  rpe, pct1rm, notes))
  }

  if (rows.isEmpty()) return null

  // ── Group rows into sessions ──────────────────────────────────────

  // Use LinkedHashMap to preserve insertion order (important for session ordering)
  val sessionMap = LinkedHashMap<Pair<Int, Int>, MutableList<ExRow>>()
  for (r in rows) {
  sessionMap.getOrPut(r.week to r.dayNum) { mutableListOf() }.add(r)
  }

  // Detect whether this program uses RPE or %1RM prescriptions
  val hasRpe  = rows.any { it.rpe.isNotBlank() }
  val has1rm  = rows.any { it.pct1rm.isNotBlank() }
  val trackRpe  = hasRpe  || intensitySystem.contains("RPE",  ignoreCase = true)
  val trackOneRm= has1rm  || intensitySystem.contains("%1RM", ignoreCase = true)

  // Actual total weeks in the data (may differ from header metadata)
  val dataWeeks = rows.maxOf { it.week }
  val finalWeeks = if (dataWeeks > 1) dataWeeks else totalWeeks

  val sessions = sessionMap.entries.map { (weekDay, exRows) ->
  val (week, _) = weekDay
  val dayName  = exRows.first().dayName
  val sessionName = cleanDayName(dayName)
  // Derive muscle groups from first 3 exercise names
  val muscles = exRows.take(3).joinToString(" / ") { ex ->
  ex.name.split(" ").firstOrNull() ?: ex.name
  }
  ParsedSession(
  weekNumber  = week,
  dayNumber  = exRows.first().dayNum,
  name  = sessionName,
  muscleGroups = muscles,
  exercises  = exRows.mapIndexed { idx, ex ->
  ParsedExercise(
  name  = ex.name,
  sets  = ex.sets,
  repsTarget  = ex.reps,
  notes  = ex.notes,
  orderIndex  = idx,
  rpeTarget  = ex.rpe,
  pct1rmTarget = ex.pct1rm
  )
  }
  )
  }

  // Cover image: cycle 1-5 based on sheet number prefix
  val sheetNumber = sheetName.substringBefore("-").trim().toIntOrNull() ?: 1
  val coverImage  = ((sheetNumber - 1) % 5 + 1).toString()

  // Days/week: prefer the sheet's explicit value, else the week-1 day count.
  val derivedDays = sessions.filter { it.weekNumber == 1 }.map { it.dayNumber }.distinct().size
  return ParsedProgram(
  name  = title,
  totalWeeks = finalWeeks,
  sessions  = sessions,
  coverImage = coverImage,
  category  = category,
  trackRpe  = trackRpe,
  trackOneRm = trackOneRm,
  description = description,
  coach  = coach,
  coachBio  = coachBio,
  daysPerWeek = if (daysPerWeek > 0) daysPerWeek else derivedDays,
  split  = split,
  style  = style.ifBlank { category }
  )
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private fun stripEmoji(s: String): String {
  val sb = StringBuilder()
  var i = 0
  while (i < s.length) {
  val c = s[i]
  when {
  // Surrogate pair = supplementary Unicode (all emoji live here) — skip both chars
  c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> i += 2
  // Variation selectors (FE00-FE0F) and zero-width joiner (200D) — skip
  c.code in 0xFE00..0xFE0F || c.code == 0x200D -> i++
  // Em dash / en dash — replace with hyphen
  c.code == 0x2014 || c.code == 0x2013 -> { sb.append('-'); i++ }
  else -> { sb.append(c); i++ }
  }
  }
  return sb.toString().trim()
  }

  private fun cleanDayName(raw: String): String {
  var s = stripEmoji(raw)
  s = weekdayRegex.replaceFirst(s, "")
  s = dayNumPrefixRx.replaceFirst(s, "")
  s = s.trimStart('-', ' ')
  return s.trim().ifBlank { raw.trim() }
  }

  private fun colLetter(idx: Int): String {
  if (idx <= 26) return ('A' + idx - 1).toString()
  val first  = ('A' + (idx - 1) / 26 - 1)
  val second = ('A' + (idx - 1) % 26)
  return "$first$second"
  }

  private fun extractSortKey(name: String): String = name.lowercase()

  // ── XLSX XML parsing (same approach as XlsxParser) ────────────────────────

  private fun parseSharedStrings(data: ByteArray?): List<String> {
  if (data == null) return emptyList()
  val strings = mutableListOf<String>()
  val parser  = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var inT  = false
  val sb  = StringBuilder()
  var eventType  = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  when (eventType) {
  XmlPullParser.START_TAG -> {
  if (parser.name == "t")  { inT = true; sb.clear() }
  else if (parser.name == "si") sb.clear()
  }
  XmlPullParser.TEXT  -> if (inT) sb.append(parser.text)
  XmlPullParser.END_TAG  -> {
  if (parser.name == "t")  inT = false
  else if (parser.name == "si") strings.add(sb.toString())
  }
  }
  eventType = parser.next()
  }
  return strings
  }

  private fun parseWorkbook(data: ByteArray): Map<String, String> {
  val map  = mutableMapOf<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var eventType = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  if (eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
  val name = parser.getAttributeValue(null, "name") ?: ""
  val rId  = parser.getAttributeValue(null, "r:id")
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
  val map  = mutableMapOf<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")
  var eventType = parser.eventType
  while (eventType != XmlPullParser.END_DOCUMENT) {
  if (eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
  val id  = parser.getAttributeValue(null, "Id")  ?: ""
  val target = parser.getAttributeValue(null, "Target") ?: ""
  if (id.isNotEmpty()) map[id] = target
  }
  eventType = parser.next()
  }
  return map
  }

  private fun parseSheet(data: ByteArray, sharedStrings: List<String>): Map<String, String> {
  val cells  = mutableMapOf<String, String>()
  val parser = Xml.newPullParser()
  parser.setInput(data.inputStream(), "UTF-8")

  var currentCell = ""
  var currentType = ""
  var inV  = false
  var inIs  = false
  val isText  = StringBuilder()
  var eventType  = parser.eventType

  while (eventType != XmlPullParser.END_DOCUMENT) {
  when (eventType) {
  XmlPullParser.START_TAG -> when (parser.name) {
  "c"  -> {
  currentCell = parser.getAttributeValue(null, "r") ?: ""
  currentType = parser.getAttributeValue(null, "t") ?: ""
  inV = false
  }
  "v"  -> { inV = true; isText.clear() }
  "is" -> { inIs = true; isText.clear() }
  "t"  -> if (inIs) isText.clear()
  }
  XmlPullParser.TEXT  -> if (inV || inIs) isText.append(parser.text)
  XmlPullParser.END_TAG -> when (parser.name) {
  "v"  -> {
  inV = false
  val raw = isText.toString().trim()
  if (currentCell.isNotEmpty()) {
  cells[currentCell] = when (currentType) {
  "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
  "str", "inlineStr" -> raw
  else -> raw
  }
  }
  }
  "is" -> {
  inIs = false
  if (currentCell.isNotEmpty()) cells[currentCell] = isText.toString().trim()
  }
  }
  }
  eventType = parser.next()
  }
  return cells
  }
}

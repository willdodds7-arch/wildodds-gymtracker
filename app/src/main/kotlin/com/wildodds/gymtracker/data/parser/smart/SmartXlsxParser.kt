package com.wildodds.gymtracker.data.parser.smart

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Smart xlsx parser that handles real-world training spreadsheets.
 *
 * Detection priority:
 *  1. Classic app template (W1D1 sheet names) → fast path
 *  2. Sheet-per-week naming ("Week 1", "Wk2" …)
 *  3. Week labels inside a sheet (Method 1)
 *  4. Repeating day sequences (Method 2)
 *  5. Column group heuristic (Method 3)
 *  6. Date-based grouping (Method 4)
 *  7. Flat fallback - extract everything as a single week
 */
class SmartXlsxParser {

  // ─── Public entry point ────────────────────────────────────────────────────

  fun parse(inputStream: InputStream): SmartParsedProgram {
  val warnings = mutableListOf<ParseWarning>()

  // 1. Read zip entries
  val entries = mutableMapOf<String, ByteArray>()
  ZipInputStream(inputStream.buffered()).use { zip ->
  var e = zip.nextEntry
  while (e != null) { entries[e.name] = zip.readBytes(); e = zip.nextEntry }
  }

  // 2. Parse xlsx infrastructure
  val ss = parseSharedStrings(entries["xl/sharedStrings.xml"])
  val sheetNameToRId = parseWorkbookXml(entries["xl/workbook.xml"] ?: return flatFallback(warnings))
  val rIdToTarget  = parseWorkbookRels(entries["xl/_rels/workbook.xml.rels"] ?: byteArrayOf())

  // Build named grids
  val grids = linkedMapOf<String, SheetGrid>()
  for ((name, rId) in sheetNameToRId) {
  val target = rIdToTarget[rId] ?: continue
  val path  = if (target.startsWith("worksheets/")) "xl/$target" else "xl/worksheets/$target"
  val data  = entries[path] ?: entries[target] ?: continue
  grids[name] = SheetGrid(parseSheetCells(data, ss))
  }

  val sheetNames = grids.keys.toList()

  // 3. Classic template path (W1D1 naming)
  if (isClassicTemplate(grids)) {
  return parseClassicTemplate(grids, sheetNames, warnings)
  }

  // 4. Sheet-per-week naming ("Week 1", "Wk2", …)
  val weekSheets = detectWeekSheets(grids)
  if (weekSheets.isNotEmpty()) {
  return parseSheetPerWeek(weekSheets, grids, sheetNames, warnings)
  }

  // 5. Single/primary sheet analysis
  val primary = selectPrimarySheet(grids, warnings)
  val grid  = grids[primary] ?: return flatFallback(warnings)

  warnings.add(ParseWarning(ParseWarning.Severity.INFO,
  "Selected sheet \"$primary\" as primary"))

  // 6. Detect layout
  val layout = detectLayout(grid, warnings)

  // 7. Detect weeks inside the sheet
  val (weeks, method) = detectWeeksInSheet(grid, layout, warnings)

  // 8. Extract the week template
  val days = when (layout) {
  Layout.VERTICAL  -> extractVertical(grid, weeks.first, warnings)
  Layout.HORIZONTAL -> extractHorizontal(grid, weeks.first, warnings)
  else  -> extractVertical(grid, weeks.first, warnings)
  }

  return SmartParsedProgram(
  name  = guessName(grids, primary),
  weeksDetected  = weeks.count,
  weekTemplate  = days,
  warnings  = warnings,
  rawSheetNames  = sheetNames,
  layoutDetected  = layout,
  weekDetectionMethod = method
  )
  }

  // ─── Classic template detection ───────────────────────────────────────────

  private val CLASSIC_SHEET = Regex("^W(\\d+)D(\\d+)$")

  private fun isClassicTemplate(grids: Map<String, SheetGrid>): Boolean =
  grids.keys.count { CLASSIC_SHEET.matches(it) } >= 1

  private fun parseClassicTemplate(
  grids: Map<String, SheetGrid>,
  sheetNames: List<String>,
  warnings: MutableList<ParseWarning>
  ): SmartParsedProgram {
  var programName = "Training Program"
  var totalWeeks  = 1
  var trackRpe  = false
  var trackOneRm  = false

  grids["Program Info"]?.let { g ->
  // Scan rows 1-10 for labelled fields (row index 0-based)
  for (r in 0..minOf(12, g.maxRow)) {
  val label = g[r, 0]?.lowercase()?.trim() ?: continue
  val value  = g[r, 2]?.trim() ?: g[r, 1]?.trim() ?: continue
  when {
  "name" in label  -> programName = value.ifBlank { programName }
  "week" in label  -> totalWeeks  = value.toIntOrNull() ?: totalWeeks
  "rpe" in label && "track" in label  -> trackRpe  = value.lowercase().startsWith("y") || value == "1"
  "1rm" in label || "one rm" in label -> trackOneRm = value.lowercase().startsWith("y") || value == "1"
  }
  }
  }

  val days = mutableListOf<SmartParsedDay>()
  for ((name, grid) in grids) {
  val m = CLASSIC_SHEET.matchEntire(name) ?: continue
  val week = m.groupValues[1].toInt()
  if (week != 1) continue  // only week 1 is the repeating template

  val dayNum  = m.groupValues[2].toInt()
  val dayName  = grid[0, 0] ?: "Day $dayNum"
  val muscleGroups = grid[1, 0]?.removePrefix("Muscle Groups:")?.removePrefix("Muscles:")?.trim() ?: ""

  // Find header row (contains "Exercise" or similar keyword)
  val headerRow = run {
  for (r in 0..minOf(8, grid.maxRow)) {
  val cells = (0..grid.maxCol).mapNotNull { c -> grid[r, c] }
  if (cells.any { ExerciseLineParser.classifyHeader(it) == ExerciseLineParser.ColType.EXERCISE }) return@run r
  }
  -1
  }

  // Build column map from header row
  val colMap: Map<ExerciseLineParser.ColType, Int> = if (headerRow >= 0) {
  (0..grid.maxCol).mapNotNull { c ->
  val h  = grid[headerRow, c] ?: return@mapNotNull null
  val type = ExerciseLineParser.classifyHeader(h)
  if (type != ExerciseLineParser.ColType.IGNORE) type to c else null
  }.toMap()
  } else {
  // Legacy fallback: col 0=Exercise, 1=Sets, 2=Reps, 3=Weight, 4=Notes
  mapOf(
  ExerciseLineParser.ColType.EXERCISE to 0,
  ExerciseLineParser.ColType.SETS    to 1,
  ExerciseLineParser.ColType.REPS    to 2,
  ExerciseLineParser.ColType.WEIGHT  to 3,
  ExerciseLineParser.ColType.NOTES   to 4
  )
  }

  val exCol  = colMap[ExerciseLineParser.ColType.EXERCISE] ?: 0
  val setsCol  = colMap[ExerciseLineParser.ColType.SETS]
  val repsCol  = colMap[ExerciseLineParser.ColType.REPS]
  val wtCol  = colMap[ExerciseLineParser.ColType.WEIGHT]
  val pctCol  = colMap[ExerciseLineParser.ColType.PCT_1RM]
  val rpeCol  = colMap[ExerciseLineParser.ColType.RPE]
  val restCol  = colMap[ExerciseLineParser.ColType.REST]
  val ssCol  = colMap[ExerciseLineParser.ColType.SUPERSET]
  val notesCol  = colMap[ExerciseLineParser.ColType.NOTES]

  val startRow  = if (headerRow >= 0) headerRow + 1 else 4
  val exercises  = mutableListOf<SmartParsedExercise>()
  var orderIndex = 0

  for (row in startRow..grid.maxRow) {
  val rawName = grid[row, exCol]
  if (rawName.isNullOrBlank() ||
  rawName == "← add exercise here" ||
  ExerciseLineParser.classifyHeader(rawName) == ExerciseLineParser.ColType.EXERCISE) continue

  val sets  = setsCol?.let { grid[row, it]?.toIntOrNull() } ?: 3
  val reps  = repsCol?.let { grid[row, it]?.trim()?.ifBlank { null } } ?: "8-12"
  val wtRaw  = wtCol?.let { grid[row, it] }
  val (weight, weightUnit) = parseWeightCell(wtRaw, WeightUnit.KG, rawName, warnings)
  val pct1rm = pctCol?.let { grid[row, it]?.trim()?.ifBlank { null } }
  val rpeStr = rpeCol?.let { grid[row, it]?.trim()?.ifBlank { null } }
  val restRaw = restCol?.let { grid[row, it] }
  val restSecs = restRaw?.let { ExerciseLineParser.parseRest(it) }
       ?: restRaw?.toIntOrNull()
  val ssGroup = ssCol?.let { grid[row, it]?.trim()?.ifBlank { null } }
       ?: ExerciseLineParser.supersetGroup(rawName)
  val notes = notesCol?.let { grid[row, it]?.trim()?.ifBlank { null } }

  exercises.add(SmartParsedExercise(
  name     = ExerciseLineParser.cleanName(rawName),
  nameOriginal  = rawName,
  sets     = sets,
  reps     = reps,
  weight    = weight,
  weightUnit   = weightUnit,
  rpeTarget   = rpeStr,
  pct1rmTarget  = pct1rm,
  notes    = notes,
  restSeconds   = restSecs,
  supersetGroup  = ssGroup,
  orderIndex   = orderIndex++
  ))
  }

  if (exercises.isNotEmpty())
  days.add(SmartParsedDay(dayName, dayNum, exercises, muscleGroups))
  }

  days.sortBy { it.dayNumber }
  return SmartParsedProgram(
  name  = programName,
  weeksDetected  = totalWeeks,
  weekTemplate  = days,
  warnings  = warnings,
  rawSheetNames  = sheetNames,
  layoutDetected  = Layout.VERTICAL,
  weekDetectionMethod = "Wild Odds template (W1D1 sheet names)"
  )
  }

  // ─── Sheet-per-week detection ─────────────────────────────────────────────

  private fun detectWeekSheets(grids: Map<String, SheetGrid>): Map<Int, SheetGrid> {
  val result = mutableMapOf<Int, SheetGrid>()
  for ((name, grid) in grids) {
  val wNum = ExerciseLineParser.extractWeekNumber(name) ?: continue
  result[wNum] = grid
  }
  return result.toSortedMap()
  }

  private fun parseSheetPerWeek(
  weekSheets: Map<Int, SheetGrid>,
  allGrids: Map<String, SheetGrid>,
  sheetNames: List<String>,
  warnings: MutableList<ParseWarning>
  ): SmartParsedProgram {
  val week1Grid = weekSheets[weekSheets.keys.first()]!!
  val layout  = detectLayout(week1Grid, warnings)
  val days  = when (layout) {
  Layout.VERTICAL  -> extractVertical(week1Grid, WeekRange(0, week1Grid.maxRow), warnings)
  Layout.HORIZONTAL -> extractHorizontal(week1Grid, WeekRange(0, week1Grid.maxRow), warnings)
  else  -> extractVertical(week1Grid, WeekRange(0, week1Grid.maxRow), warnings)
  }
  return SmartParsedProgram(
  name  = guessName(allGrids, weekSheets.keys.first().toString()),
  weeksDetected  = weekSheets.size,
  weekTemplate  = days,
  warnings  = warnings,
  rawSheetNames  = sheetNames,
  layoutDetected  = layout,
  weekDetectionMethod = "Sheet-per-week naming (${weekSheets.size} week sheets found)"
  )
  }

  // ─── Sheet selection ──────────────────────────────────────────────────────

  private val IGNORE_PATTERNS = listOf("1rm", "food", "nutrition", "bmi", "calculator", "1 rep max", "macros")
  private val PREFER_PATTERNS = listOf("template", "program", "training", "schedule", "plan")

  private fun selectPrimarySheet(
  grids: Map<String, SheetGrid>,
  warnings: MutableList<ParseWarning>
  ): String {
  // Prefer sheets by name
  grids.keys.firstOrNull { name ->
  PREFER_PATTERNS.any { name.lowercase().contains(it) }
  }?.let { return it }

  // Skip ignore patterns; pick the sheet with the most data
  val candidates = grids.filter { (name, _) ->
  IGNORE_PATTERNS.none { name.lowercase().contains(it) } &&
  name.lowercase() != "program info"
  }
  return candidates.maxByOrNull { (_, g) -> g.cells.size }?.key
  ?: grids.keys.first().also {
  warnings.add(ParseWarning(ParseWarning.Severity.WARNING,
  "Could not identify primary sheet; using first sheet"))
  }
  }

  // ─── Layout detection ─────────────────────────────────────────────────────

  private fun detectLayout(grid: SheetGrid, warnings: MutableList<ParseWarning>): Layout {
  // Scan first 5 rows for header keywords
  val headerKeywords = setOf("exercise", "sets", "reps", "weight", "movement", "name", "kg", "lbs", "notes", "lift")
  val columnKeywords = setOf("week", "day", "session", "monday", "tuesday", "wednesday", "thursday", "friday")

  for (r in 0..minOf(6, grid.maxRow)) {
  val row = grid.row(r).filterNotNull().map { it.lowercase() }
  val headerHits = row.count { cell -> headerKeywords.any { it in cell } }
  if (headerHits >= 2) return Layout.VERTICAL
  }

  // Check if col 0 has day/week labels → horizontal
  val col0 = grid.col(0).filterNotNull().map { it.lowercase() }
  val col0Hits = col0.count { cell -> columnKeywords.any { it in cell } }
  if (col0Hits >= 2 && grid.maxCol > 3) return Layout.HORIZONTAL

  // Check for hybrid: header row + stacked day sections
  warnings.add(ParseWarning(ParseWarning.Severity.INFO,
  "Layout ambiguous; defaulting to vertical scan"))
  return Layout.VERTICAL
  }

  // ─── Week detection ───────────────────────────────────────────────────────

  data class WeekRange(val startRow: Int, val endRow: Int)
  data class WeekDetectionResult(val count: Int, val first: WeekRange)

  private fun detectWeeksInSheet(
  grid: SheetGrid,
  layout: Layout,
  warnings: MutableList<ParseWarning>
  ): Pair<WeekDetectionResult, String> {

  // Method 1: Explicit week labels
  method1(grid)?.let { return it to "Method 1 - explicit week labels" }

  // Method 2: Repeating day sequences
  method2(grid)?.let { return it to "Method 2 - repeating day labels" }

  // Method 3: Column group counting (horizontal layouts)
  if (layout == Layout.HORIZONTAL) {
  method3(grid)?.let { return it to "Method 3 - column group heuristic" }
  }

  // Method 4: Date detection
  method4(grid)?.let { return it to "Method 4 - date-based grouping" }

  // Fallback - everything is one week
  warnings.add(ParseWarning(ParseWarning.Severity.WARNING,
  "Week count could not be detected; treating entire sheet as one week"))
  return WeekDetectionResult(1, WeekRange(0, grid.maxRow)) to "Fallback - single week assumed"
  }

  private fun method1(grid: SheetGrid): WeekDetectionResult? {
  // Scan all cells for "Week 1", "W1", "Block 2" etc.
  val weekRows = mutableListOf<Pair<Int, Int>>()  // row, weekNumber
  for (r in 0..grid.maxRow) {
  for (c in 0..grid.maxCol) {
  val v = grid[r, c] ?: continue
  val wNum = ExerciseLineParser.extractWeekNumber(v) ?: continue
  weekRows.add(r to wNum)
  }
  }
  if (weekRows.size < 2) return null
  val sorted  = weekRows.sortedBy { it.first }
  val weekCount = sorted.map { it.second }.distinct().size
  // Week 1 spans from first week label row to just before week 2 label
  val startRow  = sorted.first().first
  val endRow  = if (sorted.size > 1) sorted[1].first - 1 else grid.maxRow
  return WeekDetectionResult(weekCount, WeekRange(startRow + 1, endRow))
  }

  private fun method2(grid: SheetGrid): WeekDetectionResult? {
  // Find rows that look like day labels
  val dayRows = (0..grid.maxRow).filter { r ->
  val v = grid[r, 0] ?: return@filter false
  ExerciseLineParser.isDayLabel(v)
  }
  if (dayRows.size < 2) return null

  // Collect day label texts in order
  val dayLabels = dayRows.map { r -> grid[r, 0]!!.lowercase() }

  // Look for a repeating sequence
  for (seqLen in 1..(dayLabels.size / 2)) {
  val seq = dayLabels.take(seqLen)
  if (dayLabels.size % seqLen == 0) {
  val repeats = dayLabels.chunked(seqLen)
  if (repeats.all { it == seq }) {
  val weekCount = dayLabels.size / seqLen
  val endRow  = if (dayRows.size > seqLen) dayRows[seqLen] - 1 else grid.maxRow
  return WeekDetectionResult(weekCount, WeekRange(dayRows[0], endRow))
  }
  }
  }
  // Even if no exact repeat, treat the day count as one week
  val endRow = if (dayRows.size > 1) dayRows[1] - 1 else grid.maxRow
  return WeekDetectionResult(1, WeekRange(dayRows[0], grid.maxRow))
  }

  private fun method3(grid: SheetGrid): WeekDetectionResult? {
  // For horizontal layouts, look for repeating header groups across columns
  val row0 = grid.row(0).filterNotNull()
  val weekCols = row0.indices.filter { c ->
  val v = row0[c].lowercase()
  ExerciseLineParser.isWeekLabel(v) || ExerciseLineParser.isDayLabel(v)
  }
  if (weekCols.size < 2) return null
  return WeekDetectionResult(weekCols.size, WeekRange(0, grid.maxRow))
  }

  private fun method4(grid: SheetGrid): WeekDetectionResult? {
  // Look for date-like cells (serial numbers or date strings)
  // Excel dates are stored as days since 1900-01-01
  val DATE_SERIAL_MIN = 40000.0  // roughly year 2009
  val DATE_SERIAL_MAX = 50000.0  // roughly year 2036
  val dateValues = grid.cells.values.mapNotNull { v ->
  v.toDoubleOrNull()?.takeIf { it in DATE_SERIAL_MIN..DATE_SERIAL_MAX }
  }
  if (dateValues.size < 4) return null

  // Count distinct ISO weeks (serial / 7 = rough week number)
  val weekNums = dateValues.map { (it / 7).toInt() }.distinct().size
  if (weekNums < 2) return null
  return WeekDetectionResult(weekNums, WeekRange(0, grid.maxRow / weekNums))
  }

  // ─── Vertical layout extraction ───────────────────────────────────────────

  private fun extractVertical(
  grid: SheetGrid,
  range: WeekRange,
  warnings: MutableList<ParseWarning>
  ): List<SmartParsedDay> {
  // 1. Find header row
  val headerRow = findHeaderRow(grid, range)

  // 2. Map columns to types
  val colTypes = if (headerRow >= 0) {
  (0..grid.maxCol).associate { c ->
  val h = grid[headerRow, c] ?: ""
  c to ExerciseLineParser.classifyHeader(h)
  }
  } else {
  // Guess: col 0 = exercise, col 1 = sets, col 2 = reps, col 3 = weight, col 4 = notes
  mapOf(0 to ExerciseLineParser.ColType.EXERCISE,
  1 to ExerciseLineParser.ColType.SETS,
  2 to ExerciseLineParser.ColType.REPS,
  3 to ExerciseLineParser.ColType.WEIGHT,
  4 to ExerciseLineParser.ColType.NOTES)
  }

  val exCol  = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.EXERCISE }?.key ?: 0
  val setsCol  = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.SETS }?.key
  val repsCol  = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.REPS }?.key
  val wtCol  = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.WEIGHT }?.key
  val notesCol  = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.NOTES }?.key
  val restCol  = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.REST }?.key
  val rpeTargetCol = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.RPE }?.key
  val pctCol  = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.PCT_1RM }?.key
  val ssCol  = colTypes.entries.firstOrNull { it.value == ExerciseLineParser.ColType.SUPERSET }?.key

  // Infer weight unit from the column header
  val wtHeader  = if (wtCol != null && headerRow >= 0) grid[headerRow, wtCol] ?: "" else ""
  val defaultWtUnit = ExerciseLineParser.weightUnitFromHeader(wtHeader)

  if (exCol == -1) {
  warnings.add(ParseWarning(ParseWarning.Severity.WARNING, "No 'Exercise' column found; guessing column A"))
  }

  // 3. Walk rows
  val days = mutableListOf<SmartParsedDay>()
  var currentDayName = "Day 1"
  var dayNumber  = 1
  var exercises  = mutableListOf<SmartParsedExercise>()
  var orderIndex  = 0

  val startRow = maxOf(range.startRow, if (headerRow >= 0) headerRow + 1 else 0)

  for (r in startRow..range.endRow) {
  val rawName = grid[r, exCol]

  // Day label row: one populated cell, looks like a day name
  if (rawName != null && ExerciseLineParser.isDayLabel(rawName) &&
  grid.row(r).filterNotNull().size <= 2) {
  if (exercises.isNotEmpty()) {
  days.add(SmartParsedDay(currentDayName, dayNumber++, exercises))
  exercises = mutableListOf()
  orderIndex = 0
  }
  currentDayName = rawName.trim()
  continue
  }

  if (rawName.isNullOrBlank()) {
  // Blank row - could be a section break
  if (exercises.size > 3) {
  days.add(SmartParsedDay(currentDayName, dayNumber++, exercises))
  exercises = mutableListOf()
  orderIndex = 0
  currentDayName = "Day $dayNumber"
  }
  continue
  }

  // Skip obvious header values appearing in data rows
  if (ExerciseLineParser.classifyHeader(rawName) == ExerciseLineParser.ColType.EXERCISE) continue

  // Exercise row
  val cleanedName  = ExerciseLineParser.cleanName(rawName)

  // Parse sets & reps
  val setsRaw  = setsCol?.let { grid[r, it] }
  val repsRaw  = repsCol?.let { grid[r, it] }
  val wtRaw  = wtCol?.let { grid[r, it] }
  val notesRaw = notesCol?.let { grid[r, it] }
  val restRaw  = restCol?.let { grid[r, it] }
  val rpeRaw  = rpeTargetCol?.let { grid[r, it] }
  val pctRaw  = pctCol?.let { grid[r, it] }
  val ssRaw  = ssCol?.let { grid[r, it] }

  // Try to get sets/reps from dedicated cols, else parse inline from name
  val inline = ExerciseLineParser.parseInline(rawName)
  val sets  = setsRaw?.toIntOrNull() ?: inline.sets ?: 3
  val reps  = repsRaw?.trim()?.ifBlank { null } ?: inline.reps ?: "8-12"

  // Weight — if weight cell looks like a bare percentage, treat it as %1RM
  val (weight, weightUnit) = if (wtRaw != null && wtRaw.trim().matches(Regex("""^\d+(\.\d+)?\s*%$"""))) {
  Pair(null, WeightUnit.UNKNOWN)
  } else {
  parseWeightCell(wtRaw, defaultWtUnit, rawName, warnings)
  }

  // %1RM target: explicit column wins, then bare-% weight cell, then inline inline
  val pct1rmTarget = pctRaw?.trim()?.ifBlank { null }
  ?: wtRaw?.takeIf { it.trim().matches(Regex("""^\d+(\.\d+)?\s*%$""")) }
  ?: if (inline.notes?.contains("%") == true) inline.notes else null

  // RPE target: explicit column
  val rpeTarget = rpeRaw?.trim()?.ifBlank { null }

  // Superset group: explicit column wins, then name-prefix detection
  val supersetGroup = ssRaw?.trim()?.ifBlank { null }
  ?: ExerciseLineParser.supersetGroup(rawName)

  // Notes: combine explicit notes col + inline RPE/RIR annotations
  val noteParts = mutableListOf<String>()
  notesRaw?.let { noteParts.add(it) }
  // Only add inline notes if they don't duplicate the dedicated columns
  if (rpeTarget == null) inline.notes?.let { noteParts.add(it) }
  val restSecs = restRaw?.let { ExerciseLineParser.parseRest(it) }
      ?: restRaw?.toIntOrNull()

  exercises.add(SmartParsedExercise(
  name     = cleanedName,
  nameOriginal  = rawName,
  sets     = sets,
  reps     = reps,
  weight    = weight,
  weightUnit   = weightUnit,
  rpeTarget   = rpeTarget,
  pct1rmTarget  = pct1rmTarget,
  notes    = noteParts.joinToString("; ").ifBlank { null },
  restSeconds   = restSecs,
  supersetGroup  = supersetGroup,
  orderIndex   = orderIndex++
  ))
  }

  if (exercises.isNotEmpty()) days.add(SmartParsedDay(currentDayName, dayNumber, exercises))
  if (days.isEmpty()) {
  warnings.add(ParseWarning(ParseWarning.Severity.ERROR,
  "No exercises could be extracted from vertical layout"))
  }
  return days
  }

  // ─── Horizontal layout extraction ─────────────────────────────────────────

  private fun extractHorizontal(
  grid: SheetGrid,
  range: WeekRange,
  warnings: MutableList<ParseWarning>
  ): List<SmartParsedDay> {
  // Row 0 = exercise names (headers)
  // Col 0 = day/session labels
  // Cells contain "3x8", "3x8 @ 80kg" etc.

  val exerciseNames = (1..grid.maxCol).mapNotNull { c ->
  val v = grid[0, c] ?: return@mapNotNull null
  c to ExerciseLineParser.cleanName(v)
  }

  val days = mutableListOf<SmartParsedDay>()
  var dayNumber = 1

  for (r in 1..range.endRow) {
  val dayLabel = grid[r, 0] ?: continue
  if (!ExerciseLineParser.isDayLabel(dayLabel) &&
  !ExerciseLineParser.isWeekLabel(dayLabel)) {
  continue
  }

  val exercises = mutableListOf<SmartParsedExercise>()
  var orderIndex = 0

  for ((col, exName) in exerciseNames) {
  val cellVal = grid[r, col] ?: continue
  val parsed  = ExerciseLineParser.parseInline(cellVal)
  exercises.add(SmartParsedExercise(
  name     = exName,
  nameOriginal  = exName,
  sets     = parsed.sets ?: 3,
  reps     = parsed.reps ?: "8-12",
  weight    = parsed.weight,
  weightUnit   = parsed.weightUnit,
  rpeTarget   = null,
  pct1rmTarget  = null,
  notes    = parsed.notes,
  restSeconds   = null,
  supersetGroup  = null,
  orderIndex   = orderIndex++
  ))
  }

  if (exercises.isNotEmpty())
  days.add(SmartParsedDay(dayLabel.trim(), dayNumber++, exercises))
  }

  if (days.isEmpty()) {
  warnings.add(ParseWarning(ParseWarning.Severity.WARNING,
  "Horizontal layout detected but no day rows extracted; retrying as vertical"))
  return extractVertical(grid, range, warnings)
  }
  return days
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private fun findHeaderRow(grid: SheetGrid, range: WeekRange): Int {
  for (r in range.startRow..minOf(range.startRow + 10, range.endRow)) {
  val cells = grid.row(r).filterNotNull()
  val classifiedCount = cells.count { v ->
  ExerciseLineParser.classifyHeader(v) != ExerciseLineParser.ColType.IGNORE &&
  ExerciseLineParser.classifyHeader(v) != ExerciseLineParser.ColType.DAY
  }
  if (classifiedCount >= 2) return r
  }
  return -1
  }

  private fun parseWeightCell(
  raw: String?,
  defaultUnit: WeightUnit,
  context: String,
  warnings: MutableList<ParseWarning>
  ): Pair<Float?, WeightUnit> {
  if (raw == null) {
  // Try inline from context
  val inline = ExerciseLineParser.parseInline(context)
  return Pair(inline.weight, inline.weightUnit.takeIf { it != WeightUnit.UNKNOWN } ?: defaultUnit)
  }
  val num = raw.toFloatOrNull()
  if (num != null) return Pair(num, defaultUnit.takeIf { it != WeightUnit.UNKNOWN } ?: WeightUnit.UNKNOWN)

  val parsed = ExerciseLineParser.parseInline(raw)
  if (parsed.weight != null) return Pair(parsed.weight, parsed.weightUnit)

  warnings.add(ParseWarning(ParseWarning.Severity.INFO,
  "Could not parse weight value", raw))
  return Pair(null, WeightUnit.UNKNOWN)
  }

  private fun guessName(grids: Map<String, SheetGrid>, hint: String): String {
  // Try Program Info sheet first
  grids["Program Info"]?.let { g -> g[2, 2]?.takeIf { it.isNotBlank() }?.let { return it } }
  // Try first cell of primary sheet
  grids[hint]?.get(0, 0)?.takeIf { it.isNotBlank() && it.length < 60 }?.let { return it }
  return "Training Program"
  }

  private fun flatFallback(warnings: MutableList<ParseWarning>): SmartParsedProgram {
  warnings.add(ParseWarning(ParseWarning.Severity.ERROR,
  "Could not read workbook structure; file may be corrupted or unsupported"))
  return SmartParsedProgram(
  name = "Training Program", weeksDetected = 1, weekTemplate = emptyList(),
  warnings = warnings, rawSheetNames = emptyList(),
  layoutDetected = Layout.UNKNOWN, weekDetectionMethod = "None - parse failed"
  )
  }

  // ─── xlsx XML parsing (same approach as existing parser) ─────────────────

  private fun parseSharedStrings(data: ByteArray?): List<String> {
  if (data == null) return emptyList()
  val strings = mutableListOf<String>()
  val sb = StringBuilder()
  val p  = Xml.newPullParser().also { it.setInput(data.inputStream(), "UTF-8") }
  var inT = false
  var evt = p.eventType
  while (evt != XmlPullParser.END_DOCUMENT) {
  when (evt) {
  XmlPullParser.START_TAG -> when (p.name) {
  "si" -> sb.clear()
  "t"  -> { inT = true; sb.clear() }
  }
  XmlPullParser.TEXT  -> if (inT) sb.append(p.text)
  XmlPullParser.END_TAG  -> when (p.name) {
  "t"  -> inT = false
  "si" -> strings.add(sb.toString())
  }
  }
  evt = p.next()
  }
  return strings
  }

  private fun parseWorkbookXml(data: ByteArray): Map<String, String> {
  val map = mutableMapOf<String, String>()
  val p  = Xml.newPullParser().also { it.setInput(data.inputStream(), "UTF-8") }
  var evt = p.eventType
  while (evt != XmlPullParser.END_DOCUMENT) {
  if (evt == XmlPullParser.START_TAG && p.name == "sheet") {
  val name = p.getAttributeValue(null, "name") ?: ""
  val rId  = p.getAttributeValue(null, "r:id")
  ?: p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
  ?: ""
  if (name.isNotEmpty() && rId.isNotEmpty()) map[name] = rId
  }
  evt = p.next()
  }
  return map
  }

  private fun parseWorkbookRels(data: ByteArray): Map<String, String> {
  if (data.isEmpty()) return emptyMap()
  val map = mutableMapOf<String, String>()
  val p  = Xml.newPullParser().also { it.setInput(data.inputStream(), "UTF-8") }
  var evt = p.eventType
  while (evt != XmlPullParser.END_DOCUMENT) {
  if (evt == XmlPullParser.START_TAG && p.name == "Relationship") {
  val id  = p.getAttributeValue(null, "Id") ?: ""
  val target = p.getAttributeValue(null, "Target") ?: ""
  if (id.isNotEmpty()) map[id] = target
  }
  evt = p.next()
  }
  return map
  }

  private fun parseSheetCells(data: ByteArray, ss: List<String>): Map<String, String> {
  val cells = mutableMapOf<String, String>()
  val p  = Xml.newPullParser().also { it.setInput(data.inputStream(), "UTF-8") }
  var addr  = ""
  var type  = ""
  var inV  = false
  var inIs  = false
  val sb  = StringBuilder()
  var evt  = p.eventType
  while (evt != XmlPullParser.END_DOCUMENT) {
  when (evt) {
  XmlPullParser.START_TAG -> when (p.name) {
  "c"  -> { addr = p.getAttributeValue(null, "r") ?: ""; type = p.getAttributeValue(null, "t") ?: ""; inV = false }
  "v"  -> { inV = true;  sb.clear() }
  "is" -> { inIs = true; sb.clear() }
  "t"  -> if (inIs) sb.clear()
  }
  XmlPullParser.TEXT  -> if (inV || inIs) sb.append(p.text)
  XmlPullParser.END_TAG  -> when (p.name) {
  "v"  -> {
  inV = false
  val raw = sb.toString().trim()
  if (addr.isNotEmpty()) cells[addr] = when (type) {
  "s" -> ss.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
  "str", "inlineStr" -> raw
  else -> raw
  }
  }
  "is" -> { inIs = false; if (addr.isNotEmpty()) cells[addr] = sb.toString().trim() }
  }
  }
  evt = p.next()
  }
  return cells
  }
}

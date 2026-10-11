package com.wildodds.gymtracker.data.importer.core

import com.wildodds.gymtracker.data.importer.ConfidenceBreakdown
import com.wildodds.gymtracker.data.importer.ExerciseRef
import com.wildodds.gymtracker.data.importer.FlagSeverity
import com.wildodds.gymtracker.data.importer.ImportFlag
import com.wildodds.gymtracker.data.importer.LoadKind
import com.wildodds.gymtracker.data.importer.LoadSpec
import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.importer.ProgramStructure
import com.wildodds.gymtracker.data.importer.RepSpec
import com.wildodds.gymtracker.data.importer.RestSpec
import com.wildodds.gymtracker.data.importer.SessionDraft
import com.wildodds.gymtracker.data.importer.SessionType
import com.wildodds.gymtracker.data.importer.SlotDraft
import com.wildodds.gymtracker.data.importer.WeekDraft
import com.wildodds.gymtracker.data.importer.WeightUnit
import com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex
import com.wildodds.gymtracker.data.importer.vocab.LanguageDetector
import com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument
import com.wildodds.gymtracker.data.reader.ingest.Grid
import java.util.Locale

/**
 * The import core: one reader for every shape a training program arrives in.
 *
 * Every source — pasted text, a .txt/.md file, a CSV, every sheet of a workbook, a PDF's text, the
 * OCR of a screenshot — is first turned into the same thing: **blocks of rows of cells**. A text
 * line is a row of one cell (or several, when it is tab-, pipe- or column-aligned); a spreadsheet
 * row is a row; an OCR'd table row is a row. Everything after that is format-blind, which is the
 * point: a fix made for screenshots is a fix for spreadsheets and pasted text too.
 *
 * Per block, rows are read in order with one piece of state, the **column map**. A row of column
 * names ("Exercise | Sets | Reps", "Day, Exercise, Sets x Reps", "Exercise | Week 1 | Week 2")
 * sets it; until it changes, every row is read cell by cell through it. Without a map a row is
 * read as a line of text: a week marker, a day header, an exercise with its prescription, a name
 * whose prescription is on the next line, a labelled field ("Sets: 4"), or prose to ignore.
 *
 * Days and weeks come from wherever the document puts them — header lines, a Day or Week column,
 * sheet names, side-by-side column blocks, week-per-column grids — and land in one builder.
 *
 * What it never does: invent a number. A set count or rep target that the source did not state
 * stays null, and the review step says so.
 */
object ProgramParser {

  // ── public entry ─────────────────────────────────────────────────────────────

  data class Parsed(
    val draft: ProgramImportDraft,
    val exerciseCount: Int,
    val usedColumns: Boolean,
    /** Exercises with at least a set count or a rep target. */
    val prescribed: Int = 0,
    /** Exercises with both a set count and a rep target (or a specialist scheme). */
    val complete: Int = 0
  )

  fun parse(doc: ExtractedDocument, name: String): Parsed? {
    val grids = gridBlocks(doc)
    val spreadsheet = doc.sourceKind in SPREADSHEETS
    val chat = isChatExport(doc.sourceText)
    val text = if (chat) chatText(doc.sourceText) else doc.text
    // A spreadsheet is read from its grids. A document (markdown, HTML, Word, a chat that sniffed as
    // CSV) is read from its text — which still carries its tables as aligned rows — and from its
    // grids when it has any, and the reading with more complete exercises wins.
    val readings = buildList {
      // Text first: on a tie (maxBy keeps the first) a document keeps its headings ("Push day").
      if (!spreadsheet || grids.isEmpty() || chat) if (text.isNotBlank()) add(read(textBlocks(text), name, doc.tables.firstOrNull()?.name, doc.title))
      if (grids.isNotEmpty() && !chat) add(read(grids, name, doc.tables.firstOrNull()?.name, doc.title))
    }.filterNotNull()
    val best = readings.maxByOrNull { it.second } ?: return null
    val parsed = best.first
    // A document with nothing that looks like a program — no prescription anywhere and no day, week
    // or column structure — is not a program, however many words in it resolve to movements.
    // A complete prescription means sets AND reps ("Roast for 60 min" in a recipe is neither).
    if (parsed.complete == 0 && !parsed.draft.hasExplicitStructure) return null
    return parsed
  }

  private val GENERIC_TABLE = Regex("(?i)^(sheet|tabelle|feuille|hoja|foglio|planilha|blad|table)\\s*\\d*$")

  private val SPREADSHEETS = setOf(
    com.wildodds.gymtracker.data.reader.ingest.SourceKind.XLSX,
    com.wildodds.gymtracker.data.reader.ingest.SourceKind.XLS,
    com.wildodds.gymtracker.data.reader.ingest.SourceKind.ODS,
    com.wildodds.gymtracker.data.reader.ingest.SourceKind.DELIMITED
  )

  /** (reading, quality) — quality ranks complete exercises far above bare names. */
  private fun read(blocks: List<Block>, name: String, gridName: String?, docTitle: String?): Pair<Parsed, Int>? {
    if (blocks.isEmpty()) return null
    val builder = Builder()
    val multiBlock = blocks.count { b -> b.rows.any { r -> r.any { it.isNotBlank() } } } > 1
    for (b in blocks) readBlock(b, builder, multiBlock)
    // The name: a title the document states for itself beats the file name; a "name" that is really
    // a header row ("Exercise	Sets	Reps") or a CSV line is replaced by the sheet's own name.
    val firstRow = blocks.firstOrNull()?.rows?.firstOrNull { r -> r.any { it.isNotBlank() } }
      ?.filter { it.isNotBlank() }?.joinToString(" ")?.let { Lines.key(it) }
    val badName = name.isBlank() || GENERIC_TABLE.matches(name.trim()) || name.contains('\t') || name.count { it == ',' } >= 2 ||
      (firstRow != null && Lines.key(name) == firstRow && headerOf(blocks.first().rows.first { r -> r.any { it.isNotBlank() } }) != null)
    // A sheet's own name ("block", "Log", "Smith Off-Season") — unless it is a default like "Sheet1".
    val sheetName = gridName?.substringBeforeLast('.')?.trim()
      ?.takeIf { it.isNotBlank() && !Regex("(?i)^(sheet|tabelle|feuille|hoja|foglio|planilha|blad|table)\\s*\\d*$").matches(it) }
    // A document's own title (an HTML <title>, a Word heading) beats a table's name.
    val titled = docTitle?.trim()?.takeIf { it.isNotBlank() && it.length <= 80 && !it.contains('\t') }
    val finalName = builder.title ?: if (badName) titled ?: sheetName ?: "Imported Program" else name
    val draft = builder.build(finalName) ?: return null
    val p = Parsed(draft, builder.exerciseCount, builder.usedColumns, builder.prescribed, builder.complete)
    return p to (builder.complete * 3 + builder.prescribed * 2 + builder.exerciseCount)
  }

  // ── blocks ───────────────────────────────────────────────────────────────────

  data class Block(val title: String?, val rows: List<List<String>>, val fromGrid: Boolean)

  private fun gridBlocks(doc: ExtractedDocument): List<Block> =
    doc.tables.filter { !it.isEmpty() }.flatMap { g ->
      val rows = g.rows.filterIndexed { i, _ -> i !in g.hiddenRows }
        .map { r -> r.filterIndexed { c, _ -> c !in g.hiddenCols }.map { it.trim() } }
      splitSideBySide(Block(g.name, rows, fromGrid = true))
    }

  private fun textBlocks(raw: String): List<Block> {
    if (raw.isBlank()) return emptyList()
    val translated = LanguageDetector.detect(raw).let { d -> LanguageDetector.dialectFor(d)?.translate(raw) ?: raw }
    return splitSideBySide(Block(null, textRows(translated), fromGrid = false))
  }

  // ── chat exports ─────────────────────────────────────────────────────────────

  /** "[21/03/2026 19:04] Coach: …", "21/03/2026, 19:04 - Coach: …" (WhatsApp, Messenger, Signal). */
  private val CHAT_PREFIX = Regex(
    "^\\s*(?:\\[\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4},?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[apAP][mM])?]\\s*" +
      "|\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4},?\\s+\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[apAP][mM])?\\s*-\\s*)" +
      "[^:]{1,40}:\\s*")

  private fun isChatExport(text: String): Boolean {
    val lines = text.lines().filter { it.isNotBlank() }
    return lines.size >= 2 && lines.count { CHAT_PREFIX.containsMatchIn(it) } >= lines.size * 0.5
  }

  private fun chatText(text: String): String = text.lines().joinToString("\n") { CHAT_PREFIX.replace(it, "") }

  /** Text lines → rows of cells: tabs, pipes, 3+ aligned spaces, or a CSV-shaped paste. */
  private fun textRows(text: String): List<List<String>> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
      .map { it.replace(Regex("^\\s*>+\\s?"), "") }
    val nonBlank = lines.filter { it.isNotBlank() }
    val csvLike = looksLikeCsv(nonBlank)
    return lines.mapNotNull { line ->
      val l = line.trimEnd()
      if (l.isBlank()) return@mapNotNull emptyList()
      // Markdown table separator rows: |---|:--:|
      if (Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$").matches(l)) return@mapNotNull null
      when {
        csvLike -> csvSplit(l, if (csvLike && l.count { it == ';' } > l.count { it == ',' }) ';' else ',')
        l.contains('\t') -> l.split('\t').map { it.trim() }.dropLastWhile { it.isEmpty() }
        l.count { it == '|' } >= 1 && !Regex("\\d\\s*\\|\\s*\\d").containsMatchIn(l) ->
          l.trim().trim('|').split('|').map { it.trim() }
        Regex("\\S\\s{3,}\\S").containsMatchIn(l.trim()) -> l.trim().split(Regex("\\s{3,}")).map { it.trim() }
        else -> listOf(l.trim())
      }
    }
  }

  private fun looksLikeCsv(lines: List<String>): Boolean {
    if (lines.size < 2) return false
    for (sep in listOf(',', ';')) {
      val counts = lines.map { l -> csvSplit(l, sep).size - 1 }
      val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
      if (common.key >= 2 && common.value >= lines.size * 0.7) return true
    }
    return false
  }

  private fun csvSplit(line: String, sep: Char): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var q = false
    var i = 0
    while (i < line.length) {
      val c = line[i]
      when {
        c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
        c == '"' -> q = !q
        c == sep && !q -> { out += sb.toString().trim(); sb.clear() }
        else -> sb.append(c)
      }
      i++
    }
    out += sb.toString().trim()
    return out
  }

  /**
   * Days laid out side by side. Two shapes: column groups separated by an empty column, each a
   * little table of its own; and a row of day names whose columns each hold that day's lines.
   * Either way the block is cut into one sub-block per day, read left to right.
   */
  private fun splitSideBySide(b: Block): List<Block> {
    val width = b.rows.maxOfOrNull { it.size } ?: 0
    if (width < 2) return listOf(b)
    val rows = b.rows.map { r -> List(width) { c -> r.getOrNull(c).orEmpty() } }

    // 1) Empty separator columns.
    val empty = (0 until width).map { c -> rows.all { it[c].isBlank() } }
    val groups = ArrayList<IntRange>()
    var start = -1
    for (c in 0..width) {
      val isEmpty = c == width || empty[c]
      if (!isEmpty && start < 0) start = c
      if (isEmpty && start >= 0) { groups += start until c; start = -1 }
    }
    if (groups.size >= 2 && groups.all { g -> looksLikeProgramTable(rows, g) }) {
      return groups.map { g -> Block(b.title, rows.map { r -> r.subList(g.first, g.last + 1) }, b.fromGrid) }
    }

    // 2) A row of day names over columns of exercise lines.
    for ((i, r) in rows.withIndex()) {
      val cells = r.withIndex().filter { it.value.isNotBlank() }
      if (cells.size < 2) continue
      if (!cells.all { (_, v) -> Lines.explicitDay(v) != null && !Rx.parseLine(v).hasRx }) continue
      val below = rows.drop(i + 1).take(6)
      val cols = cells.map { it.index }
      val supported = cols.count { c -> below.count { row -> Rx.parseLine(row[c]).hasRx } >= 1 }
      if (supported < 2) continue
      val before = if (i > 0) listOf(Block(b.title, rows.take(i).map { it }, b.fromGrid)) else emptyList()
      val bounds = cols + width
      val parts = cols.indices.map { k ->
        val range = bounds[k] until bounds[k + 1]
        Block(b.title, rows.drop(i).map { row -> listOf(row.subList(range.first, range.last + 1).filter { it.isNotBlank() }.joinToString(" ")) }, b.fromGrid)
      }
      return before + parts
    }
    return listOf(b)
  }

  private fun looksLikeProgramTable(rows: List<List<String>>, cols: IntRange): Boolean {
    var texty = 0; var numbery = 0
    for (r in rows) {
      val cells = r.subList(cols.first, cols.last + 1).filter { it.isNotBlank() }
      if (cells.isEmpty()) continue
      if (cells.any { c -> c.any { it.isLetter() } && Rx.setsField(c).first == null }) texty++
      if (cells.any { c -> Rx.setsField(c).first != null || Rx.parseLine(c).hasRx }) numbery++
    }
    return texty >= 2 && numbery >= 2
  }

  // ── column roles ─────────────────────────────────────────────────────────────

  enum class Role { EXERCISE, SETS, REPS, SETSREPS, DAY, WEEK, SETNUM, LOAD, RPE, REST, NOTES, TEMPO, SUPERSET, WEEKCOL, SETCOL, OTHER }

  data class ColumnMap(
    val roles: Map<Int, Role>,
    val weekCols: Map<Int, Int>,
    /** (sets column, reps column) per week, when the header repeats Sets/Reps once per week. */
    val weekPairs: List<Pair<Int, Int>> = emptyList()
  ) {
    fun col(r: Role) = roles.entries.firstOrNull { it.value == r }?.key
    val hasExercise get() = col(Role.EXERCISE) != null
  }

  private val ROLE_WORDS: List<Pair<Regex, Role>> = listOf(
    Regex("^(sets?\\s*(x|×|/|&|and)\\s*reps?|setsxreps|sets\\s*/\\s*reps|prescription|scheme|volume|work|sets\\s*&\\s*reps|set\\s*x\\s*rep|protocol)$") to Role.SETSREPS,
    Regex("^(exercises?|movements?|lifts?|exercise\\s*name|name|drill|übung|übungen|ubung|uebung|ubungen|uebungen|ejercicios?|exercices?|exerc[ií]cios?|esercizi[oi]?)$") to Role.EXERCISE,
    Regex("^(sets?|#\\s*sets?|no\\.?\\s*of\\s*sets|number\\s*of\\s*sets|working\\s*sets|sätze|satz|satze|saetze|series|séries|serie)$") to Role.SETS,
    Regex("^(reps?|#\\s*reps?|repetitions?|reps?\\s*/\\s*time|reps?\\s*/\\s*duration|reps?\\s*per\\s*set|target\\s*reps|wdh\\.?|wiederholungen|wiederholung|repeticiones|reps|répétitions|ripetizioni|time|duration|distance)$") to Role.REPS,
    Regex("^(day|days|session|workout|training\\s*day|tag|día|dia|jour|giorno|date|dates|datum|fecha)$") to Role.DAY,
    Regex("^(week|wk|woche|semana|semaine|settimana)$") to Role.WEEK,
    Regex("^(set\\s*#?|set\\s*no\\.?|set\\s*number|#)$") to Role.SETNUM,
    Regex("^(weight|load|kg|kgs|lbs?|intensity|%|%\\s*1rm|% of 1rm|%1rm|percent(age)?|gewicht|peso|poids|target\\s*weight|weight\\s*\\(.*\\))$") to Role.LOAD,
    Regex("^(rpe|rir|effort|@rpe|target\\s*rpe)$") to Role.RPE,
    Regex("^(rest|rest\\s*\\(.*\\)|rest\\s*time|recovery|pause|pausa|repos)$") to Role.REST,
    Regex("^(notes?|cues?|comments?|coaching\\s*notes?|tips?|instructions?|description|details|bemerkung|notas?)$") to Role.NOTES,
    Regex("^(tempo)$") to Role.TEMPO,
    Regex("^(superset|super\\s*set|ss|group|pair|pairing)$") to Role.SUPERSET
  )
  /** "Set 1 | Set 2 | Set 3": one column of reps per set. */
  private val SET_COL = Regex("^set\\s*#?\\s*(\\d{1,2})$")
  private val WEEK_COL = Regex("^(?:week|wk|w|woche|semana|semaine)\\s*(\\d{1,2})$")

  private fun roleOf(cell: String): Pair<Role, Int?>? {
    val c = Rx.clean(cell).lowercase(Locale.ROOT).trim().trim(':', '.', '*')
    if (c.isEmpty() || c.length > 28) return null
    WEEK_COL.matchEntire(c)?.let { return Role.WEEKCOL to it.groupValues[1].toInt() }
    SET_COL.matchEntire(c)?.let { return Role.SETCOL to it.groupValues[1].toInt() }
    return ROLE_WORDS.firstOrNull { it.first.matches(c) }?.let { it.second to null }
  }

  /** A header row names at least two columns, at least one of which carries the program. */
  private fun headerOf(cells: List<String>): Pair<ColumnMap, String?>? {
    val roles = HashMap<Int, Role>()
    val weekCols = HashMap<Int, Int>()
    var label: String? = null
    var labelCol: Int? = null
    cells.forEachIndexed { i, c ->
      if (c.isBlank()) return@forEachIndexed
      val r = roleOf(c)
      if (r == null) {
        if (label == null) { label = c.trim(); labelCol = i }
      } else {
        roles[i] = r.first
        if (r.first == Role.WEEKCOL) weekCols[i] = r.second!!
      }
    }
    val named = roles.size
    if (named < 2 && !(named >= 1 && label != null && roles.values.any { it == Role.SETS || it == Role.REPS })) return null
    val carries = roles.values.any { it in setOf(Role.EXERCISE, Role.SETS, Role.REPS, Role.SETSREPS, Role.WEEKCOL, Role.SETCOL) }
    if (!carries) return null
    // "PUSH DAY | SETS | REPS": the unnamed text column is the exercise column, and its heading is a day.
    var dayLabel: String? = null
    if (Role.EXERCISE !in roles.values) {
      val lc = labelCol ?: (0 until (roles.keys.minOrNull() ?: 0)).firstOrNull() ?: return null
      roles[lc] = Role.EXERCISE
      dayLabel = label
    }
    if (named == 1 && dayLabel == null) return null
    // "Set" (singular) beside a Reps column numbers the sets: one row per set.
    roles.entries.firstOrNull { (c, r) ->
      r == Role.SETS && Rx.clean(cells[c]).lowercase(Locale.ROOT).trim().matches(Regex("set\\s*#?|set\\s*no\\.?"))
    }?.let { (c, _) -> if (Role.REPS in roles.values) roles[c] = Role.SETNUM }
    // "Exercise | Sets | Reps | Sets | Reps | Sets | Reps": one Sets/Reps pair per week.
    val setsCols = roles.filterValues { it == Role.SETS }.keys.sorted()
    val repsCols = roles.filterValues { it == Role.REPS }.keys.sorted()
    val pairs = if (setsCols.size >= 2 && setsCols.size == repsCols.size) setsCols.zip(repsCols) else emptyList()
    return ColumnMap(roles, weekCols, pairs) to dayLabel?.takeIf { Lines.isDayish(it) || it.split(' ').size <= 4 }
  }

  /** When a table has no header row: one text column + numeric columns, by content. */
  private fun inferColumns(rows: List<List<String>>): ColumnMap? {
    val data = rows.filter { r -> r.count { it.isNotBlank() } >= 2 }
    if (data.size < 2) return null
    val width = data.maxOf { it.size }
    val kinds = (0 until width).map { c ->
      val vals = data.map { it.getOrNull(c).orEmpty() }.filter { it.isNotBlank() }
      when {
        vals.isEmpty() -> "empty"
        vals.all { Rx.setsField(it).first != null } -> "int"
        vals.all { Rx.repsField(it).let { (s, l) -> s != null || l.isNotEmpty() } } -> "reps"
        vals.all { Rx.parseLine(it).let { p -> p.hasRx && p.name.isBlank() } } -> "rx"
        vals.count { v -> v.any { ch -> ch.isLetter() } } >= vals.size * 0.8 -> "text"
        else -> "mixed"
      }
    }
    val text = kinds.indexOf("text").takeIf { it >= 0 } ?: return null
    val roles = HashMap<Int, Role>()
    roles[text] = Role.EXERCISE
    val numeric = kinds.indices.filter { it > text && kinds[it] in setOf("int", "reps", "rx") }
    when {
      numeric.isEmpty() -> return null
      kinds[numeric[0]] == "rx" -> roles[numeric[0]] = Role.SETSREPS
      numeric.size >= 2 && kinds[numeric[0]] == "int" -> {
        roles[numeric[0]] = Role.SETS; roles[numeric[1]] = Role.REPS
        // A third number column in a header-less table is the weight ("Back Squat | 5 | 5 | 100").
        numeric.getOrNull(2)?.let { roles[it] = Role.LOAD }
      }
      else -> return null
    }
    // A day column to the left of the names, when its values repeat.
    if (text > 0) {
      val vals = data.map { it.getOrNull(text - 1).orEmpty() }
      if (vals.distinct().size < vals.size) roles[text - 1] = Role.DAY
    }
    return ColumnMap(roles, emptyMap())
  }

  // ── reading a block ──────────────────────────────────────────────────────────

  private fun readBlock(block: Block, out: Builder, multiBlock: Boolean) {
    val events = ArrayList<Event>()
    val rows = block.rows
    var map: ColumnMap? = null
    var lastDayValue: String? = null
    var lastWeekValue: String? = null
    var lastSetKey: String? = null

    // A run of multi-cell rows with no header gets its columns inferred from content.
    fun tryInfer(from: Int): ColumnMap? {
      val run = ArrayList<List<String>>()
      var k = from
      while (k < rows.size && rows[k].count { it.isNotBlank() } >= 2) { run += rows[k]; k++ }
      if (run.size < 2) return null
      if (run.any { r -> headerOf(r) != null }) return null
      return inferColumns(run)
    }

    // Text-mode rows are classified with lookahead, so pre-read them once.
    val lineInfo = rows.map { r -> Lines.info(r.filter { it.isNotBlank() }.joinToString(" ")) }

    var i = 0
    while (i < rows.size) {
      val row = rows[i]
      val nonBlank = row.withIndex().filter { it.value.isNotBlank() }
      if (nonBlank.isEmpty()) { events += Event.Blank; i++; if (block.fromGrid) { lastSetKey = null }; continue }

      // Metadata above the program ("Program | Smith Off-Season", "Coach: Jo", "Start Date | 2026-03-02").
      if (map == null && events.none { it is Event.Exercise } && headerOf(row) == null) {
        val meta = metaOf(row)
        // Only "name:"/"title:" can turn out to name a day (YAML); every other key is metadata.
        if (meta != null && (meta.first !in setOf("name", "title") || !Lines.startsExercises(lineInfo, i))) {
          if (meta.first in TITLE_KEYS && meta.second.isNotBlank()) out.title = out.title ?: cleanLabel(meta.second)
          i++; continue
        }
      }

      // A header row (re)defines the columns.
      val header = headerOf(row)
      if (header != null) {
        map = header.first; lastDayValue = null; lastWeekValue = null; lastSetKey = null
        header.second?.let { events += Event.Day(cleanLabel(it), rest = false, explicit = true) }
        i++; continue
      }

      if (map == null && nonBlank.size >= 2) {
        tryInfer(i)?.let { map = it }
      }

      val m = map
      if (m != null && m.hasExercise) {
        val exIdx = m.col(Role.EXERCISE)!!
        val exCell = row.getOrNull(exIdx).orEmpty()
        val info = lineInfo[i]
        // Does any numeric column actually hold a number on this row?
        val numbers = m.roles.any { (c, r) ->
          isNumericRole(r) && row.getOrNull(c).orEmpty().let { v -> v.isNotBlank() && (Rx.setsField(v).first != null || Rx.repsField(v).first != null || Rx.parseLine(v).hasRx || r == Role.LOAD || r == Role.REST) }
        }
        // A section line inside a table ("DAY 2 - PULL", "DAY 2 | LOWER", "Week 3", a title), or a
        // lone line of text in some other column, is read as text, not as an exercise row.
        val sectionLine = !numbers && (info.week != null || info.day != null || info.restDay || info.section || info.structuralKey ||
          (nonBlank.size == 1 && !info.rx.hasRx && Lines.isDayish(info.text)))
        val strayCell = nonBlank.size == 1 && nonBlank[0].index != exIdx && !isNumericRole(m.roles[nonBlank[0].index]) &&
          m.roles[nonBlank[0].index] !in setOf(Role.DAY, Role.WEEK, Role.NOTES)
        if (sectionLine || strayCell) {
          emitText(info, lineInfo, i, events)
          i++; continue
        }
        // Week / Day columns.
        m.col(Role.WEEK)?.let { c ->
          val v = row.getOrNull(c).orEmpty()
          if (v.isNotBlank() && v != lastWeekValue) {
            lastWeekValue = v
            Regex("(\\d{1,2})").find(v)?.let { events += Event.Week(it.value.toInt()) }
            lastDayValue = null
          }
        }
        m.col(Role.DAY)?.let { c ->
          val v = row.getOrNull(c).orEmpty()
          if (v.isNotBlank() && v != lastDayValue) {
            lastDayValue = v
            val label = if (v.trim().matches(Regex("\\d{1,2}"))) "Day ${v.trim()}" else cleanLabel(v)
            events += Event.Day(label, rest = Lines.isRestWord(v) && exCell.isBlank(), explicit = true)
          }
        }
        if (exCell.isBlank()) { i++; continue }

        val parsedName = Rx.parseLine(exCell)
        var line = parsedName
        fun cell(r: Role) = m.col(r)?.let { row.getOrNull(it) }.orEmpty()
        // Prescription cells override what the name cell implied.
        val sr = cell(Role.SETSREPS)
        if (sr.isNotBlank()) {
          val p = Rx.parseLine(sr)
          line = line.copy(sets = p.sets ?: line.sets, setsMax = p.setsMax ?: line.setsMax,
            reps = p.reps ?: line.reps, ladder = p.ladder.ifEmpty { line.ladder }, load = p.load ?: line.load,
            rest = p.rest ?: line.rest, override = p.override ?: line.override, tempo = line.tempo.ifBlank { p.tempo })
        }
        val setsCell = cell(Role.SETS)
        if (setsCell.isNotBlank()) {
          val (s, sMax) = Rx.setsField(setsCell)
          if (s != null) line = line.copy(sets = s, setsMax = sMax)
          else Rx.parseLine(setsCell).takeIf { it.hasRx }?.let { p -> line = line.copy(sets = p.sets ?: line.sets, reps = p.reps ?: line.reps, ladder = p.ladder.ifEmpty { line.ladder }) }
        }
        val repsCell = cell(Role.REPS)
        if (repsCell.isNotBlank()) {
          val p = Rx.parseLine(repsCell)
          if (p.hasSetsAndReps && p.name.isBlank()) {
            line = line.copy(sets = line.sets ?: p.sets, reps = p.reps, ladder = p.ladder)
          } else {
            val (single, ladder) = Rx.repsField(repsCell)
            if (single != null || ladder.isNotEmpty()) line = line.copy(reps = single, ladder = ladder)
            if (Regex("(?i)each|per side|/side|/leg").containsMatchIn(repsCell))
              line = line.copy(modifiers = (line.modifiers + com.wildodds.gymtracker.data.importer.Modifier.EACH_SIDE).distinct())
          }
        }
        // One reps cell per set: the filled cells are the sets, their values the reps of each.
        val perSet = m.roles.filterValues { it == Role.SETCOL }.keys.sorted()
          .mapNotNull { c -> row.getOrNull(c)?.takeIf { it.isNotBlank() } }
          .mapNotNull { v -> Rx.repsField(v).first }
        if (perSet.isNotEmpty()) {
          line = if (perSet.distinct().size == 1) line.copy(sets = perSet.size, reps = perSet.first(), ladder = emptyList())
          else line.copy(sets = perSet.size, reps = null, ladder = perSet)
        }
        cell(Role.LOAD).takeIf { it.isNotBlank() }?.let { lc -> loadCell(lc, m)?.let { line = line.copy(load = it) } }
        cell(Role.RPE).takeIf { it.isNotBlank() }?.let { rc ->
          Regex("(\\d{1,2}(?:\\.\\d)?)(?:\\s*-\\s*(\\d{1,2}(?:\\.\\d)?))?").find(rc)?.let { mm ->
            if (line.load == null) line = line.copy(load = LoadSpec(LoadKind.RPE, mm.groupValues[1].toDouble(), mm.groupValues[2].toDoubleOrNull(), text = rc))
          }
        }
        cell(Role.REST).takeIf { it.isNotBlank() }?.let { rc -> Rx.parseLine("rest $rc").rest?.let { line = line.copy(rest = it) } }
        cell(Role.TEMPO).takeIf { it.isNotBlank() }?.let { tc -> line = line.copy(tempo = tc.trim().uppercase()) }
        // "A1", "A2" pair exercises; a bare "A" groups them in row order.
        cell(Role.SUPERSET).trim().uppercase().let { sc -> Regex("^([A-Z])\\s*(\\d{1,2})?$").find(sc) }?.let { mm ->
          line = line.copy(supersetGroup = mm.groupValues[1], supersetOrder = mm.groupValues[2].toIntOrNull())
        }
        val note = listOf(line.note, cell(Role.NOTES)).filter { it.isNotBlank() }.joinToString("; ")
        line = line.copy(note = note)
        val raw = row.filter { it.isNotBlank() }.joinToString(" | ")

        // One row per SET: a Set column says so outright; with no Sets column at all, the same
        // exercise on consecutive rows says so too. Either way the rows fold into one exercise.
        val setColumn = m.col(Role.SETNUM) != null
        val noCount = m.col(Role.SETS) == null && m.col(Role.SETSREPS) == null && m.weekCols.isEmpty() && m.weekPairs.isEmpty() && perSet.isEmpty()
        if (setColumn || noCount) {
          val key = "${lastWeekValue}|${lastDayValue}|${Lines.key(line.name)}"
          val prev = events.lastOrNull() as? Event.Exercise
          if (key == lastSetKey && prev != null && (setColumn || line.sets == null)) {
            events.removeAt(events.size - 1)
            events += prev.copy(perSetReps = prev.perSetReps + listOfNotNull(line.reps), raw = prev.raw + "\n" + raw, setsFromRows = true)
          } else {
            events += Event.Exercise(if (setColumn) line.copy(sets = null) else line, raw,
              perSetReps = listOfNotNull(line.reps), fromColumns = true, setsFromRows = setColumn)
          }
          lastSetKey = key
          i++; continue
        }

        // Repeated Sets/Reps column pairs are weeks: "Exercise | Sets | Reps | Sets | Reps".
        if (m.weekPairs.isNotEmpty()) {
          m.weekPairs.forEachIndexed { k, (sc, rc) ->
            val (s, sMax) = Rx.setsField(row.getOrNull(sc).orEmpty())
            val (single, ladder) = Rx.repsField(row.getOrNull(rc).orEmpty())
            if (s == null && single == null && ladder.isEmpty()) return@forEachIndexed
            events += Event.Exercise(line.copy(sets = s, setsMax = sMax, reps = single, ladder = ladder),
              "$raw (week ${k + 1})", week = k + 1, fromColumns = true)
          }
          i++; continue
        }

        if (m.weekCols.isNotEmpty()) {
          for ((col, wk) in m.weekCols.entries.sortedBy { it.value }) {
            val c = row.getOrNull(col).orEmpty()
            if (c.isBlank()) continue
            val p = Rx.parseLine(c)
            val l = line.copy(sets = p.sets ?: line.sets, setsMax = p.setsMax, reps = p.reps ?: line.reps,
              ladder = p.ladder.ifEmpty { line.ladder }, load = p.load ?: line.load)
            events += Event.Exercise(l, "$raw (week $wk: $c)", week = wk, fromColumns = true)
          }
          i++; continue
        }

        events += Event.Exercise(line, raw, fromColumns = true)
        i++; continue
      }

      // No column map: a line of text.
      emitText(lineInfo[i], lineInfo, i, events)
      i++
    }

    // Sheets / blocks with no day structure of their own are named by their title.
    val hasDay = events.any { it is Event.Day }
    val hasWeek = events.any { it is Event.Week }
    val hasEx = events.any { it is Event.Exercise }
    val titleWeek = block.title?.let { t -> Regex("(?i)^\\s*(?:week|wk|w)\\s*(\\d{1,2})\\b").find(t)?.groupValues?.get(1)?.toInt() }
    val prefix = ArrayList<Event>()
    if (titleWeek != null && !hasWeek) prefix += Event.Week(titleWeek)
    else if (multiBlock && hasEx && !hasDay && block.title != null && !Lines.isGenericSheetName(block.title)) {
      prefix += Event.Day(cleanLabel(block.title), rest = false, explicit = true)
    }
    if (multiBlock && titleWeek == null && !hasDay && hasEx && (block.title == null || Lines.isGenericSheetName(block.title))) {
      // Side-by-side sub-blocks with no label of their own are still separate days.
      prefix += Event.Day(null, rest = false, explicit = false)
    }
    out.consume(prefix + events, usedColumns = map != null)
  }

  private val META_KEY = Regex("(?i)^(client|athlete|lifter|name|coach|trainer|author|created\\s*by|program|programme|plan|title|start\\s*date|end\\s*date|date|phase|goal|level|created|version|duration|length|frequency|days\\s*per\\s*week|bodyweight|body\\s*weight|bw|height|age|sex|gender|units?|squat\\s*max|bench\\s*max|deadlift\\s*max|1rm|maxes|training\\s*max|tm|email|e-mail|phone)$")
  private val TITLE_KEYS = setOf("program", "programme", "plan", "title", "name")

  /** (key, value) when a row is a "Key | value" or "Key: value" metadata line — never a list item. */
  private fun metaOf(row: List<String>): Pair<String, String>? {
    val cells = row.filter { it.isNotBlank() }.map { it.trim() }
    if (cells.isEmpty() || cells.size > 3) return null
    if (cells[0].startsWith("-") || cells[0].startsWith("•") || cells[0].startsWith("*")) return null
    val (key, value) = if (cells.size >= 2) cells[0].trimEnd(':', ' ') to cells[1]
      else cells[0].split(':', limit = 2).let { p -> if (p.size < 2) return null else p[0] to p[1].trim() }
    val k = Rx.clean(key).lowercase(Locale.ROOT).trim()
    if (!META_KEY.matches(k)) return null
    if (Rx.parseLine(value).hasSetsAndReps) return null
    return k.replace(Regex("\\s+"), " ") to value
  }

  private fun isNumericRole(r: Role?) = r in setOf(Role.SETS, Role.REPS, Role.SETSREPS, Role.LOAD, Role.RPE, Role.REST, Role.SETNUM, Role.WEEKCOL)

  private fun loadCell(raw: String, m: ColumnMap): LoadSpec? {
    // A placeholder ("—", "-", "n/a", "x") says there is no load, not that the load is "—".
    if (Regex("(?i)^[\\s\\-–—_./x*?]*$|^(n/?a|none|nil|bw\\s*only)$").matches(raw.trim())) return null
    val p = Rx.parseLine("x $raw")
    p.load?.let { return it }
    Regex("^(\\d{1,4}(?:\\.\\d{1,2})?)(?:\\s*-\\s*(\\d{1,4}(?:\\.\\d{1,2})?))?$").matchEntire(raw.trim())?.let {
      return LoadSpec(LoadKind.ABSOLUTE, it.groupValues[1].toDouble(), it.groupValues[2].toDoubleOrNull(), null, raw.trim())
    }
    return LoadSpec(LoadKind.TEXT, text = raw.trim())
  }

  /** Turn one classified text line into events. */
  private fun emitText(info: Lines.Info, all: List<Lines.Info>, i: Int, events: MutableList<Event>) {
    if (info.blank) { events += Event.Blank; return }
    // Structural keys of a YAML/JSON-ish paste ("days:", "exercises:", "weeks: 4") carry no program.
    if (info.structuralKey) return
    info.weeks?.let { events += Event.Week(it) }
    if (info.restDay) { events += Event.Day(info.dayLabel ?: info.text, rest = true, explicit = true); return }
    info.setDetail?.let { (reps, load) -> events += Event.SetDetail(reps, load); return }
    info.labelled?.let { (field, value) ->
      events += when (field) {
        // "name: Upper A" over a list of exercises names the DAY, not an exercise.
        "exercise" -> if (info.labelKey in setOf("name", "title") && Lines.startsExercises(all, i)) Event.Day(cleanLabel(value), rest = false, explicit = true)
          else Event.PendingName(value)
        "sets" -> Event.Field(sets = Rx.setsField(value).first)
        "reps" -> Event.Field(reps = Rx.repsField(value))
        "rest" -> Event.Field(rest = Rx.parseLine("rest $value").rest)
        "notes" -> Event.Field(note = value)
        "day" -> Event.Day(cleanLabel(value), rest = false, explicit = true)
        else -> Event.Blank
      }
      return
    }
    // Clauses: "monday do squats 4x6, then leg press 3x12…" is a day and three exercises.
    if (info.clauses.size >= 2) {
      info.day?.let { events += Event.Day(it, rest = false, explicit = true) }
      info.clauses.forEach { events += Event.Exercise(it, info.text) }
      return
    }
    if (info.rx.hasRx && info.rx.name.isNotBlank()) {
      info.day?.let { events += Event.Day(it, rest = false, explicit = true) }
      events += Event.Exercise(info.rx, info.text)
      return
    }
    if (info.rx.hasRx && info.rx.name.isBlank()) {
      if (info.weeks != null && !info.rx.hasSetsAndReps) return
      events += Event.Prescription(info.rx)
      return
    }
    if (info.rx.rest != null && info.rx.name.isBlank()) { events += Event.Field(rest = info.rx.rest); return }
    if (info.weeks != null) return

    val nextIdx = Lines.nextMeaningfulIndex(all, i)
    val next = nextIdx?.let { all[it] }
    val nextIsNamelessRx = next != null && next.rx.hasRx && next.rx.name.isBlank() && next.weeks == null
    val nextIsFields = next?.labelled?.first in setOf("sets", "reps") || next?.setDetail != null
    if ((nextIsNamelessRx || nextIsFields) && !info.prose && info.day == null && info.text.length <= 60 && !info.section) {
      events += Event.PendingName(info.rx.name.ifBlank { info.text })
      return
    }
    if (info.day != null) { events += Event.Day(info.day, rest = false, explicit = true); return }
    // "Warm-up", "Main", "Accessories", "Cool down" divide a day; they are not days.
    if (info.section) return
    val prev = all.getOrNull(i - 1)
    val afterExercise = prev != null && !prev.blank && prev.rx.hasRx && prev.rx.name.isNotBlank()
    if (info.prose) {
      // A sentence right under an exercise is a note on it ("warm up to the working weight").
      if (afterExercise) events += Event.Field(note = info.text)
      return
    }
    if (Lines.startsExercises(all, i)) {
      if (Lines.isKnownExercise(info.text) && !Lines.isDayish(info.text)) {
        events += Event.Exercise(info.rx.copy(name = info.rx.name.ifBlank { info.text }), info.text)
      } else {
        events += Event.Day(cleanLabel(info.text), rest = false, explicit = false)
      }
      return
    }
    // A movement on its own line inside a day: an exercise whose prescription wasn't given. Either
    // the name is a known movement, or the line directly continues a list (no blank line between it
    // and an exercise or day header above). It is imported — and flagged — rather than dropped.
    val continuesList = prev != null && !prev.blank && (prev.rx.hasRx || prev.day != null || Lines.isDayish(prev.text))
    if (!Lines.isDayish(info.text) && info.text.split(' ').size <= 6 &&
      (Lines.isKnownExercise(info.text) || continuesList)) {
      events += Event.Exercise(info.rx.copy(name = info.rx.name.ifBlank { info.text }), info.text)
    }
  }

  fun cleanLabel(raw: String): String =
    Rx.clean(raw).replace(Regex("^#+\\s*"), "").replace("**", "").replace("__", "")
      .trim().trim(':', '-', '|', '*', ' ', '=').replace(Regex("\\s+"), " ")

  // ── events → program ─────────────────────────────────────────────────────────

  sealed class Event {
    /** One or more weeks the following days belong to: "Week 3", "Weeks 1-4", "Weeks 1, 3, 5". */
    data class Week(val weeks: List<Int>) : Event() { constructor(n: Int) : this(listOf(n)) }
    data class Day(val label: String?, val rest: Boolean, val explicit: Boolean) : Event()
    data class Exercise(
      val line: Rx.Line, val raw: String, val week: Int? = null,
      /** One rep target per row, for programs written one row per SET. */
      val perSetReps: List<RepSpec> = emptyList(),
      val fromColumns: Boolean = false,
      /** True when the rows ARE the sets (a Set column, or the same exercise on consecutive rows). */
      val setsFromRows: Boolean = false
    ) : Event()
    data class PendingName(val name: String) : Event()
    data class Prescription(val rx: Rx.Line) : Event()
    /** "set 2: 5 @ 80%" under an exercise — one set of it. */
    data class SetDetail(val reps: RepSpec?, val load: LoadSpec?) : Event()
    data class Field(
      val sets: Int? = null, val reps: Pair<RepSpec?, List<RepSpec>>? = null,
      val rest: RestSpec? = null, val note: String? = null
    ) : Event()
    object Blank : Event()
  }

  private class DayB(val label: String?, val rest: Boolean, val explicit: Boolean) {
    val slots = ArrayList<SlotDraft>()
  }

  private class Builder {
    val weeks = java.util.TreeMap<Int, MutableList<DayB>>()
    /** The week(s) being written to. Several when the source said "Weeks 1-4". */
    var current: List<Int> = listOf(1)
    /** The current day, one per current week, in step. */
    var day: List<DayB>? = null
    var exerciseCount = 0
    var prescribed = 0
    var complete = 0
    var usedColumns = false
    var sawStructure = false
    private var explicitWeeks = false
    var units = HashSet<WeightUnit>()
    /** A title the document states for itself ("Program: Smith Off-Season"). */
    var title: String? = null
    private var pending: SlotWork? = null
    private var lastSlot: List<Pair<DayB, Int>>? = null

    private class SlotWork(var name: String, var raw: String, var line: Rx.Line? = null) {
      val perSet = ArrayList<RepSpec>()
      var load: LoadSpec? = null
    }

    private fun days(w: Int) = weeks.getOrPut(w) { ArrayList() }

    private fun ensureDay(): List<DayB> = day ?: current.map { w -> DayB(null, false, false).also { days(w) += it } }.also { day = it }

    private fun newDay(label: String?, rest: Boolean, explicit: Boolean) {
      // "Day 1 … Day 2 … Day 1 …" with no week headers: the repeated NUMBERED day starts a new week.
      // Only numbered days and weekdays — "Upper / Lower / Upper / Lower" is one four-day week.
      if (!explicitWeeks && label != null && current.size == 1 && Lines.isNumberedDay(label)) {
        val k = Lines.dayKey(label)
        if (days(current[0]).any { it.label != null && Lines.dayKey(it.label) == k }) {
          current = listOf((weeks.keys.maxOrNull() ?: current[0]) + 1)
        }
      }
      day = current.map { w -> DayB(label, rest, explicit).also { days(w) += it } }
    }

    fun consume(events: List<Event>, usedColumns: Boolean) {
      if (usedColumns) this.usedColumns = true
      for (e in events) when (e) {
        is Event.Week -> { flush(); current = e.weeks.distinct(); day = null; sawStructure = true; explicitWeeks = true }
        is Event.Day -> {
          flush()
          // A label-less day marker right after a fresh day is a no-op (side-by-side blocks).
          if (e.label == null && day != null && day!!.all { it.slots.isEmpty() }) continue
          newDay(e.label, e.rest, e.explicit)
          if (e.explicit) sawStructure = true
        }
        is Event.PendingName -> { flush(); pending = SlotWork(e.name, e.name) }
        is Event.Prescription -> {
          val p = pending
          if (p != null) {
            p.line = (p.line ?: nameLine(p.name)).let { merge(it, e.rx) }
            p.raw += "\n" + e.rx.raw
          } else lastSlot?.let { targets ->
            // A prescription line under an exercise that had none ("Squat" / "3 sets x 5").
            targets.forEach { (d, idx) ->
              val s = d.slots[idx]
              if (s.sets.firstOrNull()?.count == null) {
                val line = merge(Rx.Line(s.rawText, s.exercise.original), e.rx)
                d.slots[idx] = slot(line, s.rawText + "\n" + e.rx.raw, s.orderIndex)
              }
            }
          }
        }
        is Event.SetDetail -> {
          val p = pending
          if (p != null) {
            e.reps?.let { p.perSet += it }
            if (p.load == null) p.load = e.load
            p.raw += "\n" + (e.reps?.text ?: "")
          }
        }
        is Event.Field -> {
          val p = pending
          if (p != null) {
            var l = p.line ?: nameLine(p.name)
            e.sets?.let { l = l.copy(sets = it) }
            e.reps?.let { (single, ladder) -> l = l.copy(reps = single, ladder = ladder) }
            e.rest?.let { l = l.copy(rest = it) }
            e.note?.let { l = l.copy(note = listOf(l.note, it).filter { s -> s.isNotBlank() }.joinToString("; ")) }
            p.line = l
          } else lastSlot?.forEach { (d, idx) ->
            val s = d.slots[idx]
            if (e.rest != null && s.rest.lowSec == null) d.slots[idx] = s.copy(rest = e.rest)
            if (e.note != null) d.slots[idx] = d.slots[idx].copy(notes = listOf(s.notes, e.note).filter { it.isNotBlank() }.joinToString("; "))
          }
        }
        is Event.Exercise -> {
          flush()
          if (e.line.warmupOnly) continue
          add(e.line, e.raw, e.week, e.perSetReps, e.setsFromRows)
        }
        Event.Blank -> {}
      }
      flush()
    }

    private fun nameLine(name: String): Rx.Line = Rx.parseLine(name).let { it.copy(name = it.name.ifBlank { name }) }

    private fun merge(base: Rx.Line, rx: Rx.Line): Rx.Line = base.copy(
      sets = rx.sets ?: base.sets, setsMax = rx.setsMax ?: base.setsMax,
      reps = rx.reps ?: base.reps, ladder = rx.ladder.ifEmpty { base.ladder },
      load = rx.load ?: base.load, rest = rx.rest ?: base.rest,
      modifiers = (base.modifiers + rx.modifiers).distinct(),
      note = listOf(base.note, rx.note).filter { it.isNotBlank() }.joinToString("; "),
      override = rx.override ?: base.override,
      tempo = base.tempo.ifBlank { rx.tempo }
    )

    private fun flush() {
      val p = pending ?: return
      pending = null
      var line = p.line ?: nameLine(p.name)
      if (p.load != null && line.load == null) line = line.copy(load = p.load)
      add(line, p.raw, null, p.perSet, setsFromRows = p.perSet.isNotEmpty())
    }

    private fun add(line: Rx.Line, raw: String, weekOverride: Int?, perSetReps: List<RepSpec>, setsFromRows: Boolean) {
      if (line.name.isBlank() || line.name.length > 80) return
      val targets: List<DayB> = if (weekOverride == null) ensureDay() else {
        val cur = ensureDay()[0]
        val ord = days(current[0]).indexOf(cur)
        val target = days(weekOverride)
        while (target.size <= ord) {
          val src = days(current[0]).getOrNull(target.size)
          target += DayB(src?.label, src?.rest ?: false, src?.explicit ?: false)
        }
        listOf(target[ord])
      }
      var l = line
      if (perSetReps.isNotEmpty()) {
        val allSame = perSetReps.all { it == perSetReps[0] }
        // The rows are the sets only when they say so; one row of a no-Sets table states no count.
        val count = if (setsFromRows) perSetReps.size else l.sets
        l = l.copy(sets = count, reps = if (allSame) perSetReps[0] else null, ladder = if (allSame) emptyList() else perSetReps)
      }
      (l.load?.unit)?.let { units += it }
      val placed = ArrayList<Pair<DayB, Int>>()
      for (d in targets) {
        d.slots += slot(l, raw, d.slots.size)
        placed += d to (d.slots.size - 1)
      }
      lastSlot = placed
      exerciseCount++
      if (l.hasRx) prescribed++
      val o = l.override
      if (l.hasSetsAndReps || (o != null && o.count != null && (o.reps != null || o.perSet.isNotEmpty()))) complete++
    }

    fun slot(line: Rx.Line, raw: String, order: Int): SlotDraft {
      val p = line.toPrescription()
      val stated = listOf(p.count != null, p.reps != null || p.perSet.isNotEmpty()).count { it }
      val breakdown = ConfidenceBreakdown(
        structure = when (stated) { 2 -> 1.0; 1 -> 0.7; else -> 0.45 },
        // Unread text is the signal: 95% read is a clean line, 60% read is probably misunderstood.
        notationCoverage = when {
          line.coverage >= 0.95 -> 1.0
          line.coverage >= 0.85 -> 0.95
          line.coverage >= 0.70 -> 0.85
          line.coverage >= 0.50 -> 0.65
          else -> 0.45
        },
        reasons = buildList {
          if (stated == 0) add("no sets/reps recognised")
          else {
            if (p.count == null) add("no set count stated")
            if (p.reps == null && p.perSet.isEmpty()) add("no reps stated")
          }
          if (line.coverage < 0.95) add("${((1 - line.coverage) * 100).toInt()}% of line unparsed")
        }
      )
      return SlotDraft(
        rawText = raw,
        exercise = ExerciseRef(original = line.name, resolvedName = titleCase(line.name)),
        sets = listOf(p),
        rest = line.rest ?: RestSpec(),
        supersetGroup = line.supersetGroup,
        supersetOrder = line.supersetOrder,
        modifiers = line.modifiers,
        notes = line.note,
        confidence = breakdown.score,
        breakdown = breakdown,
        orderIndex = order
      )
    }

    fun build(name: String): ProgramImportDraft? {
      flush()
      // Drop days that came to nothing (titles, sign-offs) — but keep stated rest days.
      for ((_, ds) in weeks) ds.removeAll { it.slots.isEmpty() && !it.rest }
      weeks.entries.removeAll { (_, ds) -> ds.none { it.slots.isNotEmpty() } }
      if (weeks.isEmpty()) return null
      val weekDrafts = weeks.entries.map { (w, ds) ->
        var trainingNo = 0
        WeekDraft(index = w, sessions = ds.mapIndexed { idx, d ->
          if (!d.rest) trainingNo++
          SessionDraft(
            label = d.label?.takeIf { it.isNotBlank() } ?: if (d.rest) "Rest" else "Day $trainingNo",
            dayNumber = idx + 1,
            weekdayHint = Lines.weekdayOf(d.label.orEmpty()).orEmpty(),
            type = if (d.rest) SessionType.REST else SessionType.TRAINING,
            slots = d.slots
          )
        })
      }
      return ProgramImportDraft(
        name = name,
        weeks = weekDrafts,
        structure = if (weekDrafts.size > 1) ProgramStructure.WEEK_BY_WEEK else ProgramStructure.SINGLE_WEEK_REPEATED,
        repeatWeeks = 1,
        canonicalUnit = units.singleOrNull(),
        hasExplicitStructure = sawStructure || usedColumns,
        flags = if (units.size > 1) listOf(ImportFlag(FlagSeverity.REVIEW, "The program mixes kg and lb — check each load's unit.")) else emptyList()
      )
    }

    private fun titleCase(s: String): String =
      s.split(" ").filter { it.isNotBlank() }.joinToString(" ") { w ->
        if (w.length <= 3 && w.uppercase() == w && w.any { it.isLetter() }) w
        else w.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
      }
  }

  // ── line classification ──────────────────────────────────────────────────────

  object Lines {
    data class Info(
      val text: String,
      val blank: Boolean,
      val rx: Rx.Line,
      /** The week(s) this line names: "Week 3" → [3], "Weeks 1-4" → [1,2,3,4], "Weeks 1, 3, 5". */
      val weeks: List<Int>?,
      val day: String?,
      val restDay: Boolean,
      val dayLabel: String?,
      val labelled: Pair<String, String>?,
      val clauses: List<Rx.Line>,
      val prose: Boolean,
      val labelKey: String? = null,
      /** "set 2: 5 @ 80%" — one set of the exercise above. */
      val setDetail: Pair<RepSpec?, LoadSpec?>? = null,
      /** A divider inside a day: warm-up, main, accessories, cool-down. */
      val section: Boolean = false,
      /** "days:", "exercises:", "weeks: 4" — structure of a pasted YAML/JSON document. */
      val structuralKey: Boolean = false
    ) {
      val week: Int? get() = weeks?.firstOrNull()
    }

    private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val WEEKDAY_RX = Regex("^(mon|monday|tue|tues|tuesday|wed|weds|wednesday|thu|thur|thurs|thursday|fri|friday|sat|saturday|sun|sunday)\\b", RegexOption.IGNORE_CASE)
    private val DAY_RX = Regex("(?i)(\\bday\\s*(\\d{1,2}|[a-f])\\b|\\b(session|workout|training\\s+day|wod)\\s*#?\\s*(\\d{1,2}|[a-f])\\b|\\bday\\s*:)")
    private val WEEK_RX = Regex("(?i)(?:^|[^a-z])(?:weeks?|wks?|woche|wochen|semanas?|semaines?)\\s*#?\\s*(\\d{1,2})(?![\\d])")
    private val WEEK_RANGE = Regex("(?i)(?:^|[^a-z])(?:weeks?|wks?|woche|wochen|semanas?|semaines?)\\s*#?\\s*(\\d{1,2})\\s*(?:-|to|through|thru|bis|a)\\s*(\\d{1,2})(?![\\d])")
    private val WEEK_LIST = Regex("(?i)(?:^|[^a-z])(?:weeks?|wks?)\\s*#?\\s*(\\d{1,2}(?:\\s*(?:,|&|and|\\+)\\s*\\d{1,2})+)(?![\\d])")
    private val REST_RX = Regex("(?i)\\b(rest|off|recovery|rest\\s+day|day\\s+off|deload\\s+day)\\b")
    private val DAY_WORDS = Regex("(?i)^(upper|lower|push|pull|legs?|leg\\s+day|arms?|chest|back|shoulders?|core|abs|full\\s*body|total\\s*body|posterior|anterior|power|strength|hypertrophy|conditioning|cardio|accessor(y|ies)|glutes?|hamstrings?|quads?|bis|tris|biceps|triceps|delts|mobility|metcon|finisher|warm[- ]?up|heavy|light|volume|speed|dynamic|max\\s*effort|mixed)\\b")
    private val SECTION_RX = Regex("(?i)^(warm[\\s-]?ups?|warmup|main(\\s+(lifts?|work|sets?|block|movement))?|accessor(y|ies)(\\s+(work|block))?|assistance(\\s+work)?|supplemental(\\s+work)?|cool[\\s-]?downs?|finishers?|prehab|activation|mobility\\s+work|core\\s+work|primary|secondary|tertiary)\\s*[:.]?$")
    private val STRUCT_KEY = Regex("(?i)^-?\\s*(days|exercises|weeks|sessions|workouts|blocks|program|programme|items|movements|lifts|schedule|routine|plan|phases|metadata|meta|version)\\s*:\\s*\\S{0,20}$")
    private val SET_DETAIL = Regex("(?i)^set\\s*#?\\s*(\\d{1,2})\\s*[:\\-.)]\\s*(.+)$")
    private val LOAD_FIRST = Regex("(?i)^(\\d+(?:[.,]\\d+)?)\\s*(kg|kgs|lbs?|#)\\s*[x×*]\\s*(\\d{1,3})(?:\\s*reps?)?$")
    private val PROSE_RX = Regex("(?i)^(hi|hey|hello|dear|thanks|thank you|cheers|regards|best|let me know|here'?s|here is|great|good luck|enjoy|note:|ps\\b|p\\.s\\.)")
    private val LABEL_RX = Regex("(?i)^(exercise|movement|lift|name|title|sets?|reps?|repetitions|rest|notes?|cues?|session|day|workout)\\s*[:=]\\s*(.+)$")

    fun key(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    fun weekdayOf(s: String): String? {
      val m = WEEKDAY_RX.find(Rx.clean(s).trim()) ?: return null
      val w = m.value.lowercase(Locale.ROOT)
      return WEEKDAYS.firstOrNull { it.startsWith(w.take(3)) }?.replaceFirstChar { it.uppercaseChar() }
    }

    fun isRestWord(s: String) = REST_RX.containsMatchIn(s)

    fun isSection(s: String) = SECTION_RX.matches(cleanLabel(s))

    /** "Day 3", "Day 3 - Push", "Monday" — a day that names its place in the week. */
    fun isNumberedDay(s: String): Boolean =
      Regex("(?i)\\bday\\s*\\d{1,2}\\b").containsMatchIn(s) || weekdayOf(s) != null

    /** What makes two numbered days "the same day": "Day 1 - Push" and "DAY 1" are both day 1. */
    fun dayKey(s: String): String =
      Regex("(?i)\\bday\\s*(\\d{1,2})\\b").find(s)?.let { "day ${it.groupValues[1].toInt()}" }
        ?: weekdayOf(s)?.lowercase(Locale.ROOT) ?: key(s)

    fun isGenericSheetName(s: String) =
      Regex("(?i)^(sheet|tabelle|feuille|hoja|program+e?|plan|training|workout|routine|log|data|template|block|export|table)\\s*\\d*$").matches(s.trim())

    fun explicitDay(s: String): String? {
      val c = cleanLabel(s)
      if (c.isEmpty() || c.length > 60) return null
      if (DAY_RX.containsMatchIn(c) || WEEKDAY_RX.containsMatchIn(c)) return c
      return null
    }

    fun isDayish(s: String): Boolean {
      val c = cleanLabel(s)
      return explicitDay(c) != null || (DAY_WORDS.containsMatchIn(c) && c.split(' ').size <= 5)
    }

    fun isKnownExercise(s: String): Boolean {
      val c = Rx.clean(s).trim()
      if (c.isEmpty() || c.length > 50) return false
      return ExerciseIndex.find(c) != null || ExerciseIndex.find(c.removeSuffix("s")) != null
    }

    private fun weeksIn(clean: String): List<Int>? {
      WEEK_RANGE.find(clean)?.let { m ->
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
        if (b > a && b - a <= 52) return (a..b).toList()
      }
      WEEK_LIST.find(clean)?.let { m ->
        val ns = Regex("\\d{1,2}").findAll(m.groupValues[1]).map { it.value.toInt() }.toList()
        if (ns.size >= 2) return ns.distinct()
      }
      return WEEK_RX.find(clean)?.groupValues?.get(1)?.toInt()?.let { listOf(it) }
    }

    fun info(rawText: String): Info {
      val t = rawText.trim()
      if (t.isBlank()) return Info("", true, Rx.Line("", ""), null, null, false, null, null, emptyList(), false)
      val text = t.replace(Regex("^#+\\s*"), "").replace("**", "").replace("__", "").trim()
      val clean = Rx.clean(text)
      val bare = clean.replace(Regex("^-\\s+"), "")

      if (STRUCT_KEY.matches(clean)) return Info(text, false, Rx.Line(text, ""), null, null, false, null, null, emptyList(), false, structuralKey = true)
      SET_DETAIL.matchEntire(bare)?.let { m ->
        // Logging apps write the load first: "Set 1: 60 kg × 10" is 10 reps at 60 kg.
        val detail = m.groupValues[2].trim().let { d ->
          LOAD_FIRST.matchEntire(d)?.let { lf -> "${lf.groupValues[3]} @ ${lf.groupValues[1]}${lf.groupValues[2]}" } ?: d
        }
        val p = Rx.parseLine("1x$detail")
        if (p.reps != null || p.ladder.isNotEmpty()) {
          return Info(text, false, Rx.Line(text, ""), null, null, false, null, null, emptyList(), false,
            setDetail = (p.reps ?: p.ladder.firstOrNull()) to p.load)
        }
      }

      val rx = Rx.parseLine(text)
      val words = clean.split(' ').filter { it.isNotBlank() }

      val weeks = weeksIn(clean)
        ?.takeIf { !rx.hasRx || rx.name.isBlank() || words.size <= 6 }
        ?.takeIf { !Regex("(?i)\\d\\s*-?\\s*weeks?\\b").containsMatchIn(clean) || Regex("(?i)weeks?\\s*#?\\s*\\d").containsMatchIn(clean) }

      // A day named at the start of a line that also carries exercises: "Monday: squats 4x6".
      var day: String? = null
      var exerciseText = text
      if (rx.hasRx) {
        val m = Regex("(?i)^\\s*((?:day\\s*\\d{1,2}|(?:mon|tues?|wed(?:nes)?|thu(?:rs)?|fri|sat(?:ur)?|sun)(?:day)?))\\b\\s*[:\\-–]?\\s*(?:do\\s+)?").find(clean)
        if (m != null && clean.length > m.range.last + 1) {
          day = cleanLabel(m.groupValues[1]).replaceFirstChar { it.uppercaseChar() }
          exerciseText = clean.substring(m.range.last + 1)
        }
      } else if (weeks == null || DAY_RX.containsMatchIn(clean)) {
        day = explicitDay(text)?.let { stripWeek(it) }?.takeIf { it.isNotBlank() }
      }
      val rx2 = if (exerciseText !== text) Rx.parseLine(exerciseText) else rx

      val restDay = !rx.hasRx && REST_RX.containsMatchIn(clean) && words.size <= 6 &&
        (day != null || weekdayOf(clean) != null || Regex("(?i)^(rest|off|rest\\s+day|day\\s+off|recovery(\\s+day)?)\\W*$").matches(clean) ||
          Regex("(?i)[:\\-–]\\s*(rest|off|recovery)\\W*$").containsMatchIn(clean))

      var labelKey: String? = null
      val labelled = if (!rx.hasSetsAndReps) LABEL_RX.matchEntire(bare)?.let { m ->
        val f = m.groupValues[1].lowercase(Locale.ROOT)
        labelKey = f
        val field = when {
          f in setOf("exercise", "movement", "lift", "name", "title") -> "exercise"
          f.startsWith("set") -> "sets"
          f.startsWith("rep") -> "reps"
          f == "rest" -> "rest"
          f in setOf("note", "notes", "cue", "cues") -> "notes"
          else -> "day"
        }
        field to m.groupValues[2].trim()
      } else null

      val clauses = splitClauses(exerciseText)

      val prose = PROSE_RX.containsMatchIn(clean) ||
        (!rx.hasRx && (words.size > 7 || (words.size > 3 && Regex("[.!?]$").containsMatchIn(clean)))) ||
        (!rx.hasRx && clean.endsWith(",")) ||
        // A lowercase sentence fragment of four or more words is a note, never a header.
        (!rx.hasRx && words.size >= 4 && bare.firstOrNull()?.isLowerCase() == true && day == null)

      return Info(text, false, rx2, weeks, day, restDay, if (restDay) (day ?: cleanLabel(text)) else null,
        labelled, clauses, prose, labelKey,
        section = !rx.hasRx && day == null && isSection(text))
    }

    private fun stripWeek(s: String): String =
      s.replace(Regex("(?i)\\b(?:weeks?|wks?)\\s*#?\\s*\\d{1,2}(?:\\s*(?:-|to|,|&)\\s*\\d{1,2})*\\b\\s*[-:,|]?\\s*"), "").trim().trim('-', ':', '|', ',', ' ')

    /** "squats 4 sets of 6, then leg press 3x12 and finish with calf raises 4x15" → 3 lines. */
    private fun splitClauses(text: String): List<Rx.Line> {
      val clean = Rx.clean(text)
      val lower = clean.lowercase(Locale.ROOT)
      // Only split where several complete prescriptions sit in one line.
      val ends = Regex("(?<![\\w.])\\d{1,2}(?:\\s*-\\s*\\d{1,2})?\\s*(?:x|\\*|by)\\s*(?:\\d{1,4}(?:\\s*-\\s*\\d{1,4})?(?:\\s*(?:s|sec|m|min))?|amrap|max|failure)(?![\\w.])|(?<![\\w.])\\d{1,2}\\s*sets?\\s*(?:of|x)\\s*\\d{1,3}(?:\\s*-\\s*\\d{1,3})?(?:\\s*reps?)?")
        .findAll(lower).toList()
      if (ends.size < 2) return emptyList()
      if (Regex("\\b(warm|wu|ramp)").containsMatchIn(lower)) return emptyList()
      val out = ArrayList<Rx.Line>()
      var start = 0
      for (m in ends) {
        val seg = clean.substring(start, m.range.last + 1)
        start = m.range.last + 1
        val p = Rx.parseLine(seg.trim().trim(',', ';'))
        if (p.name.isBlank() || !p.hasRx) return emptyList()
        out += p
      }
      return out
    }

    fun nextMeaningfulIndex(all: List<Info>, i: Int): Int? {
      for (k in i + 1 until all.size) if (!all[k].blank && !all[k].structuralKey) return k
      return null
    }

    fun nextMeaningful(all: List<Info>, i: Int): Info? = nextMeaningfulIndex(all, i)?.let { all[it] }

    /** True when the lines after [i] begin a run of exercises (the thing a header introduces). */
    fun startsExercises(all: List<Info>, i: Int): Boolean {
      val cur = all[i]
      if (cur.prose || cur.text.length > 60 || cur.text.split(' ').size > 7) return false
      val idx = nextMeaningfulIndex(all, i) ?: return false
      val n = all[idx]
      if (n.weeks != null || n.day != null || n.restDay) return false
      if (n.rx.hasRx && n.rx.name.isNotBlank()) return true
      if (n.clauses.size >= 2) return true
      if (n.labelled?.first == "exercise" && n.labelKey !in setOf("name", "title")) return true
      // A section divider ("Warm-up") under a header still means the header starts a day.
      if (n.section) return startsExercises(all, idx)
      val nn = nextMeaningful(all, idx)
      if (nn != null && ((nn.rx.hasRx && nn.rx.name.isBlank()) || nn.setDetail != null)) return true  // name line, prescription line
      if (Regex("(?i)\\bexercise\\b.*\\b(sets?|reps?)\\b").containsMatchIn(n.text)) return true  // header row
      return false
    }
  }
}

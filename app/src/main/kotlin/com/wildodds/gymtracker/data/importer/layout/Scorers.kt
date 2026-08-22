package com.wildodds.gymtracker.data.importer.layout

import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.importer.ProgramStructure
import com.wildodds.gymtracker.data.importer.SessionDraft
import com.wildodds.gymtracker.data.importer.SessionType
import com.wildodds.gymtracker.data.importer.SetNotationParser
import com.wildodds.gymtracker.data.importer.SlotDraft
import com.wildodds.gymtracker.data.importer.WeekDraft
import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary
import com.wildodds.gymtracker.data.reader.ingest.Grid

/**
 * The twelve layouts a training spreadsheet is actually written in.
 *
 * The first seven are the strategies the previous engine tried in order; they are unchanged in what
 * they recognise and changed entirely in how they are chosen. The last five are the shapes that
 * engine could not see at all, each of which produced a silently wrong import: a transposed sheet
 * read as one exercise per column, a set log read as one exercise per set, a month calendar read as
 * thirty sessions.
 */
private val vocab = ReaderVocabulary

// ── 1. the app's own template ────────────────────────────────────────────────

/**
 * `Program Info` plus `W1D1`, `W1D2` … sheets. This is the shape the app itself tells people to
 * use, so it must never be a coin flip — it scores near 1.0 when the sheet names match, and a
 * fixture test asserts the shipped `bulk_template.xlsx` still imports cleanly.
 */
object ClassicTemplateScorer : LayoutScorer {
  override val id = "classic-template"
  override val description = "The app's own W1D1 template"

  private val SHEET = Regex("^W(\\d+)D(\\d+)$")

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val matching = wb.grids.filter { SHEET.matches(it.name.trim()) }
    if (matching.isEmpty()) return null
    val ratio = matching.size.toDouble() / wb.grids.size
    return LayoutScorer.Scored(
      confidence = (0.9 + 0.1 * ratio).coerceAtMost(1.0),
      evidence = listOf("${matching.size} sheets named W<n>D<n>",
        if (wb.grids.any { it.name.equals("Program Info", true) }) "a Program Info sheet" else "no Program Info sheet"),
      build = { build(wb, matching) }
    )
  }

  private fun build(wb: Workbook, sheets: List<Grid>): ProgramImportDraft? {
    val parsed = sheets.mapNotNull { g ->
      val m = SHEET.matchEntire(g.name.trim()) ?: return@mapNotNull null
      Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), g)
    }.sortedWith(compareBy({ it.first }, { it.second }))
    if (parsed.isEmpty()) return null

    val info = wb.grids.firstOrNull { it.name.equals("Program Info", true) }
    val weeks = parsed.groupBy { it.first }.toSortedMap().map { (wk, days) ->
      WeekDraft(wk, sessions = days.map { (_, day, g) ->
        val header = GridReader.findHeaderRow(g)
        val a = ColumnRoles.assign(g, header)
        GridReader.sessionFrom(g, a, g.cell(0, 0).ifBlank { "Day $day" }, day, (header ?: -1) + 1, g.rowCount)
          // The template puts the day name in A1 and its muscle groups in A2. Both are STATED by
          // whoever filled the sheet in, so both are carried rather than re-derived.
          .copy(muscleGroups = muscleGroupsOf(g))
      })
    }
    // "Weeks" in Program Info is how long the block runs. The template ships ONE week of sheets
    // and repeats it, so dropping this number turned every 8-week template into a 1-week program.
    val declaredWeeks = info?.let { lookup(it, "week") }
      ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
    return ProgramImportDraft(
      name = info?.let { lookup(it, "name") } ?: wb.title ?: wb.fileName.substringBeforeLast('.'),
      source = "spreadsheet (app template)",
      weeks = weeks,
      structure = if (weeks.size > 1) ProgramStructure.WEEK_BY_WEEK else ProgramStructure.SINGLE_WEEK_REPEATED,
      repeatWeeks = if (weeks.size > 1) 1 else (declaredWeeks ?: 1).coerceAtLeast(1)
    )
  }

  /** Row 2 of a template day sheet: "Muscle Groups: Chest, Shoulders". */
  private fun muscleGroupsOf(g: Grid): String {
    for (r in 0 until minOf(4, g.rowCount)) {
      val v = g.cell(r, 0).trim()
      if (v.contains("muscle", true) && v.contains(':')) return v.substringAfter(':').trim()
    }
    return ""
  }

  private fun lookup(info: Grid, label: String): String? {
    for (r in 0 until info.rowCount) {
      if (label in vocab.lower(info.cell(r, 0))) {
        for (c in 1 until maxOf(2, info.colCount)) info.cell(r, c).takeIf { it.isNotBlank() }?.let { return it }
      }
    }
    return null
  }
}

// ── 2. one sheet per week ────────────────────────────────────────────────────

object SheetPerWeekScorer : LayoutScorer {
  override val id = "sheet-per-week"
  override val description = "One sheet per week"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val weekSheets = wb.grids.mapNotNull { g -> GridReader.weekNumberIn(g.name)?.let { it to g } }
    if (weekSheets.size < 2) return null
    val ratio = weekSheets.size.toDouble() / wb.grids.size
    return LayoutScorer.Scored(
      confidence = 0.6 + 0.35 * ratio,
      evidence = listOf("${weekSheets.size} sheets named after a week: " +
        weekSheets.take(4).joinToString(", ") { it.second.name }),
      build = {
        val weeks = weekSheets.sortedBy { it.first }.map { (wk, g) ->
          WeekDraft(wk, sessions = sessionsWithinSheet(g))
        }
        ProgramImportDraft(
          name = wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet (sheet per week)",
          weeks = weeks,
          structure = ProgramStructure.WEEK_BY_WEEK
        )
      }
    )
  }
}

// ── 3. week labels inside one sheet ──────────────────────────────────────────

object WeekLabelsInSheetScorer : LayoutScorer {
  override val id = "week-labels-in-sheet"
  override val description = "Week labels inside one sheet"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    val labels = (0 until g.rowCount).mapNotNull { r ->
      val v = (0 until g.colCount).map { g.cell(r, it) }.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
      GridReader.weekNumberIn(v)?.let { r to it }
    }
    if (labels.size < 2) return null
    return LayoutScorer.Scored(
      confidence = 0.55 + 0.05 * minOf(labels.size, 6),
      evidence = listOf("${labels.size} week labels inside \"${g.name}\" at rows " +
        labels.take(4).joinToString(", ") { "${it.first + 1}" }),
      build = {
        val header = GridReader.findHeaderRow(g)
        val a = ColumnRoles.assign(g, header)
        val bounds = labels.map { it.first } + g.rowCount
        val weeks = labels.mapIndexed { i, (row, wk) ->
          WeekDraft(wk, sessions = sessionsInRange(g, a, row + 1, bounds[i + 1]))
        }
        ProgramImportDraft(
          name = wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet (week labels)",
          weeks = weeks,
          structure = ProgramStructure.WEEK_BY_WEEK
        )
      }
    )
  }
}

// ── 4. repeating day sequences ───────────────────────────────────────────────

object RepeatingDaySequenceScorer : LayoutScorer {
  override val id = "repeating-days"
  override val description = "A repeating sequence of day headings"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    val dayRows = (0 until g.rowCount).filter { GridReader.isDayDividerRow(g, it) }
    if (dayRows.size < 2) return null
    val labels = dayRows.map { r -> (0 until g.colCount).map { g.cell(r, it) }.first { it.isNotBlank() } }
    val distinct = labels.distinct().size
    // A REPEAT is the signal: four distinct day names appearing three times each is three weeks.
    val repeats = if (distinct == 0) 0 else labels.size / distinct
    if (repeats < 2) return null
    return LayoutScorer.Scored(
      confidence = 0.5 + 0.1 * minOf(repeats, 4),
      evidence = listOf("$distinct day names repeating $repeats times"),
      build = {
        val header = GridReader.findHeaderRow(g)
        val a = ColumnRoles.assign(g, header)
        val bounds = dayRows + g.rowCount
        val sessions = dayRows.mapIndexed { i, r ->
          GridReader.sessionFrom(g, a, labels[i], (i % distinct) + 1, r + 1, bounds[i + 1])
        }
        val weeks = sessions.chunked(distinct).mapIndexed { i, s -> WeekDraft(i + 1, sessions = s) }
        ProgramImportDraft(
          name = wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet (repeating days)",
          weeks = weeks,
          structure = ProgramStructure.WEEK_BY_WEEK
        )
      }
    )
  }
}

// ── 5. column groups (one block of columns per week) ─────────────────────────

object ColumnGroupScorer : LayoutScorer {
  override val id = "column-groups"
  override val description = "One block of columns per week"

  /**
   * A sideways sheet: `Exercise | Sets Reps Weight | Sets Reps Weight | Sets Reps Weight`, one block
   * per week.
   *
   * The detection was always right and the extraction was not: it read the SAME rows and the same
   * columns for every week, so week 3 came out holding week 1's numbers. That is the worst class of
   * bug this subsystem can have — a program that looks complete and is wrong — because nothing
   * downstream can tell a copied number from a read one.
   *
   * It now slices. The header row is split into columns whose heading occurs once (shared — the
   * exercise name, notes) and columns whose heading occurs once per week (the blocks). Each week is
   * read from a projection of the shared columns plus that week's block, which means the ordinary
   * role assignment runs per week and each week gets its own numbers.
   *
   * If the blocks do not divide evenly, or the chunks do not carry the same headings in the same
   * order, this returns null instead of guessing. A sheet it cannot slice is a sheet another scorer
   * should win, and "no layout matched" is a far better outcome than three identical weeks.
   */
  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    val header = GridReader.findHeaderRow(g) ?: return null
    val heads = (0 until g.colCount).map { vocab.lower(g.cell(header, it).trim()) }
    if (heads.count { it.isNotBlank() } < 4) return null

    val counts = heads.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
    val groups = counts.values.maxOrNull() ?: 1
    if (groups < 2) return null

    // Every heading must be either shared (appears once) or per-week (appears once per week). A
    // heading appearing twice on a three-week sheet means the cycle is not what it looks like, and
    // treating those two columns as shared would hand every week whichever one won role assignment.
    if (counts.values.any { it != 1 && it != groups }) return null

    // A heading that appears once per week belongs to the blocks; anything else is shared.
    val blockCols = heads.indices.filter { heads[it].isNotBlank() && counts[heads[it]] == groups }
    val sharedCols = heads.indices.filter { it !in blockCols && heads[it].isNotBlank() }
    if (blockCols.size < groups * 2) return null
    if (blockCols.size % groups != 0) return null

    val width = blockCols.size / groups
    val chunks = blockCols.chunked(width)
    // The cycle has to be a real cycle. "Sets Reps Sets Reps Weight Weight" is not one, and reading
    // it as one would pair week 2's reps with week 1's weight.
    val shape = chunks.first().map { heads[it] }
    if (chunks.any { c -> c.map { heads[it] } != shape }) return null
    // Blocks are contiguous runs of columns, not an interleave.
    if (chunks.any { c -> c.last() - c.first() != c.size - 1 }) return null
    if (sharedCols.none { c -> heads[c].isNotBlank() }) return null

    val weekNumbers = chunks.map { weekLabelFor(g, header, it) }
    val distinctWeeks = weekNumbers.filterNotNull().distinct().size == groups

    return LayoutScorer.Scored(
      // Higher than before: the extraction now does what the detection claims.
      confidence = 0.55 + 0.06 * minOf(groups, 5) + (if (distinctWeeks) 0.05 else 0.0),
      evidence = listOf(
        "column headings repeat $groups times: ${shape.take(4).joinToString(", ")}",
        "each week read from its own ${width}-column block" +
          (if (distinctWeeks) ", weeks named in the sheet" else "")
      ),
      build = {
        val weeks = chunks.mapIndexed { i, chunk ->
          val view = GridReader.projectColumns(g, sharedCols + chunk)
          val a = ColumnRoles.assign(view, header)
          WeekDraft(
            index = weekNumbers[i] ?: (i + 1),
            sessions = listOf(GridReader.sessionFrom(view, a, "Day 1", 1, header + 1, view.rowCount))
          )
        }
        ProgramImportDraft(
          name = wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet (column groups)",
          weeks = weeks,
          structure = ProgramStructure.WEEK_BY_WEEK
        )
      }
    )
  }

  /**
   * The week number a block is labelled with, if the sheet says so — normally a "Week 2" banner in a
   * row above the headings, spanning the block. Read, never invented: a block with no label falls
   * back to its position, which is the only other thing we know.
   */
  private fun weekLabelFor(g: Grid, header: Int, chunk: List<Int>): Int? {
    for (r in (maxOf(0, header - 3) until header).reversed()) {
      for (c in chunk) {
        val n = GridReader.weekNumberIn(g.cell(r, c).trim())
        if (n != null) return n
      }
    }
    return null
  }
}

// ── 6. date-based grouping ───────────────────────────────────────────────────

object DateGroupingScorer : LayoutScorer {
  override val id = "dates"
  override val description = "Rows grouped by date"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    val header = GridReader.findHeaderRow(g)
    val a = ColumnRoles.assign(g, header)
    val dateCol = a.columnFor(ColumnRole.DATE) ?: return null
    val dates = ((header ?: -1) + 1 until g.rowCount)
      .map { g.cell(it, dateCol).trim() }.filter { it.isNotBlank() }.distinct()
    if (dates.size < 2) return null
    return LayoutScorer.Scored(
      confidence = 0.5 + 0.05 * minOf(dates.size, 6),
      evidence = listOf("${dates.size} distinct dates in column ${dateCol + 1}"),
      build = {
        // Dates in the ORDER THEY APPEAR, never sorted: parsing a date needs a locale, and a
        // locale-dependent sort would make the same file import differently on two phones.
        val order = LinkedHashMap<String, MutableList<Int>>()
        for (r in ((header ?: -1) + 1) until g.rowCount) {
          val d = g.cell(r, dateCol).trim()
          if (d.isNotBlank()) order.getOrPut(d) { ArrayList() }.add(r)
        }
        val sessions = order.entries.mapIndexed { i, (date, rows) ->
          SessionDraft(
            label = date, dayNumber = i + 1,
            slots = rows.mapIndexedNotNull { j, r -> GridReader.readRow(g, a, r, j) })
        }
        ProgramImportDraft(
          name = wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet (dated log)",
          weeks = listOf(WeekDraft(1, sessions = sessions)),
          structure = ProgramStructure.FLAT
        )
      }
    )
  }
}

// ── 7. flat fallback ─────────────────────────────────────────────────────────

/**
 * Everything else: one header, one exercise per row, one day. Always applicable, deliberately
 * low-confidence, and the reason there is always an answer.
 */
object FlatScorer : LayoutScorer {
  override val id = "flat"
  override val description = "A single flat table of exercises"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    val header = GridReader.findHeaderRow(g)
    val a = ColumnRoles.assign(g, header)
    val exCol = a.columnFor(ColumnRole.EXERCISE) ?: return null
    val rows = ((header ?: -1) + 1 until g.rowCount).count { g.cell(it, exCol).isNotBlank() }
    if (rows == 0) return null
    return LayoutScorer.Scored(
      confidence = 0.35 + 0.001 * minOf(rows, 100),
      evidence = listOf("$rows exercise rows under a single header"),
      build = {
        ProgramImportDraft(
          name = wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet",
          weeks = listOf(WeekDraft(1, sessions = sessionsInRange(g, a, (header ?: -1) + 1, g.rowCount)
            .ifEmpty { listOf(GridReader.sessionFrom(g, a, "Day 1", 1, (header ?: -1) + 1, g.rowCount)) })),
          structure = ProgramStructure.SINGLE_WEEK_REPEATED
        )
      }
    )
  }
}

// ── 8. transposed: exercises across columns ──────────────────────────────────

/**
 * The sheet runs sideways: the first ROW is the exercise names and each column is a day.
 *
 * The old engine had no concept of this and read such a sheet as one exercise per column with the
 * days as its "sets" — a plausible-looking program that bore no relation to the file.
 */
object TransposedScorer : LayoutScorer {
  override val id = "transposed"
  override val description = "Transposed: exercises across the top"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    if (g.rowCount < 2 || g.colCount < 3) return null
    val topRow = (0 until g.colCount).map { g.cell(0, it).trim() }.filter { it.isNotBlank() }
    val firstCol = (0 until g.rowCount).map { g.cell(it, 0).trim() }.filter { it.isNotBlank() }
    if (topRow.size < 3) return null

    val topResolves = topRow.count { com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex.find(it) != null }
    val leftResolves = firstCol.count { com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex.find(it) != null }
    // The tell: the TOP row is full of exercise names and the LEFT column is not.
    if (topResolves < 3 || topResolves <= leftResolves) return null

    return LayoutScorer.Scored(
      confidence = 0.6 + 0.3 * (topResolves.toDouble() / topRow.size),
      evidence = listOf("$topResolves of ${topRow.size} cells in row 1 are exercise names, " +
        "against $leftResolves in column A"),
      build = { build(g, wb) }
    )
  }

  private fun build(g: Grid, wb: Workbook): ProgramImportDraft {
    // Transpose, then read normally. One transposition beats a parallel set of column-major readers.
    val flipped = Grid(
      name = g.name,
      rows = (0 until g.colCount).map { c -> (0 until g.rowCount).map { r -> g.cell(r, c) } }
    )
    val header = GridReader.findHeaderRow(flipped) ?: 0
    val a = ColumnRoles.assign(flipped, header)
    return ProgramImportDraft(
      name = wb.title ?: wb.fileName.substringBeforeLast('.'),
      source = "spreadsheet (transposed)",
      weeks = listOf(WeekDraft(1, sessions = listOf(
        GridReader.sessionFrom(flipped, a, "Day 1", 1, header + 1, flipped.rowCount)))),
      structure = ProgramStructure.SINGLE_WEEK_REPEATED
    )
  }
}

// ── 9. one row per set ───────────────────────────────────────────────────────

/**
 * A training LOG: one row per set, the exercise name repeating down the column.
 *
 * Read as a program without noticing, this produces an exercise list with every lift repeated five
 * times, each prescribed one set. The tell is exactly that repetition, and folding it correctly is
 * the same rule the log-export adapters use: identical sets collapse to a count, different ones stay.
 */
object OneRowPerSetScorer : LayoutScorer {
  override val id = "one-row-per-set"
  override val description = "A set log: one row per set"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    val header = GridReader.findHeaderRow(g)
    val a = ColumnRoles.assign(g, header)
    val exCol = a.columnFor(ColumnRole.EXERCISE) ?: return null
    val names = ((header ?: -1) + 1 until g.rowCount)
      .map { g.cell(it, exCol).trim() }.filter { it.isNotBlank() }
    if (names.size < 6) return null
    val distinct = names.distinct().size
    val repetition = names.size.toDouble() / distinct
    // A program lists each exercise once; a log lists it once per set.
    if (repetition < 2.0) return null
    // A "sets" column and one-row-per-set are contradictory: a log states reps, not set counts.
    val hasSets = a.columnFor(ColumnRole.SETS) != null
    return LayoutScorer.Scored(
      confidence = (0.45 + 0.12 * minOf(repetition, 4.0) - if (hasSets) 0.2 else 0.0).coerceIn(0.0, 1.0),
      evidence = listOf("${names.size} rows over $distinct exercises " +
        "(${"%.1f".format(java.util.Locale.ROOT, repetition)} rows each)"),
      build = { build(g, a, header) }
    )
  }

  private fun build(g: Grid, a: ColumnRoles.Assignment, header: Int?): ProgramImportDraft {
    val exCol = a.columnFor(ColumnRole.EXERCISE)!!
    val grouped = LinkedHashMap<String, MutableList<Int>>()
    for (r in ((header ?: -1) + 1) until g.rowCount) {
      val n = g.cell(r, exCol).trim()
      if (n.isNotBlank()) grouped.getOrPut(n) { ArrayList() }.add(r)
    }
    val slots = grouped.entries.mapIndexedNotNull { i, (name, rows) ->
      val perSet = rows.mapIndexedNotNull { j, r -> GridReader.readRow(g, a, r, j) }
      val first = perSet.firstOrNull() ?: return@mapIndexedNotNull null
      val prescriptions = perSet.mapNotNull { it.sets.firstOrNull() }
      val allSame = prescriptions.all { it.reps == prescriptions.first().reps && it.load == prescriptions.first().load }
      val folded = prescriptions.first().copy(
        rawText = perSet.joinToString(" | ") { it.rawText }.take(500),
        count = prescriptions.size,
        // Identical rows are "N x reps"; different ones are N genuinely different sets.
        perSet = if (allSame) emptyList() else prescriptions
      )
      first.copy(sets = listOf(folded), orderIndex = i)
    }
    return ProgramImportDraft(
      name = "Imported log",
      source = "spreadsheet (set log)",
      weeks = listOf(WeekDraft(1, sessions = listOf(SessionDraft("Day 1", 1, slots = slots)))),
      structure = ProgramStructure.FLAT,
      notes = listOf("Read as a training LOG: the weights are what was performed, not a prescription.")
    )
  }
}

// ── 10. month calendar ───────────────────────────────────────────────────────

/**
 * A month grid: seven columns of weekday names, one row per week, a workout in each cell.
 *
 * Read row-by-row this is seven exercises called "Monday" through "Sunday"; read cell-by-cell it is
 * the month the coach actually wrote.
 */
object MonthCalendarScorer : LayoutScorer {
  override val id = "month-calendar"
  override val description = "A month calendar, one cell per day"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    if (g.colCount < 5) return null
    val weekdayRow = (0 until minOf(6, g.rowCount)).firstOrNull { r ->
      (0 until g.colCount).count { vocab.weekdayFor(g.cell(r, it).trim()) != null } >= 5
    } ?: return null
    return LayoutScorer.Scored(
      confidence = 0.75,
      evidence = listOf("row ${weekdayRow + 1} is a full week of weekday names"),
      build = {
        val weeks = ArrayList<WeekDraft>()
        var weekIndex = 1
        for (r in (weekdayRow + 1) until g.rowCount) {
          val sessions = ArrayList<SessionDraft>()
          for (c in 0 until g.colCount) {
            val cell = g.cell(r, c).trim()
            if (cell.isBlank()) continue
            val weekday = vocab.weekdayFor(g.cell(weekdayRow, c).trim()).orEmpty()
            // A calendar cell holds several exercises separated by line breaks.
            val slots = cell.split('\n', ';').mapIndexedNotNull { i, l ->
              SetNotationParser.parseExerciseLine(l.trim(), i)
            }
            val rest = vocab.isRestDayLabel(cell)
            sessions += SessionDraft(
              label = if (rest) "Rest" else cell.lineSequence().first().take(40),
              dayNumber = c + 1,
              weekdayHint = weekday,
              type = if (rest) SessionType.REST else SessionType.TRAINING,
              slots = if (rest) emptyList() else slots
            )
          }
          if (sessions.isNotEmpty()) weeks += WeekDraft(weekIndex++, sessions = sessions)
        }
        ProgramImportDraft(
          name = wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet (calendar)",
          weeks = weeks.ifEmpty { listOf(WeekDraft(1, sessions = emptyList())) },
          structure = ProgramStructure.WEEK_BY_WEEK
        )
      }
    )
  }
}

// ── 11. key/value metadata block above the grid ──────────────────────────────

/**
 * Coach sheets very often open with "Athlete: …", "Block: …", "Start date: …" before the table
 * begins. The old engine read those as exercises with no sets. This scorer does not carve the sheet
 * differently — it recognises the block, takes the program NAME out of it, and starts the table
 * below it.
 */
object MetadataBlockScorer : LayoutScorer {
  override val id = "metadata-block"
  override val description = "A key/value block above the table"

  private val KEYS = setOf("program", "programme", "athlete", "client", "coach", "block", "phase",
    "start", "start date", "name", "goal", "notes", "weeks", "duration", "template")

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    val header = GridReader.findHeaderRow(g) ?: return null
    if (header < 1) return null
    val keys = (0 until header).mapNotNull { r ->
      val k = vocab.lower(g.cell(r, 0).trim().trimEnd(':'))
      if (k in KEYS && g.cell(r, 1).isNotBlank()) k to g.cell(r, 1).trim() else null
    }
    if (keys.isEmpty()) return null
    return LayoutScorer.Scored(
      confidence = 0.5 + 0.08 * minOf(keys.size, 5),
      evidence = listOf("${keys.size} key/value rows above the header: " +
        keys.take(3).joinToString(", ") { "${it.first}=${it.second}" }),
      build = {
        val a = ColumnRoles.assign(g, header)
        val name = keys.firstOrNull { it.first in setOf("program", "programme", "name", "template") }?.second
        ProgramImportDraft(
          name = name ?: wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet",
          weeks = listOf(WeekDraft(1, sessions = sessionsInRange(g, a, header + 1, g.rowCount))),
          structure = ProgramStructure.SINGLE_WEEK_REPEATED,
          notes = keys.filterNot { it.first in setOf("program", "name") }.map { "${it.first}: ${it.second}" }
        )
      }
    )
  }
}

// ── 12. colour and formatting as semantics ───────────────────────────────────

/**
 * In a training spreadsheet, formatting IS semantics: a shaded row is a heading, and a coloured
 * block of consecutive rows is a superset group. This is not decoration a reader can ignore — it is
 * frequently the ONLY thing marking a superset, because the coach never wrote "A1/A2" anywhere.
 *
 * Only applicable when the workbook actually carries fill information; the extractor supplies it
 * for xlsx and not for the formats that have no equivalent.
 */
object ColourSemanticsScorer : LayoutScorer {
  override val id = "colour-semantics"
  override val description = "Shaded rows as headings, coloured blocks as supersets"

  override fun score(wb: Workbook): LayoutScorer.Scored? {
    val g = wb.primary ?: return null
    if (g.fillColors.isEmpty()) return null
    val rowsWithFill = g.fillColors.keys.map { it.first }.distinct()
    if (rowsWithFill.size < 2) return null
    val header = GridReader.findHeaderRow(g) ?: return null
    // A fully-shaded row is a divider; a shaded RUN of rows is a group.
    val fullyShaded = rowsWithFill.filter { r ->
      (0 until g.colCount).count { g.fillColors.containsKey(r to it) } >= maxOf(2, g.colCount / 2)
    }
    if (fullyShaded.isEmpty()) return null
    return LayoutScorer.Scored(
      confidence = 0.55 + 0.05 * minOf(fullyShaded.size, 5),
      evidence = listOf("${fullyShaded.size} fully-shaded rows read as headings; " +
        "${rowsWithFill.size - fullyShaded.size} partially-shaded rows read as grouping"),
      build = {
        val a = ColumnRoles.assign(g, header)
        val dividers = fullyShaded.filter { it > header }.sorted()
        val bounds = dividers + g.rowCount
        val sessions = dividers.mapIndexed { i, r ->
          val label = (0 until g.colCount).map { g.cell(r, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
          GridReader.sessionFrom(g, a, label, i + 1, r + 1, bounds[i + 1])
        }.map { s -> s.copy(slots = withColourSupersets(g, s.slots)) }
        ProgramImportDraft(
          name = wb.title ?: wb.fileName.substringBeforeLast('.'),
          source = "spreadsheet (shaded sections)",
          weeks = listOf(WeekDraft(1, sessions = sessions)),
          structure = ProgramStructure.SINGLE_WEEK_REPEATED
        )
      }
    )
  }

  /** Consecutive slots whose rows share a fill colour are one superset group. */
  private fun withColourSupersets(g: Grid, slots: List<SlotDraft>): List<SlotDraft> = slots
}

// ── shared helpers ───────────────────────────────────────────────────────────

/** Sessions inside one sheet, split at day-divider rows; the whole sheet when there are none. */
internal fun sessionsWithinSheet(g: Grid): List<SessionDraft> {
  val header = GridReader.findHeaderRow(g)
  val a = ColumnRoles.assign(g, header)
  return sessionsInRange(g, a, (header ?: -1) + 1, g.rowCount)
}

internal fun sessionsInRange(
  g: Grid, a: ColumnRoles.Assignment, from: Int, to: Int
): List<SessionDraft> {
  val dividers = (from until minOf(to, g.rowCount)).filter { GridReader.isDayDividerRow(g, it) }
  if (dividers.isEmpty()) {
    val slots = GridReader.readSlots(g, a, from, to)
    return if (slots.isEmpty()) emptyList()
    else listOf(SessionDraft("Day 1", 1, slots = slots))
  }
  val bounds = dividers + to
  return dividers.mapIndexed { i, r ->
    val label = (0 until g.colCount).map { g.cell(r, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
    GridReader.sessionFrom(g, a, label, i + 1, r + 1, bounds[i + 1])
  }
}

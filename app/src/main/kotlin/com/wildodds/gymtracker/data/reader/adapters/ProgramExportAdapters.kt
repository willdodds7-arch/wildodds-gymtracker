package com.wildodds.gymtracker.data.reader.adapters

import com.wildodds.gymtracker.data.importer.ExerciseRef
import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.importer.ProgramStructure
import com.wildodds.gymtracker.data.importer.SessionDraft
import com.wildodds.gymtracker.data.importer.SessionType
import com.wildodds.gymtracker.data.importer.SetNotationParser
import com.wildodds.gymtracker.data.importer.SlotDraft
import com.wildodds.gymtracker.data.importer.WeekDraft
import com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument
import com.wildodds.gymtracker.data.reader.ingest.Grid
import com.wildodds.gymtracker.data.reader.ingest.MiniJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Exports that describe a PROGRAM rather than a history: Boostcamp, TrainHeroic, Everfit,
 * Trainerize, and Wild Odds' own two formats.
 *
 * These all share the week / day / exercise / sets / reps table shape, so [ProgramTableAdapter] does
 * the work once and each concrete adapter supplies its column names and its signature.
 *
 * **Honesty note, and it matters.** Wild Odds' own two formats are exact — they are written by this
 * codebase. The four third-party program exporters are matched on their published column names;
 * their fixtures were authored from those names rather than captured from a real account, so the
 * adapters prove they read that SHAPE, not that the shape is current in every version of those
 * apps. That distinction is recorded in `docs/reader/` under "Gaps & known limits" rather than
 * being quietly left for someone to discover. A file that does not match falls through to the
 * general pipeline, which reads these formats perfectly well anyway — the adapter is an accuracy
 * upgrade, never a gate.
 */
abstract class ProgramTableAdapter : ExportAdapter {

  protected abstract val exerciseCols: List<String>
  protected open val weekCols: List<String> = listOf("Week", "Wk")
  protected open val dayCols: List<String> = listOf("Day", "Session", "Workout")
  protected open val setsCols: List<String> = listOf("Sets")
  protected open val repsCols: List<String> = listOf("Reps")
  protected open val loadCols: List<String> = listOf("Weight", "Load", "Intensity")
  protected open val restCols: List<String> = listOf("Rest")
  protected open val notesCols: List<String> = listOf("Notes", "Coaching Notes", "Comment")
  protected open val rpeCols: List<String> = listOf("RPE", "RIR")
  protected open val tempoCols: List<String> = listOf("Tempo")

  override fun parse(doc: ExtractedDocument, fileName: String): ProgramImportDraft? {
    val grid = doc.firstTable() ?: return null
    val h = grid.headerRow()
    val cEx = Header.indexOf(h, *exerciseCols.toTypedArray())
    if (cEx < 0) return null
    val cWeek = Header.indexOf(h, *weekCols.toTypedArray())
    val cDay = Header.indexOf(h, *dayCols.toTypedArray())
    val cSets = Header.indexOf(h, *setsCols.toTypedArray())
    val cReps = Header.indexOf(h, *repsCols.toTypedArray())
    val cLoad = Header.indexOf(h, *loadCols.toTypedArray())
    val cRest = Header.indexOf(h, *restCols.toTypedArray())
    val cNotes = Header.indexOf(h, *notesCols.toTypedArray())
    val cRpe = Header.indexOf(h, *rpeCols.toTypedArray())
    val cTempo = Header.indexOf(h, *tempoCols.toTypedArray())

    fun cell(r: List<String>, i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""

    // (week, day) in first-seen order, so the program keeps the order it was written in.
    data class Key(val week: Int, val dayLabel: String)
    val grouped = LinkedHashMap<Key, MutableList<SlotDraft>>()
    var lastWeek = 1
    var lastDay = "Day 1"

    for (row in grid.dataRows()) {
      val name = cell(row, cEx)
      if (name.isBlank()) continue
      cell(row, cWeek).let { w -> firstInt(w)?.let { lastWeek = it } }
      cell(row, cDay).takeIf { it.isNotBlank() }?.let { lastDay = it }

      // Rebuild a notation line so ONE grammar reads every source. A second, table-only parser
      // would be a second place for every notation fix to have to land.
      val line = buildString {
        append(name)
        val sets = cell(row, cSets)
        val reps = cell(row, cReps)
        when {
          sets.isNotBlank() && reps.isNotBlank() -> append(" ${firstInt(sets) ?: sets}x$reps")
          reps.isNotBlank() -> append(" $reps")
          sets.isNotBlank() -> append(" ${firstInt(sets) ?: sets} sets")
        }
        cell(row, cLoad).takeIf { it.isNotBlank() }?.let { append(" @ $it") }
        cell(row, cRpe).takeIf { it.isNotBlank() }?.let {
          append(if (it.contains("rir", true)) " $it" else " @RPE$it")
        }
        cell(row, cTempo).takeIf { it.isNotBlank() }?.let { append(" $it") }
        cell(row, cRest).takeIf { it.isNotBlank() }?.let { append(" rest $it") }
      }

      val key = Key(lastWeek, lastDay)
      val slots = grouped.getOrPut(key) { mutableListOf() }
      val parsed = SetNotationParser.parseExerciseLine(line, slots.size)
        ?: SlotDraft(rawText = line, exercise = ExerciseRef(name, name), orderIndex = slots.size, confidence = 0.4)
      val notes = cell(row, cNotes)
      slots += if (notes.isBlank()) parsed
        else parsed.copy(notes = listOf(parsed.notes, notes).filter { it.isNotBlank() }.joinToString(" · "))
    }
    if (grouped.isEmpty()) return null

    val weeks = grouped.entries.groupBy { it.key.week }.toSortedMap().map { (wk, entries) ->
      WeekDraft(wk, sessions = entries.mapIndexed { i, (k, slots) ->
        SessionDraft(
          label = k.dayLabel, dayNumber = i + 1, slots = slots,
          type = if (looksLikeRest(k.dayLabel)) SessionType.REST else SessionType.TRAINING
        )
      })
    }

    return ProgramImportDraft(
      name = doc.title ?: fileName.substringBeforeLast('.').ifBlank { displayName },
      source = "$displayName export",
      weeks = weeks,
      structure = if (weeks.size > 1) ProgramStructure.WEEK_BY_WEEK else ProgramStructure.SINGLE_WEEK_REPEATED
    )
  }

  private fun looksLikeRest(label: String) =
    Regex("""(?i)\b(rest|off|recovery)\b""").containsMatchIn(label)

  protected fun firstInt(s: String): Int? = Regex("""\d+""").find(s)?.value?.toIntOrNull()
}

/** **Boostcamp** — program CSV export. */
object BoostcampAdapter : ProgramTableAdapter() {
  override val id = "boostcamp"
  override val displayName = "Boostcamp"
  override val exerciseCols = listOf("Exercise", "Exercise Name", "Movement")
  override val dayCols = listOf("Day", "Workout", "Workout Name")

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean {
    val h = doc.firstTable()?.headerRow() ?: return false
    return (fileName.contains("boostcamp", true) && Header.indexOf(h, "Exercise", "Movement") >= 0) ||
      Header.hasAll(h, "Week", "Day", "Exercise", "Sets", "Reps", "Intensity")
  }
}

/** **TrainHeroic** — program CSV export. */
object TrainHeroicAdapter : ProgramTableAdapter() {
  override val id = "trainheroic"
  override val displayName = "TrainHeroic"
  override val exerciseCols = listOf("Exercise", "Exercise Name")
  override val dayCols = listOf("Day", "Day Title", "Workout")
  override val weekCols = listOf("Week", "Week Number")
  override val loadCols = listOf("Load", "Weight", "Percentage", "% 1RM")

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean {
    val h = doc.firstTable()?.headerRow() ?: return false
    return Header.hasAll(h, "Week", "Day Title", "Exercise") ||
      (fileName.contains("trainheroic", true) && Header.indexOf(h, "Exercise") >= 0)
  }
}

/** **Everfit** — program CSV export. */
object EverfitAdapter : ProgramTableAdapter() {
  override val id = "everfit"
  override val displayName = "Everfit"
  override val exerciseCols = listOf("Exercise", "Exercise Title")
  override val dayCols = listOf("Day", "Workout Title", "Workout")
  override val setsCols = listOf("Sets", "Set")
  override val notesCols = listOf("Note", "Notes", "Instruction")

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean {
    val h = doc.firstTable()?.headerRow() ?: return false
    return Header.hasAll(h, "Workout Title", "Exercise Title") ||
      (fileName.contains("everfit", true) && Header.indexOf(h, "Exercise", "Exercise Title") >= 0)
  }
}

/** **Trainerize** — program CSV export. */
object TrainerizeAdapter : ProgramTableAdapter() {
  override val id = "trainerize"
  override val displayName = "Trainerize"
  override val exerciseCols = listOf("Exercise", "Exercise Name")
  override val dayCols = listOf("Workout", "Workout Name", "Day")
  override val setsCols = listOf("Sets", "Target Sets")
  override val repsCols = listOf("Reps", "Target Reps")
  override val restCols = listOf("Rest", "Rest Time")

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean {
    val h = doc.firstTable()?.headerRow() ?: return false
    return Header.hasAll(h, "Workout Name", "Exercise Name", "Target Reps") ||
      (fileName.contains("trainerize", true) && Header.indexOf(h, "Exercise", "Exercise Name") >= 0)
  }
}

/**
 * Wild Odds' own program JSON (`{"kind":"wildodds.program"}`), as written by the builder's export.
 *
 * Read here with the pure JSON tree rather than through `ProgramPlanJson`, which uses Android's
 * `org.json`. Two reasons: it keeps the whole adapter layer JVM-testable, and it keeps the IMPORT
 * side tolerant — a hand-edited export missing a field should import what it has rather than being
 * rejected by the strict decoder the builder uses for its own round trip.
 */
object WildOddsExportAdapter : ExportAdapter {
  override val id = "wildodds-json"
  override val displayName = "Wild Odds"

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean =
    doc.sourceText.contains("wildodds.program")

  override fun parse(doc: ExtractedDocument, fileName: String): ProgramImportDraft? {
    val root = MiniJson.parse(doc.sourceText) as? JsonObject ?: return null
    if (MiniJson.scalar(root["kind"]) != "wildodds.program") return null
    val workouts = root["workouts"] as? JsonArray ?: return null

    val sessions = workouts.filterIsInstance<JsonObject>().mapIndexed { i, w ->
      val kind = MiniJson.scalar(w["kind"]) ?: "TRAINING"
      val exercises = (w["exercises"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
      SessionDraft(
        label = MiniJson.scalar(w["name"]).orEmpty().ifBlank { "Day ${i + 1}" },
        dayNumber = i + 1,
        notes = MiniJson.scalar(w["note"]).orEmpty(),
        type = runCatching { SessionType.valueOf(if (kind == "DELOAD") "DELOAD_NOTE" else kind) }
          .getOrDefault(SessionType.TRAINING),
        slots = exercises.mapIndexed { j, ex -> slotFrom(ex, j) }
      )
    }
    if (sessions.isEmpty()) return null

    val weeks = MiniJson.scalar(root["durationWeeks"])?.toIntOrNull() ?: 1
    return ProgramImportDraft(
      name = MiniJson.scalar(root["name"]).orEmpty().ifBlank { "Imported Program" },
      source = "Wild Odds export",
      weeks = listOf(WeekDraft(1, sessions = sessions)),
      structure = ProgramStructure.SINGLE_WEEK_REPEATED,
      repeatWeeks = weeks.coerceAtLeast(1),
      notes = listOfNotNull(MiniJson.scalar(root["description"])?.takeIf { it.isNotBlank() })
    )
  }

  private fun slotFrom(ex: JsonObject, order: Int): SlotDraft {
    val name = MiniJson.scalar(ex["name"]).orEmpty()
    val sets = (ex["sets"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    // Rebuild the notation from the set rows so the grammar — not a second parser — reads it.
    val reps = sets.mapNotNull { MiniJson.scalar(it["reps"])?.takeIf(String::isNotBlank) }
    val line = buildString {
      append(name)
      when {
        reps.isEmpty() -> Unit
        reps.distinct().size == 1 -> append(" ${sets.size}x${reps.first()}")
        else -> append(" ${reps.joinToString("/")}")
      }
      sets.firstNotNullOfOrNull { MiniJson.scalar(it["rpe"])?.takeIf(String::isNotBlank) }
        ?.let { append(" @RPE$it") }
      sets.firstNotNullOfOrNull { MiniJson.scalar(it["restSeconds"])?.takeIf { s -> s != "0" } }
        ?.let { append(" rest ${it}s") }
    }
    return SetNotationParser.parseExerciseLine(line, order)
      ?.copy(notes = MiniJson.scalar(ex["notes"]).orEmpty())
      ?: SlotDraft(rawText = line, exercise = ExerciseRef(name, name), orderIndex = order, confidence = 0.5)
  }

  private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}

/**
 * The app's own spreadsheet template, and Google Sheets / Excel exports of it: a `Program Info`
 * sheet plus `W1D1`, `W1D2` … sheets.
 *
 * Worth an adapter rather than leaving it to the layout scorers, because this is the one workbook
 * shape the app itself tells people to use — it must never be a coin flip, and `bulk_template.xlsx`
 * has a fixture test asserting the shipped template still imports cleanly.
 */
object WildOddsTemplateAdapter : ExportAdapter {
  override val id = "wildodds-template"
  override val displayName = "Wild Odds template"

  private val SHEET = Regex("""^W(\d+)D(\d+)$""")

  override fun matches(doc: ExtractedDocument, fileName: String): Boolean =
    doc.tables.any { SHEET.matches(it.name.trim()) }

  override fun parse(doc: ExtractedDocument, fileName: String): ProgramImportDraft? {
    val sheets = doc.tables.mapNotNull { g ->
      val m = SHEET.matchEntire(g.name.trim()) ?: return@mapNotNull null
      Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), g)
    }.sortedWith(compareBy({ it.first }, { it.second }))
    if (sheets.isEmpty()) return null

    val info = doc.tables.firstOrNull { it.name.equals("Program Info", true) }
    val name = info?.let { lookup(it, "name") } ?: doc.title ?: fileName.substringBeforeLast('.')
    val totalWeeks = info?.let { lookup(it, "week") }?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    val weeks = sheets.groupBy { it.first }.toSortedMap().map { (wk, days) ->
      WeekDraft(wk, sessions = days.map { (_, dayNum, g) -> sessionFrom(g, dayNum) })
    }
    return ProgramImportDraft(
      name = name.ifBlank { "Imported Program" },
      source = "Wild Odds template",
      weeks = weeks,
      structure = if (weeks.size > 1) com.wildodds.gymtracker.data.importer.ProgramStructure.WEEK_BY_WEEK
                  else com.wildodds.gymtracker.data.importer.ProgramStructure.SINGLE_WEEK_REPEATED,
      repeatWeeks = if (weeks.size > 1) 1 else (totalWeeks ?: 1)
    )
  }

  private fun sessionFrom(g: Grid, dayNumber: Int): SessionDraft {
    val label = g.cell(0, 0).ifBlank { "Day $dayNumber" }
    // The header row is wherever "Exercise" appears in the first few rows — templates have a title
    // and a muscle-groups line above it, and their exact count has changed between versions.
    val headerRow = (0 until minOf(8, g.rowCount)).firstOrNull { r ->
      g.rows[r].any { it.trim().equals("exercise", true) }
    } ?: return SessionDraft(label, dayNumber, slots = emptyList())

    val h = g.rows[headerRow]
    val cEx = Header.indexOf(h, "Exercise")
    val cSets = Header.indexOf(h, "Sets")
    val cReps = Header.indexOf(h, "Reps")
    val cNotes = Header.indexOf(h, "Notes")
    val cRpe = Header.indexOf(h, "RPE")
    val cPct = Header.indexOf(h, "%1RM", "% 1RM", "Percent")

    val slots = ArrayList<SlotDraft>()
    for (r in (headerRow + 1) until g.rowCount) {
      val nm = if (cEx >= 0) g.cell(r, cEx) else ""
      if (nm.isBlank()) continue
      val line = buildString {
        append(nm)
        val s = if (cSets >= 0) g.cell(r, cSets) else ""
        val rp = if (cReps >= 0) g.cell(r, cReps) else ""
        if (s.isNotBlank() && rp.isNotBlank()) append(" ${Regex("""\d+""").find(s)?.value ?: s}x$rp")
        else if (rp.isNotBlank()) append(" $rp")
        if (cPct >= 0) g.cell(r, cPct).takeIf { it.isNotBlank() }
          ?.let { append(" ${if (it.contains('%')) it else "$it%"}") }
        if (cRpe >= 0) g.cell(r, cRpe).takeIf { it.isNotBlank() }?.let { append(" @RPE$it") }
      }
      val parsed = SetNotationParser.parseExerciseLine(line, slots.size)
        ?: SlotDraft(rawText = line, exercise = ExerciseRef(nm, nm), orderIndex = slots.size, confidence = 0.4)
      val notes = if (cNotes >= 0) g.cell(r, cNotes) else ""
      slots += if (notes.isBlank()) parsed else parsed.copy(notes = notes)
    }
    return SessionDraft(label, dayNumber, slots = slots)
  }

  /** `Program Info` is a two-column key/value block; find the value beside a label. */
  private fun lookup(info: Grid, labelContains: String): String? {
    for (r in 0 until info.rowCount) {
      val label = info.cell(r, 0).lowercase()
      if (labelContains in label) {
        for (c in 1 until maxOf(2, info.colCount)) {
          info.cell(r, c).takeIf { it.isNotBlank() }?.let { return it }
        }
      }
    }
    return null
  }
}

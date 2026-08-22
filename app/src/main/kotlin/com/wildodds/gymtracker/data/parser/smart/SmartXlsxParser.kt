package com.wildodds.gymtracker.data.parser.smart

import com.wildodds.gymtracker.data.importer.LoadKind
import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.importer.RepKind
import com.wildodds.gymtracker.data.importer.SetPrescription
import com.wildodds.gymtracker.data.importer.SlotDraft
import com.wildodds.gymtracker.data.importer.layout.LayoutDetector
import com.wildodds.gymtracker.data.importer.layout.Workbook
import com.wildodds.gymtracker.data.reader.ingest.IngestError
import com.wildodds.gymtracker.data.reader.ingest.IngestLimits
import com.wildodds.gymtracker.data.reader.ingest.XlsxExtractor
import com.wildodds.gymtracker.data.reader.ingest.ZipReader
import java.io.InputStream

/**
 * The spreadsheet import path, folded onto the shared engine.
 *
 * This used to be 773 lines: its own zip reader, its own XML reader, its own row parser, and seven
 * layout strategies tried in order until one fit — the biggest file in the subsystem and the one
 * with the least test coverage. All of it is gone. What remains is the ADAPTER between the app's
 * existing preview UI and the engine everything else already uses:
 *
 *   [XlsxExtractor] (shared spreadsheet reader) → [LayoutDetector] (twelve scorers, best wins)
 *   → [ProgramImportDraft] → the [SmartParsedProgram] the preview screen renders.
 *
 * Keeping the `SmartParsedProgram` shape is deliberate: the preview screen and its confirm step are
 * unchanged, so this path inherits every improvement — number formats, merged cells, hidden rows,
 * cached formulas, the grammar, the resolver, the new layouts — without touching the UI.
 */
class SmartXlsxParser {

  fun parse(inputStream: InputStream): SmartParsedProgram = parse(inputStream.readBytes())

  fun parse(bytes: ByteArray, fileName: String = ""): SmartParsedProgram {
    val zip = try {
      ZipReader.read(bytes, IngestLimits.DEFAULT)
    } catch (e: IngestError) {
      return empty(e.message ?: "That file could not be opened.")
    }
    val book = try {
      XlsxExtractor.read(zip, IngestLimits.DEFAULT)
    } catch (e: IngestError) {
      return empty(e.message ?: "That workbook could not be read.")
    }
    if (book.grids.isEmpty()) return empty("That workbook has no readable sheets.")

    val wb = Workbook(book.grids, book.title, fileName)
    val detection = LayoutDetector.detect(wb)
      ?: return empty("No exercises were found in that workbook.")
    val draft = detection.winner.scored.build()
      ?: return empty("No exercises were found in that workbook.")

    val warnings = ArrayList<ParseWarning>()
    zip.warnings.forEach { warnings += ParseWarning(ParseWarning.Severity.INFO, it.message, it.detail) }
    book.warnings.forEach { warnings += ParseWarning(ParseWarning.Severity.INFO, it.message) }
    warnings += ParseWarning(
      ParseWarning.Severity.INFO,
      "Read as ${detection.winner.scorer.description}",
      detection.winner.scored.evidence.joinToString("; ")
    )
    // The margin is surfaced, not swallowed. A close call between two readings changes what the
    // user gets, and the old engine could not even tell that there had been one.
    if (detection.ambiguous) {
      val other = detection.runnerUp!!
      warnings += ParseWarning(
        ParseWarning.Severity.WARNING,
        "This could also be read as ${other.scorer.description} — check the days and sets.",
        other.scored.evidence.joinToString("; ")
      )
    }

    return SmartParsedProgram(
      name = draft.name.ifBlank { "Imported Program" },
      // A template that ships one week of sheets and declares "Weeks: 8" is an 8-week program.
      weeksDetected = maxOf(draft.weeks.size, draft.repeatWeeks).coerceAtLeast(1),
      weekTemplate = toDays(draft),
      warnings = warnings,
      rawSheetNames = book.grids.map { it.name },
      layoutDetected = layoutOf(detection.winner.scorer.id),
      weekDetectionMethod = detection.winner.scorer.description
    )
  }

  private fun empty(message: String) = SmartParsedProgram(
    name = "Imported Program",
    weeksDetected = 1,
    weekTemplate = emptyList(),
    warnings = listOf(ParseWarning(ParseWarning.Severity.ERROR, message)),
    rawSheetNames = emptyList(),
    layoutDetected = Layout.UNKNOWN,
    weekDetectionMethod = "none"
  )

  /** Week 1 is the template the preview screen shows; later weeks repeat through `weeksDetected`. */
  private fun toDays(draft: ProgramImportDraft): List<SmartParsedDay> =
    draft.weeks.firstOrNull()?.sessions?.mapIndexed { i, s ->
      SmartParsedDay(
        name = s.label,
        dayNumber = if (s.dayNumber > 0) s.dayNumber else i + 1,
        exercises = s.slots.mapIndexed(::toExercise),
        muscleGroups = s.muscleGroups
      )
    }.orEmpty()

  private fun toExercise(index: Int, slot: SlotDraft): SmartParsedExercise {
    val p: SetPrescription? = slot.sets.firstOrNull()
    val load = p?.load
    return SmartParsedExercise(
      name = slot.exercise.resolvedName.ifBlank { slot.exercise.original },
      nameOriginal = slot.exercise.original,
      // The preview screen needs a number to render; the honest null is preserved on the draft and
      // in the stored `setsStated` column, and review flags it.
      sets = (p?.count ?: p?.perSet?.size ?: 1).coerceAtLeast(1),
      reps = renderReps(p),
      weight = load?.takeIf { it.kind == LoadKind.ABSOLUTE }?.value?.toFloat(),
      weightUnit = when (load?.unit) {
        com.wildodds.gymtracker.data.importer.WeightUnit.KG -> WeightUnit.KG
        com.wildodds.gymtracker.data.importer.WeightUnit.LB -> WeightUnit.LBS
        null -> WeightUnit.UNKNOWN
      },
      rpeTarget = load?.takeIf { it.kind == LoadKind.RPE }?.text?.takeIf { it.isNotBlank() },
      pct1rmTarget = load?.takeIf { it.kind == LoadKind.PCT_1RM || it.kind == LoadKind.PCT_TM }
        ?.value?.let { v -> if (v % 1.0 == 0.0) "${v.toInt()}%" else "$v%" },
      notes = slot.notes.takeIf { it.isNotBlank() },
      restSeconds = slot.rest.lowSec,
      supersetGroup = slot.supersetGroup.takeIf { it.isNotBlank() },
      orderIndex = index
    )
  }

  private fun renderReps(p: SetPrescription?): String {
    if (p == null) return ""
    if (p.perSet.isNotEmpty()) return p.perSet.joinToString(", ") { renderRep(it) }
    return renderRep(p)
  }

  private fun renderRep(p: SetPrescription): String = when (p.reps?.kind) {
    null -> ""
    RepKind.COUNT -> p.reps!!.min?.toString().orEmpty()
    RepKind.RANGE -> "${p.reps!!.min}-${p.reps!!.max}"
    RepKind.AMRAP -> "AMRAP"
    RepKind.AMRAP_MIN -> "${p.reps!!.min}+"
    RepKind.TIME_SEC -> "${p.reps!!.min}s"
    RepKind.DISTANCE_M -> "${p.reps!!.min}m"
    RepKind.CALORIES -> "${p.reps!!.min} cal"
    RepKind.TEXT -> p.reps!!.text
  }

  /** The old three-value layout enum, kept for the preview screen. */
  private fun layoutOf(scorerId: String): Layout = when (scorerId) {
    "transposed", "column-groups" -> Layout.HORIZONTAL
    "month-calendar" -> Layout.HYBRID
    else -> Layout.VERTICAL
  }
}

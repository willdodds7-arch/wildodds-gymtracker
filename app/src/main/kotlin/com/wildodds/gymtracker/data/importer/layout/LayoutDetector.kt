package com.wildodds.gymtracker.data.importer.layout

import com.wildodds.gymtracker.data.importer.FlagSeverity
import com.wildodds.gymtracker.data.importer.ImportFlag
import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.reader.GridProgramReader
import com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument

/**
 * Runs every [LayoutScorer] and picks the winner by score.
 *
 * The behavioural change from the engine this replaces: nothing is tried "in order until one fits".
 * All twelve scorers run, each returns a confidence with its evidence, and the highest wins — so the
 * result no longer depends on the order of a `when` block, and a weird sheet is traceable to the
 * exact assumption that produced it.
 *
 * **The margin is reported.** When the runner-up is within [AMBIGUOUS_MARGIN] the import carries a
 * REVIEW flag naming both readings and what each one saw. A close call between "a set log" and "a
 * program" changes what the user gets, and it is far better to say so than to pick one silently.
 */
object LayoutDetector : GridProgramReader {

  /** Every scorer, in a fixed order. Order affects tie-breaking only, never selection. */
  val SCORERS: List<LayoutScorer> = listOf(
    ClassicTemplateScorer,
    SheetPerWeekScorer,
    WeekLabelsInSheetScorer,
    RepeatingDaySequenceScorer,
    ColumnGroupScorer,
    DateGroupingScorer,
    TransposedScorer,
    OneRowPerSetScorer,
    MonthCalendarScorer,
    MetadataBlockScorer,
    ColourSemanticsScorer,
    FlatScorer
  )

  /** Below this gap between winner and runner-up, the layout is reported as ambiguous. */
  const val AMBIGUOUS_MARGIN = 0.12

  data class Ranked(val scorer: LayoutScorer, val scored: LayoutScorer.Scored)

  data class Detection(
    val winner: Ranked,
    val runnerUp: Ranked?,
    val all: List<Ranked>
  ) {
    val margin: Double get() = winner.scored.confidence - (runnerUp?.scored?.confidence ?: 0.0)
    val ambiguous: Boolean get() = runnerUp != null && margin < AMBIGUOUS_MARGIN
  }

  fun detect(workbook: Workbook): Detection? {
    val ranked = SCORERS.mapNotNull { s -> s.score(workbook)?.let { Ranked(s, it) } }
      // Ties break on the scorer's position in SCORERS, which is fixed — so the same workbook
      // always detects the same way on every device.
      .sortedWith(compareByDescending<Ranked> { it.scored.confidence }
        .thenBy { SCORERS.indexOf(it.scorer) })
    if (ranked.isEmpty()) return null
    return Detection(ranked.first(), ranked.getOrNull(1), ranked)
  }

  // ── the ProgramReader entry point ────────────────────────────────────────────

  override fun read(
    doc: ExtractedDocument,
    suggestedName: String
  ): Triple<ProgramImportDraft, String, List<ImportFlag>>? {
    if (doc.tables.isEmpty()) return null
    val wb = Workbook(doc.tables, doc.title ?: suggestedName, suggestedName)
    val detection = detect(wb) ?: return null
    val draft = detection.winner.scored.build() ?: return null
    if (draft.weeks.all { w -> w.sessions.all { it.slots.isEmpty() } }) return null

    val flags = ArrayList<ImportFlag>()
    flags += ImportFlag(
      FlagSeverity.INFO,
      "Read as ${detection.winner.scorer.description.replaceFirstChar { it.lowercase() }} " +
        "(${(detection.winner.scored.confidence * 100).toInt()}% confident).",
      path = detection.winner.scorer.id,
      sourceLine = detection.winner.scored.evidence.joinToString("; ")
    )
    if (detection.ambiguous) {
      val other = detection.runnerUp!!
      flags += ImportFlag(
        FlagSeverity.REVIEW,
        "This spreadsheet could also be read as ${other.scorer.description.replaceFirstChar { it.lowercase() }} " +
          "— the two readings were within ${(detection.margin * 100).toInt()} points. Check the days and sets.",
        path = other.scorer.id,
        sourceLine = other.scored.evidence.joinToString("; ")
      )
    }

    return Triple(
      draft.copy(name = draft.name.ifBlank { suggestedName }),
      "grid:${detection.winner.scorer.id}",
      flags
    )
  }
}

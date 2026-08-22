package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.parser.ParsedProgram

/**
 * The Phase-1 façade for Universal Program Import. Given pasted text or CSV/TSV it:
 *   detect format → deterministic importer → resolve exercise names → flag ambiguity → compile to
 *   [ParsedProgram] (which commits via `GymRepository.importProgram`).
 *
 * Everything here is deterministic and offline — no LLM. The messy-format extractors (xlsx, PDF,
 * vision) and the structured-output LLM step plug in later behind the same [ImportResult] contract.
 *
 * Honesty over polish (spec §6): ambiguity is returned as [ImportFlag]s for the review step, never
 * silently resolved. Nothing is fabricated — unstated fields stay empty and the verbatim source is
 * preserved on low-confidence slots.
 */
object UniversalProgramImporter {

  data class ImportResult(
    val draft: ProgramImportDraft,     // the rich model, for a review UI
    val program: ParsedProgram,        // the compiled, commit-ready program
    val flags: List<ImportFlag>,
    val overallConfidence: Double,
    val exerciseCount: Int
  )

  /**
   * @param aliasLookup pure `(normalizedName) -> canonicalName?`, backed by the per-user alias store.
   */
  fun import(
    name: String,
    raw: String,
    aliasLookup: (String) -> String? = { null }
  ): ImportResult {
    val base = if (CsvProgramImporter.looksLikeCsv(raw)) CsvProgramImporter.import(name, raw)
               else TextProgramImporter.import(name, raw)

    // Resolve every exercise name (attaches status + confidence; never mutates the original).
    val resolved = base.copy(
      weeks = base.weeks.map { w ->
        w.copy(sessions = w.sessions.map { s ->
          s.copy(slots = s.slots.map { slot ->
            val ref = ExerciseResolver.resolve(slot.exercise.original, aliasLookup)
            slot.copy(exercise = ref, confidence = minOf(slot.confidence, resolutionConfidence(ref, slot.confidence)))
          })
        })
      }
    )

    val allSlots = resolved.weeks.flatMap { it.sessions.flatMap { s -> s.slots } }
    val unit = detectUnit(allSlots)
    val flags = buildFlags(resolved, allSlots, unit)
    val withMeta = resolved.copy(canonicalUnit = unit, flags = flags)

    val program = CimCompiler.compile(withMeta)
    val overall = if (allSlots.isEmpty()) 0.0 else allSlots.map { it.confidence }.average()

    return ImportResult(withMeta, program, flags, overall, allSlots.size)
  }

  private fun resolutionConfidence(ref: ExerciseRef, slotConf: Double): Double = when (ref.status) {
    ResolutionStatus.ALIAS, ResolutionStatus.EXACT -> slotConf
    ResolutionStatus.INFERRED -> minOf(slotConf, 0.55)
    ResolutionStatus.UNRESOLVED -> minOf(slotConf, 0.3)
  }

  /** kg vs lb from explicit labels only — mixed or absent → null (ask, never assume). */
  private fun detectUnit(slots: List<SlotDraft>): WeightUnit? {
    val units = slots.mapNotNull { it.sets.firstOrNull()?.load?.takeIf { l -> l.kind == LoadKind.ABSOLUTE }?.unit }.distinct()
    return units.singleOrNull()
  }

  private fun buildFlags(draft: ProgramImportDraft, slots: List<SlotDraft>, unit: WeightUnit?): List<ImportFlag> {
    val flags = mutableListOf<ImportFlag>()
    if (slots.isEmpty()) {
      flags += ImportFlag(FlagSeverity.BLOCKER, "No exercises were found — this file may not be a training program.")
      return flags
    }
    slots.filter { it.exercise.status == ResolutionStatus.UNRESOLVED || it.exercise.status == ResolutionStatus.INFERRED }
      .forEach { flags += ImportFlag(FlagSeverity.REVIEW, "Couldn’t confidently match “${it.exercise.original}” — confirm the exercise.", it.exercise.original) }
    slots.filter { it.sets.firstOrNull()?.kind == SetKind.UNKNOWN }
      .forEach { flags += ImportFlag(FlagSeverity.REVIEW, "No sets/reps recognised for “${it.exercise.original}” — kept verbatim.", it.exercise.original) }
    val hasAbsolute = slots.any { it.sets.firstOrNull()?.load?.kind == LoadKind.ABSOLUTE }
    if (hasAbsolute && unit == null)
      flags += ImportFlag(FlagSeverity.REVIEW, "Weights were found but kg/lb is ambiguous — confirm units before committing.")
    if (draft.structure == ProgramStructure.SINGLE_WEEK_REPEATED && draft.repeatWeeks <= 1)
      flags += ImportFlag(FlagSeverity.INFO, "Only one week was detected. Set how many weeks it runs in review.")
    return flags
  }
}

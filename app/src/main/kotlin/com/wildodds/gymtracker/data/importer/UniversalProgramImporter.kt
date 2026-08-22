package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.parser.ParsedProgram

/**
 * The façade for Universal Program Import. Given pasted text or CSV/TSV it:
 *   detect format → deterministic importer → resolve exercise names → flag ambiguity → compile to
 *   [ParsedProgram] (which commits via `GymRepository.importProgram`).
 *
 * Everything here is deterministic and offline — no LLM, no network, in any locale.
 *
 * Honesty over polish: ambiguity is returned as [ImportFlag]s for the review step, never silently
 * resolved. Nothing is fabricated — unstated fields stay null and the verbatim source line is
 * preserved on every slot regardless of confidence.
 */
object UniversalProgramImporter {

  data class ImportResult(
    val draft: ProgramImportDraft,     // the rich model, for the review UI
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
    return fromDraft(base, aliasLookup)
  }

  /**
   * Resolve → flag → compile, starting from an already-structured draft. Split out so every ingest
   * path (text, CSV, spreadsheet, app-export adapter) shares one resolution and flagging step
   * instead of each growing its own.
   */
  fun fromDraft(
    base: ProgramImportDraft,
    aliasLookup: (String) -> String? = { null }
  ): ImportResult {
    // Resolve every exercise name (attaches status + confidence; never mutates the original).
    val resolved = base.copy(
      weeks = base.weeks.map { w ->
        w.copy(sessions = w.sessions.map { s ->
          s.copy(slots = s.slots.map { slot ->
            val ref = ExerciseResolver.resolve(slot.exercise.original, aliasLookup)
            val breakdown = slot.breakdown.copy(
              resolution = resolutionWeight(ref),
              reasons = slot.breakdown.reasons + resolutionReason(ref)
            )
            slot.copy(
              exercise = ref,
              breakdown = breakdown,
              confidence = minOf(slot.confidence, breakdown.score)
            )
          })
        })
      }
    )

    val allSlots = resolved.weeks.flatMap { w -> w.sessions.flatMap { s -> s.slots } }
    val unit = detectUnit(allSlots)
    val compiled = DraftCompiler.compile(resolved.copy(canonicalUnit = unit))
    val flags = buildFlags(resolved, allSlots, unit) + compiled.flags
    val withMeta = resolved.copy(canonicalUnit = unit, flags = flags)

    val overall = if (allSlots.isEmpty()) 0.0 else allSlots.map { it.confidence }.average()
    return ImportResult(withMeta, compiled.program, flags, overall, allSlots.size)
  }

  /** The resolution component of the confidence breakdown — a multiplier, not a cap. */
  private fun resolutionWeight(ref: ExerciseRef): Double = when (ref.status) {
    ResolutionStatus.EXACT -> 1.0
    ResolutionStatus.ALIAS -> 1.0
    ResolutionStatus.INFERRED -> 0.55
    ResolutionStatus.UNRESOLVED -> 0.30
  }

  private fun resolutionReason(ref: ExerciseRef): List<String> = when (ref.status) {
    ResolutionStatus.EXACT, ResolutionStatus.ALIAS -> emptyList()
    ResolutionStatus.INFERRED -> listOf("exercise inferred")
    ResolutionStatus.UNRESOLVED -> listOf("exercise not recognised")
  }

  /** kg vs lb from explicit labels only — mixed or absent → null (ask, never assume). */
  private fun detectUnit(slots: List<SlotDraft>): WeightUnit? {
    val units = slots.mapNotNull { it.sets.firstOrNull()?.load?.takeIf { l -> l.kind == LoadKind.ABSOLUTE }?.unit }.distinct()
    return units.singleOrNull()
  }

  private fun buildFlags(draft: ProgramImportDraft, slots: List<SlotDraft>, unit: WeightUnit?): List<ImportFlag> {
    val flags = mutableListOf<ImportFlag>()
    val hasRestDays = draft.weeks.any { w -> w.sessions.any { it.type != SessionType.TRAINING } }
    if (slots.isEmpty() && !hasRestDays) {
      flags += ImportFlag(FlagSeverity.BLOCKER, "No exercises were found — this file may not be a training program.")
      return flags
    }
    slots.filter { it.exercise.status == ResolutionStatus.UNRESOLVED || it.exercise.status == ResolutionStatus.INFERRED }
      .forEach {
        flags += ImportFlag(FlagSeverity.REVIEW,
          "Couldn’t confidently match “${it.exercise.original}” — confirm the exercise.",
          it.exercise.original, it.rawText)
      }
    slots.filter { it.sets.firstOrNull()?.kind == SetKind.UNKNOWN }
      .forEach {
        flags += ImportFlag(FlagSeverity.REVIEW,
          "No sets/reps recognised for “${it.exercise.original}” — kept verbatim.",
          it.exercise.original, it.rawText)
      }
    // A set count the source never stated is committed as 1 so the exercise renders at all; say so,
    // with the line, rather than letting an invented number pass as read.
    slots.filter { s ->
      val p = s.sets.firstOrNull()
      p != null && p.kind != SetKind.UNKNOWN && p.count == null && p.perSet.isEmpty()
    }.forEach {
      flags += ImportFlag(FlagSeverity.REVIEW,
        "The source didn’t say how many sets of “${it.exercise.original}” — confirm the count.",
        it.exercise.original, it.rawText)
    }
    val hasAbsolute = slots.any { it.sets.firstOrNull()?.load?.kind == LoadKind.ABSOLUTE }
    if (hasAbsolute && unit == null)
      flags += ImportFlag(FlagSeverity.REVIEW, "Weights were found but kg/lb is ambiguous — confirm units before committing.")
    if (draft.structure == ProgramStructure.SINGLE_WEEK_REPEATED && draft.repeatWeeks <= 1)
      flags += ImportFlag(FlagSeverity.INFO, "Only one week was detected. Set how many weeks it runs in review.")
    return flags
  }
}

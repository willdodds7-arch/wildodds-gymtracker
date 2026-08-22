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

  /** Below this many slots, a document is too short to judge by ratio. */
  private const val MIN_SLOTS_FOR_PROGRAM_CHECK = 3

  /** At least this fraction of lines must prescribe or name something known. */
  private const val MIN_RECOGNISED_FRACTION = 0.25

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
    // The incoming draft's own flags come FIRST and are kept: an importer that already knows
    // something about this document - that it was read as German, say - has said so on the draft,
    // and dropping that here made the knowledge unreachable.
    val flags = (base.flags + buildFlags(resolved, allSlots, unit) + compiled.flags).distinct()
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

    // A document can produce slots and still not be a program. A recipe yields "1 Whole Chicken",
    // "2 Lemons", "200ml White Wine" — lines that look structurally like exercises and are not.
    //
    // The test is evidence-based rather than a keyword blocklist: a training program either
    // PRESCRIBES something (a recognised sets/reps/time/distance token) or NAMES something the
    // exercise library knows. A document where almost nothing does either is not a program,
    // whatever its lines look like.
    //
    // The three-slot floor matters: a one-line paste of an exercise nobody has heard of is a
    // legitimate thing to import, and blocking it would be worse than importing it unresolved.
    val recognised = slots.count {
      it.sets.firstOrNull()?.kind != SetKind.UNKNOWN ||
        it.exercise.status == ResolutionStatus.EXACT || it.exercise.status == ResolutionStatus.ALIAS
    }
    val nothingRecognised = recognised == 0
    val mostlyUnrecognised = slots.size >= MIN_SLOTS_FOR_PROGRAM_CHECK &&
      recognised < slots.size * MIN_RECOGNISED_FRACTION
    // A document that declared its own structure — a Day 1, a week header, a spreadsheet header
    // row, a known export schema — gets the benefit of the doubt even when nothing in it resolves,
    // because a one-line paste of a movement nobody has heard of is a legitimate import. A document
    // with neither structure nor a single recognisable line is a shopping list.
    if (mostlyUnrecognised || (nothingRecognised && !draft.hasExplicitStructure)) {
      flags += ImportFlag(FlagSeverity.BLOCKER,
        "Only $recognised of ${slots.size} lines look like exercises — this file may not be a " +
        "training program.",
        sourceLine = slots.take(3).joinToString(" / ") { it.rawText.take(40) })
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
      flags += ImportFlag(FlagSeverity.REVIEW,
        "Weights were found but kg/lb is ambiguous — confirm units before committing.")

    // ── an exercise matched below the trust threshold ─────────────────────────
    slots.filter {
      it.exercise.status == ResolutionStatus.INFERRED && it.exercise.candidates.isNotEmpty()
    }.forEach { s ->
      val best = s.exercise.candidates.first()
      flags += ImportFlag(FlagSeverity.REVIEW,
        "“${s.exercise.original}” looks like “${best.name}” but wasn't certain — confirm it.",
        s.exercise.original, s.rawText)
    }

    // ── a line the parser only half-read ──────────────────────────────────────
    // The single best signal that something was misunderstood: a large fraction of the source
    // characters that no recognised token consumed.
    slots.filter { it.breakdown.notationCoverage < 0.7 && it.sets.firstOrNull()?.kind != SetKind.UNKNOWN }
      .forEach {
        flags += ImportFlag(FlagSeverity.REVIEW,
          "Part of “${it.rawText.take(60)}” wasn't understood — check it read correctly.",
          it.exercise.original, it.rawText)
      }

    // ── duplicate sessions ────────────────────────────────────────────────────
    // Two days with the same name AND the same exercises inside one week is almost always a
    // segmentation mistake — a day header read twice, or a block pasted in twice.
    for (w in draft.weeks) {
      val seen = HashMap<String, SessionDraft>()
      for (s in w.sessions) {
        val key = s.label.lowercase(java.util.Locale.ROOT) + "|" +
          s.slots.joinToString(",") { it.exercise.original.lowercase(java.util.Locale.ROOT) }
        val prev = seen.put(key, s)
        if (prev != null && s.slots.isNotEmpty()) {
          flags += ImportFlag(FlagSeverity.REVIEW,
            "Week ${w.index} has two identical “${s.label}” days — one may be a duplicate.",
            "week${w.index}.${s.dayNumber}")
        }
      }
    }

    // ── week count inferred ───────────────────────────────────────────────────
    if (draft.structure == ProgramStructure.SINGLE_WEEK_REPEATED && draft.repeatWeeks <= 1)
      flags += ImportFlag(FlagSeverity.INFO,
        "Only one week was detected. Set how many weeks it runs in review.")
    else if (draft.structure == ProgramStructure.SINGLE_WEEK_REPEATED)
      flags += ImportFlag(FlagSeverity.INFO,
        "One week was written out and repeated ${draft.repeatWeeks} times — the source said how " +
        "long the block runs but not what changes week to week.")

    // ── rest days ─────────────────────────────────────────────────────────────
    val restDays = draft.weeks.flatMap { it.sessions }.filter { it.type != SessionType.TRAINING }
    if (restDays.isNotEmpty())
      flags += ImportFlag(FlagSeverity.INFO,
        "${restDays.size} rest day${if (restDays.size == 1) "" else "s"} were read from the source " +
        "and kept in place: ${restDays.joinToString(", ") { it.label }}.")

    // ── a per-set list longer than the stated count ───────────────────────────
    slots.forEach { s ->
      val p = s.sets.firstOrNull() ?: return@forEach
      if (p.perSet.size > 1 && p.count != null && p.count != p.perSet.size) {
        flags += ImportFlag(FlagSeverity.REVIEW,
          "“${s.exercise.original}” says ${p.count} sets but lists ${p.perSet.size} — check which is right.",
          s.exercise.original, s.rawText)
      }
    }

    // ── a progression rule the model cannot hold ──────────────────────────────
    draft.unresolvedProgression.forEach {
      flags += ImportFlag(FlagSeverity.REVIEW,
        "“$it” is a progression rule, which a program definition can't carry — it's kept in the notes.",
        sourceLine = it)
    }

    return flags
  }
}

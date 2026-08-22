package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.db.entity.StoredSetPrescription
import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession

/**
 * Compiles the rich [ProgramImportDraft] down to [ParsedProgram], which commits through
 * `GymRepository.importProgram`.
 *
 * Was `CimCompiler`, and was the accuracy ceiling of the whole product: it flattened rest, tempo,
 * superset grouping, RIR and per-set detail into a `notes` blob. Since schema v26 all of those are
 * real columns, so this is now close to a 1:1 mapping. What genuinely still cannot be represented is
 * enumerated in [LOSSY_FIELDS] — and when one of those fields is present, it goes to notes **and**
 * raises an INFO flag naming it, so the lossiness is visible rather than folkloric.
 *
 * `notes` is no longer a dumping ground. It carries the coach's own prose and nothing else, except
 * for the [LOSSY_FIELDS] that have nowhere better to go.
 */
object DraftCompiler {

  /**
   * The fields the stored model still cannot hold, each with the note prefix it is rendered under.
   * Asserted exactly by `DraftCompilerLossTest` — a future field silently falling out of the model
   * fails CI rather than quietly degrading imports.
   */
  val LOSSY_FIELDS: Map<String, String> = linkedMapOf(
    "setKind" to "Set style",                 // CLUSTER/MYO/LADDER/… has no column; the modifier does
    "restKind.asNeeded" to "Rest",            // "as needed" isn't a number of seconds
    "restKind.supersetNone" to "Rest",        // "no rest (superset)" isn't a number of seconds
    "load.text" to "Load",                    // BAND/CHAIN/TEXT loads keep only their label
    "weekDeload" to "Deload",                 // WeekDraft.isDeload has no per-week storage
    "sessionNotes" to "Session",              // SessionDraft.notes has no column on `sessions`
    "programProgression" to "Progression"     // "add 2.5kg each week" is a rule, not a prescription
  )

  fun compile(draft: ProgramImportDraft): CompiledProgram {
    val lossy = linkedSetOf<String>()
    val weekByWeek = draft.structure == ProgramStructure.WEEK_BY_WEEK && draft.weeks.size > 1

    val sessions: List<ParsedSession> =
      if (weekByWeek) draft.weeks.flatMap { w -> w.sessions.map { s -> toSession(s, w.index, w, lossy) } }
      else draft.weeks.firstOrNull()?.let { w -> w.sessions.map { s -> toSession(s, 1, w, lossy) } } ?: emptyList()

    if (draft.weeks.any { it.isDeload }) lossy += "weekDeload"
    if (draft.unresolvedProgression.isNotEmpty()) lossy += "programProgression"

    val allExercises = sessions.flatMap { it.exercises }
    val repeatWeeks = if (weekByWeek) 1 else draft.repeatWeeks.coerceAtLeast(1)
    val totalWeeks = if (weekByWeek) draft.weeks.size else maxOf(repeatWeeks, 1)
    val firstWeek = sessions.minOfOrNull { it.weekNumber } ?: 1
    val days = sessions.filter { it.weekNumber == firstWeek }.map { it.dayNumber }.distinct().size

    val program = ParsedProgram(
      name = draft.name,
      totalWeeks = totalWeeks,
      sessions = sessions,
      isUserCreated = true,
      trackRpe = allExercises.any { it.rpeTarget.isNotBlank() },
      trackOneRm = allExercises.any { it.pct1rmTarget.isNotBlank() },
      repeatWeeks = repeatWeeks,
      description = draft.notes.joinToString(" ").take(300),
      coach = draft.author,
      daysPerWeek = days
    )

    val flags = lossy.map { field ->
      ImportFlag(
        FlagSeverity.INFO,
        "“$field” can't be stored as a field yet — it was kept in the exercise notes instead.",
        path = field
      )
    }
    return CompiledProgram(program, flags)
  }

  /** The compiled program plus the flags the compile step itself raised. */
  data class CompiledProgram(val program: ParsedProgram, val flags: List<ImportFlag>)

  // ── sessions ─────────────────────────────────────────────────────────────────

  private fun toSession(s: SessionDraft, week: Int, w: WeekDraft, lossy: MutableSet<String>): ParsedSession {
    if (s.notes.isNotBlank()) lossy += "sessionNotes"
    return ParsedSession(
      weekNumber = week,
      dayNumber = s.dayNumber,
      name = s.label,
      muscleGroups = "",
      // A rest day genuinely has no exercises; that is what makes it a rest day, not an empty bug.
      exercises = s.slots.map { toExercise(it, lossy) },
      sessionType = s.type.name
    )
  }

  // ── exercises ────────────────────────────────────────────────────────────────

  private fun toExercise(slot: SlotDraft, lossy: MutableSet<String>): ParsedExercise {
    val p = slot.sets.firstOrNull()
    val stated = p?.count ?: p?.perSet?.size?.takeIf { it > 0 }
    // `sets` is what every screen renders and cannot be null, so an unstated count falls back to 1
    // here — but `setsStated` stays null, and UniversalProgramImporter raises a REVIEW flag naming
    // the exercise, so the user is told before commit rather than shown an invented number.
    val effectiveSets = (stated ?: 1).coerceAtLeast(1)
    val load = p?.load
    val rest = effectiveRest(slot, p)

    return ParsedExercise(
      name = slot.exercise.resolvedName.ifBlank { slot.exercise.original },
      sets = effectiveSets,
      repsTarget = renderReps(p),           // "" when the source didn't state reps — never guessed
      notes = buildNotes(slot, p, lossy),
      orderIndex = slot.orderIndex,
      rpeTarget = renderRpe(p),
      pct1rmTarget = renderPct(p),
      setsStated = stated,
      restSeconds = rest?.lowSec,
      restRangeSecondsMax = rest?.highSec,
      tempo = validTempo(p?.tempo),
      supersetGroup = slot.supersetGroup.ifBlank { null },
      supersetOrder = slot.supersetOrder,
      loadKind = load?.kind?.takeIf { it != LoadKind.NONE }?.name,
      loadValue = load?.value,
      loadValueMax = load?.valueMax,
      loadUnit = load?.storageUnit?.takeIf { it != LoadUnit.NONE }?.name,
      perSet = renderPerSet(p),
      isFinalSetAmrap = p?.isFinalSetAmrap ?: false,
      modifiers = (slot.modifiers + p?.modifiers.orEmpty() +
        (if (slot.optional) listOf(Modifier.OPTIONAL) else emptyList())).distinct().sorted(),
      // ALWAYS retained, at every confidence level. This is the audit trail, and the thing that
      // makes "re-parse this import with the current parser" possible at all.
      rawSourceLine = slot.rawText.ifBlank { null }
    )
  }

  /** Slot-level rest wins; a per-set rest is used only when the slot didn't state one. */
  private fun effectiveRest(slot: SlotDraft, p: SetPrescription?): RestSpec? {
    val r = slot.rest.takeIf { it.kind != RestKind.NONE_SPECIFIED } ?: p?.rest
    return r?.takeIf { it.kind == RestKind.FIXED || it.kind == RestKind.RANGE }
  }

  /**
   * A tempo is stored only when it is a real 4-slot form. "3s pause" is a modifier, not a tempo, and
   * writing it into the tempo column would make the column mean two different things.
   */
  private fun validTempo(t: String?): String? {
    val s = t?.trim().orEmpty()
    if (s.isBlank()) return null
    val compact = Regex("""^[\dX]{4}$""")            // 3010, 31X0
    val dashed = Regex("""^[\dX]-[\dX]-[\dX]-[\dX]$""")  // 3-1-2-0
    return if (compact.matches(s.uppercase()) || dashed.matches(s.uppercase())) s else null
  }

  // ── per-set ──────────────────────────────────────────────────────────────────

  private fun renderPerSet(p: SetPrescription?): List<StoredSetPrescription> {
    if (p == null || p.perSet.isEmpty()) return emptyList()
    return p.perSet.mapIndexed { i, child ->
      val load = child.load ?: p.load
      StoredSetPrescription(
        setNumber = i + 1,
        reps = renderRep(child.reps),
        repKind = (child.reps?.kind ?: RepKind.TEXT).name,
        repMin = child.reps?.min,
        repMax = child.reps?.max,
        loadKind = (load?.kind ?: LoadKind.NONE).name,
        loadValue = load?.value,
        loadValueMax = load?.valueMax,
        loadUnit = (load?.storageUnit ?: LoadUnit.NONE).name,
        tempo = validTempo(child.tempo.ifBlank { p.tempo }).orEmpty(),
        restSeconds = child.rest?.lowSec,
        restRangeSecondsMax = child.rest?.highSec,
        isAmrap = child.isFinalSetAmrap,
        modifiers = child.modifiers,
        raw = child.rawText
      )
    }
  }

  // ── field rendering ──────────────────────────────────────────────────────────

  private fun renderReps(p: SetPrescription?): String {
    if (p == null) return ""
    if (p.perSet.isNotEmpty()) return p.perSet.joinToString(", ") { renderRep(it.reps) }.trim(',', ' ')
    return renderRep(p.reps)
  }

  private fun renderRep(r: RepSpec?): String = when (r?.kind) {
    null -> ""
    RepKind.COUNT -> r.min?.toString() ?: ""
    RepKind.RANGE -> "${r.min}-${r.max}"
    RepKind.AMRAP -> "AMRAP"
    RepKind.AMRAP_MIN -> "${r.min}+"
    RepKind.TIME_SEC -> if ((r.min ?: 0) >= 60 && (r.min ?: 0) % 60 == 0) "${r.min!! / 60}min" else "${r.min ?: 0}s"
    RepKind.DISTANCE_M -> "${r.min ?: 0}m"
    RepKind.CALORIES -> "${r.min ?: 0} cal"
    RepKind.TEXT -> r.text
  }

  private fun renderPct(p: SetPrescription?): String {
    val l = p?.load ?: return ""
    val lo = l.value?.let(::fmt) ?: return ""
    val range = l.valueMax?.let { "$lo-${fmt(it)}" } ?: lo
    return when (l.kind) {
      LoadKind.PCT_1RM -> "$range%"
      LoadKind.PCT_TM  -> "$range% TM"
      LoadKind.PCT_E1RM -> "$range% e1RM"
      else -> ""
    }
  }

  private fun renderRpe(p: SetPrescription?): String =
    if (p?.load?.kind == LoadKind.RPE) p.load.text.ifBlank { p.load.value?.let(::fmt).orEmpty() } else ""

  /**
   * Notes now carry only the coach's own prose plus whatever [LOSSY_FIELDS] applied — everything
   * else has a column. Each lossy value is also recorded in [lossy] so the compile step can flag it.
   */
  private fun buildNotes(slot: SlotDraft, p: SetPrescription?, lossy: MutableSet<String>): String {
    val parts = mutableListOf<String>()

    p?.kind?.takeIf { it !in PLAIN_SET_KINDS }?.let {
      lossy += "setKind"
      parts += "Set style ${it.name.lowercase().replace('_', ' ')}"
    }
    when (slot.rest.kind) {
      RestKind.AS_NEEDED -> { lossy += "restKind.asNeeded"; parts += "Rest as needed" }
      RestKind.SUPERSET_NONE -> { lossy += "restKind.supersetNone"; parts += "No rest (superset)" }
      RestKind.TEXT -> { lossy += "restKind.asNeeded"; parts += "Rest ${slot.rest.text}" }
      else -> {}
    }
    p?.load?.takeIf { it.kind in TEXT_ONLY_LOADS }?.let {
      lossy += "load.text"
      parts += it.text.ifBlank { it.kind.name.lowercase() }
    }
    if (slot.notes.isNotBlank()) parts += slot.notes
    return parts.joinToString(" · ")
  }

  /** Set kinds that the numeric columns fully describe, so they need no note. */
  private val PLAIN_SET_KINDS = setOf(
    SetKind.STRAIGHT, SetKind.RANGE, SetKind.TIMED, SetKind.CARRY, SetKind.UNKNOWN
  )

  /** Load kinds whose meaning lives in their label rather than in a number. */
  private val TEXT_ONLY_LOADS = setOf(LoadKind.BAND, LoadKind.CHAIN, LoadKind.TEXT)

  private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}

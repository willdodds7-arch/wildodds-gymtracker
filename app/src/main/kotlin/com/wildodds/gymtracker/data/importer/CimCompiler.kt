package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession

/**
 * Compiles the rich [ProgramImportDraft] down to the app's existing [ParsedProgram], which commits
 * through the battle-tested `GymRepository.importProgram`. The runtime model is flat (one `sets:Int`
 * + `repsTarget:String` per exercise), so structure the flat fields can't hold — rest, tempo,
 * superset, modifiers, RIR, per-set detail, and the verbatim line for anything low-confidence — is
 * folded into the exercise `notes` so it is shown to the user and never lost.
 */
object CimCompiler {

  fun compile(draft: ProgramImportDraft): ParsedProgram {
    val weekByWeek = draft.structure == ProgramStructure.WEEK_BY_WEEK && draft.weeks.size > 1

    val sessions: List<ParsedSession> =
      if (weekByWeek) draft.weeks.flatMap { w -> w.sessions.map { s -> toSession(s, w.index) } }
      else draft.weeks.firstOrNull()?.sessions?.map { s -> toSession(s, 1) } ?: emptyList()

    val allExercises = sessions.flatMap { it.exercises }
    val repeatWeeks = if (weekByWeek) 1 else draft.repeatWeeks.coerceAtLeast(1)
    val totalWeeks = if (weekByWeek) draft.weeks.size else maxOf(repeatWeeks, 1)
    val days = sessions.filter { it.weekNumber == (sessions.minOfOrNull { s -> s.weekNumber } ?: 1) }
      .map { it.dayNumber }.distinct().size

    return ParsedProgram(
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
  }

  private fun toSession(s: SessionDraft, week: Int) = ParsedSession(
    weekNumber = week, dayNumber = s.dayNumber, name = s.label,
    muscleGroups = "", exercises = s.slots.map(::toExercise)
  )

  private fun toExercise(slot: SlotDraft): ParsedExercise {
    val p = slot.sets.firstOrNull()
    val sets = (p?.count ?: p?.perSet?.size?.takeIf { it > 0 } ?: 1).coerceAtLeast(1)
    val reps = renderReps(p)
    return ParsedExercise(
      name = slot.exercise.resolvedName.ifBlank { slot.exercise.original },
      sets = sets,
      repsTarget = reps,                    // "" when the source didn't state reps — never guessed
      notes = buildNotes(slot, p),
      orderIndex = slot.orderIndex,
      rpeTarget = renderRpe(p),
      pct1rmTarget = renderPct(p)
    )
  }

  // ── field rendering ───────────────────────────────────────────────────────────
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
    RepKind.TEXT -> r.text
  }

  private fun renderPct(p: SetPrescription?): String {
    val l = p?.load ?: return ""
    return when (l.kind) {
      LoadKind.PCT_1RM -> l.value?.let { fmt(it) + "%" } ?: ""
      LoadKind.PCT_TM  -> l.value?.let { fmt(it) + "% TM" } ?: ""
      else -> ""
    }
  }

  private fun renderRpe(p: SetPrescription?): String =
    if (p?.load?.kind == LoadKind.RPE) p.load.text.ifBlank { p.load.value?.let(::fmt).orEmpty() } else ""

  private fun buildNotes(slot: SlotDraft, p: SetPrescription?): String {
    val parts = mutableListOf<String>()
    if (slot.supersetGroup.isNotBlank()) parts += "Superset ${slot.supersetGroup}"
    if (slot.optional) parts += "Optional"
    if (slot.modifiers.isNotBlank()) parts += slot.modifiers
    p?.tempo?.takeIf { it.isNotBlank() }?.let { parts += "Tempo $it" }
    when (slot.rest.kind) {
      RestKind.FIXED -> parts += "Rest ${slot.rest.lowSec}s"
      RestKind.RANGE -> parts += "Rest ${slot.rest.lowSec}-${slot.rest.highSec}s"
      RestKind.AS_NEEDED -> parts += "Rest as needed"
      RestKind.SUPERSET_NONE -> parts += "No rest (superset)"
      else -> {}
    }
    p?.load?.takeIf { it.kind == LoadKind.RIR }?.let { parts += "RIR ${it.text}" }
    p?.load?.takeIf { it.kind == LoadKind.ABSOLUTE }?.let { parts += "@${it.text}" }
    p?.load?.takeIf { it.kind == LoadKind.BODYWEIGHT }?.let { parts += it.text }
    if (slot.notes.isNotBlank()) parts += slot.notes
    // Preserve the verbatim source line whenever structure was uncertain, so nothing is trusted blindly.
    if (slot.confidence < 0.9 || p?.kind == SetKind.UNKNOWN) parts += "“${slot.rawText}”"
    return parts.joinToString(" · ")
  }

  private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}

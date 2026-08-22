package com.wildodds.gymtracker.data.parser

data class ParsedExercise(
  val name: String,
  val sets: Int,
  val repsTarget: String,
  val notes: String,
  val orderIndex: Int,
  val rpeTarget: String = "",  // Prescribed RPE from program (e.g. "8", "7-8")
  val pct1rmTarget: String = "",  // Prescribed %1RM from program (e.g. "75%", "70-80%")
  val cap3Tag: String = "",  // nSuns CAP3 main lifts only — see Exercise.cap3Tag
  // ── Reader v2 prescription detail (v26) ─────────────────────────────────────
  // What the SOURCE said, carried straight through to the matching Exercise columns. Every one of
  // these is null/empty when the source didn't say — see Exercise for the field-by-field notes.
  // Defaulted throughout so every existing construction site (catalogue, builder, CAP3, templates)
  // is unaffected and simply supplies nothing.
  val setsStated: Int? = null,
  val restSeconds: Int? = null,
  val restRangeSecondsMax: Int? = null,
  val tempo: String? = null,
  val supersetGroup: String? = null,
  val supersetOrder: Int? = null,
  val loadKind: String? = null,
  val loadValue: Double? = null,
  val loadValueMax: Double? = null,
  val loadUnit: String? = null,
  val perSet: List<com.wildodds.gymtracker.data.db.entity.StoredSetPrescription> = emptyList(),
  val isFinalSetAmrap: Boolean = false,
  val modifiers: List<String> = emptyList(),
  val rawSourceLine: String? = null
)

data class ParsedSession(
  val weekNumber: Int,
  val dayNumber: Int,
  val name: String,
  val muscleGroups: String,
  val exercises: List<ParsedExercise>,
  val phaseNumber: Int = 1,
  /**
   * Name of a [com.wildodds.gymtracker.data.importer.SessionType]. Defaults to TRAINING so every
   * existing producer is unchanged; the importer sets REST / ACTIVE_RECOVERY / DELOAD_NOTE when the
   * source says so, and a REST session legitimately carries zero exercises.
   */
  val sessionType: String = "TRAINING"
)

/** One block of a multi-phase program. weekNumber on sessions stays global (continuous). */
data class ParsedPhase(
  val phaseNumber: Int,
  val name: String,
  val durationWeeks: Int,
  val focus: String = ""
)

data class ParsedProgram(
  val name: String,
  val totalWeeks: Int,
  val sessions: List<ParsedSession>,
  // "1" - "5" for built-in gradients, content URI string, or null for default
  val coverImage: String? = null,
  val isFlexible: Boolean = false,
  val category: String = "",
  val isUserCreated: Boolean = false,
  val trackRpe: Boolean = false,
  val trackOneRm: Boolean = false,
  // Empty (or single entry) = a normal single-block program (interface unchanged).
  val phases: List<ParsedPhase> = emptyList(),
  // Catalogue "blocks" ship ONE representative week in [sessions]; this asks the importer to
  // materialise that week across N weeks (default block length). 1 = use sessions as-is.
  val repeatWeeks: Int = 1,
  // ── Browse metadata (v18) ──────────────────────────────────────────────────
  val description: String = "",
  val coach: String = "",
  val coachBio: String = "",
  val daysPerWeek: Int = 0,
  val split: String = "",
  val style: String = "",
  // Offer a main-lift picker when starting (the Russian routine run on bench/OHP/deadlift).
  val liftSwappable: Boolean = false
)

/**
 * Run a lift-swappable program (e.g. the Russian Squat Routine) on a different main lift: the
 * dominant %1RM-prescribed exercise is renamed to [target]'s canonical movement everywhere, and the
 * program title gains the lift, so 1RM prefill + the missing-1RM prompt follow the chosen lift
 * automatically (both key off the exercise NAME). No-op if the program already runs on [target].
 */
fun ParsedProgram.swappedToLift(target: com.wildodds.gymtracker.data.profile.MainLift): ParsedProgram {
  val primaryName = sessions.asSequence()
    .flatMap { it.exercises.asSequence() }
    .filter { it.pct1rmTarget.isNotBlank() }
    .groupingBy { it.name }.eachCount()
    .maxByOrNull { it.value }?.key ?: return this
  if (com.wildodds.gymtracker.data.profile.MainLift.forExercise(primaryName) == target) return this

  val targetName = when (target) {
    com.wildodds.gymtracker.data.profile.MainLift.SQUAT -> "Back Squat"
    com.wildodds.gymtracker.data.profile.MainLift.BENCH -> "Bench Press"
    com.wildodds.gymtracker.data.profile.MainLift.DEADLIFT -> "Deadlift"
    com.wildodds.gymtracker.data.profile.MainLift.OHP -> "Overhead Press"
  }
  return copy(
    name = "$name — ${target.label}",
    sessions = sessions.map { s ->
      s.copy(exercises = s.exercises.map { e -> if (e.name == primaryName) e.copy(name = targetName) else e })
    }
  )
}

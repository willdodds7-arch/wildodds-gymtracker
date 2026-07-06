package com.wildodds.gymtracker.data.parser

data class ParsedExercise(
  val name: String,
  val sets: Int,
  val repsTarget: String,
  val notes: String,
  val orderIndex: Int,
  val rpeTarget: String = "",  // Prescribed RPE from program (e.g. "8", "7-8")
  val pct1rmTarget: String = ""  // Prescribed %1RM from program (e.g. "75%", "70-80%")
)

data class ParsedSession(
  val weekNumber: Int,
  val dayNumber: Int,
  val name: String,
  val muscleGroups: String,
  val exercises: List<ParsedExercise>,
  val phaseNumber: Int = 1
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
  val style: String = ""
)

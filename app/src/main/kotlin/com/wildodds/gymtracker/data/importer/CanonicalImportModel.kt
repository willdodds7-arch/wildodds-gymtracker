package com.wildodds.gymtracker.data.importer

/**
 * Canonical Import Model (CIM) — the rich, lossless representation a program is parsed into before
 * it is reviewed and committed. It is deliberately separate from the runtime Room entities: the app
 * keeps executing its simple flat model ([com.wildodds.gymtracker.data.db.entity.Exercise] has one
 * `sets:Int` + `repsTarget:String`), while the CIM carries the structure and the verbatim source so
 * nothing is lost. [CimCompiler] compiles a [ProgramImportDraft] down to the app's existing
 * [com.wildodds.gymtracker.data.parser.ParsedProgram], which commits through the battle-tested
 * `GymRepository.importProgram` path.
 *
 * Design rule that runs through every field: **never fabricate.** A value the source didn't state is
 * null / empty / UNKNOWN — not a plausible-looking guess. Every level keeps a `rawText` so the
 * original is always recoverable even when structure wasn't.
 *
 * These are plain data classes (no serialization framework) so they stay trivial to construct in
 * tests and to hand to a future structured-output LLM step or a review UI.
 */

enum class WeightUnit { KG, LB }

enum class LoadKind {
  ABSOLUTE,   // a concrete weight (value + unit)
  PCT_1RM,    // % of one-rep max
  PCT_TM,     // % of training max
  RPE,        // rate of perceived exertion target
  RIR,        // reps in reserve target
  BODYWEIGHT, // bodyweight, optionally +value (unit)
  BAND, CHAIN,// accommodating resistance — label in text
  RELATIVE,   // "last week + 2.5kg" — value is the delta
  TEXT,       // couldn't be structured — verbatim in text
  NONE        // no load stated (NOT a guess of one)
}

/** A load prescription, kept flat (tag + optional fields) so it serialises and edits cleanly. */
data class LoadSpec(
  val kind: LoadKind = LoadKind.NONE,
  val value: Double? = null,          // weight / % / rpe / rir / delta, per kind
  val unit: WeightUnit? = null,       // ABSOLUTE / RELATIVE / BODYWEIGHT(+extra)
  val text: String = ""               // raw fallback or band/chain label
)

enum class RepKind { COUNT, RANGE, AMRAP, AMRAP_MIN, TIME_SEC, DISTANCE_M, TEXT }

/** Reps for a set: a count, a range, AMRAP (incl. the "5+" minimum form), time, or distance. */
data class RepSpec(
  val kind: RepKind = RepKind.TEXT,
  val min: Int? = null,               // COUNT / AMRAP_MIN / TIME_SEC / DISTANCE_M use this
  val max: Int? = null,               // RANGE upper bound
  val text: String = ""
)

enum class SetKind { STRAIGHT, RANGE, TOPSET, BACKOFF, DROP, MYO, RESTPAUSE, CLUSTER, EMOM, CARRY, TIMED, UNKNOWN }

/**
 * One prescription line for an exercise. [count] is the number of sets and is **null when the source
 * didn't say** (rendered conservatively at compile, never invented). [perSet] holds genuinely
 * different sets (e.g. 5/5/5/3/1 → five children) and, when present, wins over [count]/[reps].
 */
data class SetPrescription(
  val rawText: String,
  val kind: SetKind = SetKind.STRAIGHT,
  val count: Int? = null,
  val reps: RepSpec? = null,
  val load: LoadSpec? = null,
  val tempo: String = "",             // verbatim tempo, e.g. "31X0" or "3-1-2-0"
  val isFinalSetAmrap: Boolean = false,
  val perSet: List<SetPrescription> = emptyList()
)

enum class RestKind { NONE_SPECIFIED, FIXED, RANGE, AS_NEEDED, SUPERSET_NONE, TEXT }

data class RestSpec(
  val kind: RestKind = RestKind.NONE_SPECIFIED,
  val lowSec: Int? = null,
  val highSec: Int? = null,
  val text: String = ""
)

enum class ResolutionStatus { EXACT, ALIAS, INFERRED, UNRESOLVED }

/** A parsed exercise name and how confidently it maps to a known movement. [original] is forever. */
data class ExerciseRef(
  val original: String,
  val resolvedName: String,
  val status: ResolutionStatus = ResolutionStatus.UNRESOLVED,
  val confidence: Double = 0.0
)

/** One exercise slot in a session. */
data class SlotDraft(
  val rawText: String,
  val exercise: ExerciseRef,
  val sets: List<SetPrescription> = emptyList(),
  val rest: RestSpec = RestSpec(),
  val supersetGroup: String = "",     // "A", "B" … "" = none
  val modifiers: String = "",         // grip/stance/tempo/unilateral the flat library can't encode
  val optional: Boolean = false,
  val notes: String = "",
  val confidence: Double = 1.0,
  val orderIndex: Int = 0
)

data class SessionDraft(
  val label: String,
  val dayNumber: Int,
  val weekdayHint: String = "",
  val notes: String = "",
  val slots: List<SlotDraft>
)

data class WeekDraft(
  val index: Int,
  val isDeload: Boolean = false,
  val sessions: List<SessionDraft>
)

/** How the weeks relate: fully written out, one week repeated, a flat every-session list, rotation. */
enum class ProgramStructure { WEEK_BY_WEEK, SINGLE_WEEK_REPEATED, FLAT, ROTATION }

enum class FlagSeverity { INFO, REVIEW, BLOCKER }

/** A thing the user should look at before committing — surfaced, never silently resolved. */
data class ImportFlag(val severity: FlagSeverity, val message: String, val path: String = "")

/** The whole parsed program, pre-commit. */
data class ProgramImportDraft(
  val name: String,
  val author: String = "",
  val source: String = "",
  val canonicalUnit: WeightUnit? = null,   // null = ambiguous → the review step must ask, not assume
  val weeks: List<WeekDraft>,
  val structure: ProgramStructure = ProgramStructure.SINGLE_WEEK_REPEATED,
  val repeatWeeks: Int = 1,
  val notes: List<String> = emptyList(),
  val unresolvedProgression: List<String> = emptyList(),
  val flags: List<ImportFlag> = emptyList()
)

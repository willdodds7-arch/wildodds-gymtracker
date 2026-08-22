package com.wildodds.gymtracker.data.importer

/**
 * Canonical Import Model (CIM) — the rich, lossless representation a program is parsed into before
 * it is reviewed and committed. [DraftCompiler] compiles a [ProgramImportDraft] down to the app's
 * [com.wildodds.gymtracker.data.parser.ParsedProgram], which commits through the battle-tested
 * `GymRepository.importProgram` path.
 *
 * Since the v26 schema the compile step is very nearly 1:1 — rest, tempo, superset grouping and
 * order, load kind, per-set detail, modifiers and the verbatim source line are all first-class
 * stored columns. What still cannot be represented is enumerated in [DraftCompiler.LOSSY_FIELDS]
 * and raises an INFO flag naming the field, so the lossiness is visible rather than folkloric.
 *
 * Design rule that runs through every field: **never fabricate.** A value the source didn't state is
 * null / empty / UNKNOWN — not a plausible-looking guess. Every level keeps a `rawText` so the
 * original is always recoverable even when structure wasn't.
 *
 * These are plain data classes (no serialization framework) so they stay trivial to construct in
 * tests and to hand to a review UI.
 */

enum class WeightUnit { KG, LB }

/**
 * The unit a [LoadSpec.value] is expressed in, as stored. Separate from [WeightUnit] because a load
 * is not always a weight: RPE and RIR are points, percentages are percentages, and a band is a
 * colour. NONE means the load carries no numeric value at all.
 */
enum class LoadUnit { KG, LB, PERCENT, POINTS, NONE }

enum class LoadKind {
  ABSOLUTE,          // a concrete weight (value + unit)
  PCT_1RM,           // % of one-rep max
  PCT_TM,            // % of training max
  PCT_E1RM,          // % of estimated 1RM
  PCT_BODYWEIGHT,    // % of bodyweight
  PCT_OF_TOP,        // % "of that" — of the top set just performed
  RPE,               // rate of perceived exertion target
  RIR,               // reps in reserve target
  BODYWEIGHT,        // bodyweight, no added load
  BODYWEIGHT_PLUS,   // bodyweight + value (unit)
  BODYWEIGHT_MINUS,  // assisted: bodyweight − value (unit)
  BAND, CHAIN,       // accommodating resistance — label in text
  RELATIVE,          // "last week + 2.5kg" — value is the delta
  TEXT,              // couldn't be structured — verbatim in text
  NONE               // no load stated (NOT a guess of one)
}

/**
 * A load prescription, kept flat (tag + optional fields) so it serialises and edits cleanly.
 * [valueMax] is non-null only for a stated RANGE ("@ 60-70kg", "RPE 7-8", "70-80%").
 */
data class LoadSpec(
  val kind: LoadKind = LoadKind.NONE,
  val value: Double? = null,          // weight / % / rpe / rir / delta, per kind
  val valueMax: Double? = null,       // upper bound of a stated range; null = not a range
  val unit: WeightUnit? = null,       // ABSOLUTE / RELATIVE / BODYWEIGHT±
  val text: String = ""               // raw fallback or band/chain label
) {
  /** How [value] is measured, for storage. Derived, never guessed. */
  val storageUnit: LoadUnit
    get() = when (kind) {
      LoadKind.ABSOLUTE, LoadKind.RELATIVE, LoadKind.BODYWEIGHT_PLUS, LoadKind.BODYWEIGHT_MINUS ->
        when (unit) { WeightUnit.KG -> LoadUnit.KG; WeightUnit.LB -> LoadUnit.LB; null -> LoadUnit.NONE }
      LoadKind.PCT_1RM, LoadKind.PCT_TM, LoadKind.PCT_E1RM, LoadKind.PCT_BODYWEIGHT, LoadKind.PCT_OF_TOP ->
        LoadUnit.PERCENT
      LoadKind.RPE, LoadKind.RIR -> LoadUnit.POINTS
      else -> LoadUnit.NONE
    }
}

enum class RepKind { COUNT, RANGE, AMRAP, AMRAP_MIN, TIME_SEC, DISTANCE_M, CALORIES, TEXT }

/** Reps for a set: a count, a range, AMRAP (incl. the "5+" minimum form), time, or distance. */
data class RepSpec(
  val kind: RepKind = RepKind.TEXT,
  val min: Int? = null,               // COUNT / AMRAP_MIN / TIME_SEC / DISTANCE_M use this
  val max: Int? = null,               // RANGE upper bound
  val text: String = ""
)

enum class SetKind {
  STRAIGHT, RANGE, TOPSET, BACKOFF, DROP, MYO, RESTPAUSE, CLUSTER,
  EMOM, AMRAP_TIME, FOR_TIME, LADDER, PYRAMID, CIRCUIT, COMPLEX,
  CARRY, TIMED, UNKNOWN
}

/**
 * The canonical modifier vocabulary. These are the qualifiers that change how a set is performed
 * without changing the numbers, and the ones the stored `Exercise.modifiers` column carries.
 * Strings, not an enum, so [com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary] can grow
 * the recognised surface forms without a schema change — but the canonical set lives here.
 */
object Modifier {
  const val EACH_SIDE = "each_side"
  const val PER_LEG = "per_leg"
  const val CLUSTER = "cluster"
  const val DROP_SET = "drop_set"
  const val REST_PAUSE = "rest_pause"
  const val AMRAP_IN_TIME = "amrap_in_time"
  const val EMOM = "emom"
  const val FOR_TIME = "for_time"
  const val PAUSED = "paused"
  const val TEMPO_ONLY = "tempo_only"
  const val BAND = "band"
  const val CHAIN = "chain"
  const val OPTIONAL = "optional"
  const val TO_FAILURE = "to_failure"
  const val MYO_REPS = "myo_reps"
  const val TOTAL_REPS = "total_reps"

  /** Every canonical modifier, in a fixed order — used by tests and by the review UI. */
  val ALL: List<String> = listOf(
    EACH_SIDE, PER_LEG, CLUSTER, DROP_SET, REST_PAUSE, AMRAP_IN_TIME, EMOM, FOR_TIME,
    PAUSED, TEMPO_ONLY, BAND, CHAIN, OPTIONAL, TO_FAILURE, MYO_REPS, TOTAL_REPS
  )
}

/**
 * One prescription line for an exercise. [count] is the number of sets and is **null when the source
 * didn't say** — null means "the source didn't say", which is not one set. [perSet] holds genuinely
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
  val rest: RestSpec? = null,         // per-set rest (cluster/rest-pause state it per set)
  val modifiers: List<String> = emptyList(),
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

/**
 * One ranked alternative for an unresolved name, so the review screen is a tap rather than a search.
 * [via] names the matcher branch that produced it, which is what makes hit-rate measurable offline.
 */
data class ExerciseCandidate(val name: String, val score: Double, val via: String)

/** A parsed exercise name and how confidently it maps to a known movement. [original] is forever. */
data class ExerciseRef(
  val original: String,
  val resolvedName: String,
  val status: ResolutionStatus = ResolutionStatus.UNRESOLVED,
  val confidence: Double = 0.0,
  /** Top-ranked alternatives, best first. Populated for anything below EXACT. */
  val candidates: List<ExerciseCandidate> = emptyList(),
  /** Which matcher branch produced [resolvedName] — "exact", "alias", "token_set", "phonetic", … */
  val via: String = ""
)

/**
 * A confidence score broken into the named components that produced it, so the UI can say
 * "0.42: exercise inferred, no load, 31% of line unparsed" rather than showing a bare number.
 */
data class ConfidenceBreakdown(
  val structure: Double = 1.0,        // how sure the segmenter was about this line's role
  val notationCoverage: Double = 1.0, // fraction of the source line consumed by recognised tokens
  val resolution: Double = 1.0,       // exercise-resolution tier
  val extractor: Double = 1.0,        // OCR / PDF / grid extractor confidence
  val layoutMargin: Double = 1.0,     // how far the winning spreadsheet layout beat the runner-up
  val reasons: List<String> = emptyList()
) {
  /**
   * The product of the components, clamped to [0,1]. Multiplicative rather than additive on
   * purpose: any single component collapsing (a 0.4-confidence OCR line) must drag the whole slot
   * down, which an average would hide.
   */
  val score: Double
    get() = (structure * notationCoverage * resolution * extractor * layoutMargin).coerceIn(0.0, 1.0)

  companion object {
    val CERTAIN = ConfidenceBreakdown()
  }
}

/** One exercise slot in a session. */
data class SlotDraft(
  val rawText: String,
  val exercise: ExerciseRef,
  val sets: List<SetPrescription> = emptyList(),
  val rest: RestSpec = RestSpec(),
  val supersetGroup: String = "",     // "A", "B" … "" = none
  val supersetOrder: Int? = null,     // the 1 in "A1" — null when the source didn't number it
  val modifiers: List<String> = emptyList(),
  val optional: Boolean = false,
  val notes: String = "",
  val confidence: Double = 1.0,
  val breakdown: ConfidenceBreakdown = ConfidenceBreakdown.CERTAIN,
  val orderIndex: Int = 0
)

/**
 * What a day in the program is. A program that says "Wed — off" is a 5-day week with a rest day in
 * it, not a 4-day week; representing that is the difference between importing the program the coach
 * wrote and importing something adjacent to it.
 */
enum class SessionType { TRAINING, REST, ACTIVE_RECOVERY, DELOAD_NOTE }

data class SessionDraft(
  val label: String,
  val dayNumber: Int,
  val weekdayHint: String = "",
  val notes: String = "",
  val type: SessionType = SessionType.TRAINING,
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

/**
 * A thing the user should look at before committing — surfaced, never silently resolved.
 * [sourceLine] is the verbatim line the flag came from, so the review screen can show the evidence
 * next to the claim.
 */
data class ImportFlag(
  val severity: FlagSeverity,
  val message: String,
  val path: String = "",
  val sourceLine: String = ""
)

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

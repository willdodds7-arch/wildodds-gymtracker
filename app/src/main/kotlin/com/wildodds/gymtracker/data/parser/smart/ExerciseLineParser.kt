package com.wildodds.gymtracker.data.parser.smart

// ─── Parses a single "exercise line" that may contain inline sets/reps/weight ─

object ExerciseLineParser {

  // ── Regex pool ────────────────────────────────────────────────────────────

  // 3x8, 4 x 8-10, 3×12, 3X5
  private val SETS_REPS_INLINE = Regex("""(\d+)\s*[xX×]\s*(\d+(?:-\d+)?)""")

  // "3 sets of 8", "4 sets of 8-10"
  private val SETS_OF_REPS = Regex("""(\d+)\s+sets?\s+of\s+(\d+(?:-\d+)?)""", RegexOption.IGNORE_CASE)

  // "AMRAP", "to failure", "max"
  private val AMRAP = Regex("""(?:amrap|to\s+failure|max\s+reps?)""", RegexOption.IGNORE_CASE)

  // 80kg, 80 kg, 175lbs, 175 lb
  private val WEIGHT = Regex("""(\d+(?:\.\d+)?)\s*(kg|lbs?)\b""", RegexOption.IGNORE_CASE)

  // RPE 8, RPE8, @8
  private val RPE = Regex("""(?:RPE\s*(\d+(?:\.\d+)?)|@\s*(\d+(?:\.\d+)?))""", RegexOption.IGNORE_CASE)

  // RIR 2
  private val RIR = Regex("""RIR\s*(\d+)""", RegexOption.IGNORE_CASE)

  // Rest: 90s / 2min / rest 90 seconds
  private val REST = Regex("""rest[:\s]+(\d+)\s*(s|sec(?:onds?)?|m|min(?:utes?)?)""", RegexOption.IGNORE_CASE)

  // Superset group: "A1.", "B2.", "A1 -"
  private val SUPERSET = Regex("""^([A-Z])(\d)\s*[.\-)\s]""")

  // Leading bullets / numbers stripped from exercise names
  private val LEADING_NOISE = Regex("""^[\d]+[.):\-\s]+|^[-•·✓✗\s]+""")

  // ── Abbreviation expansion map ────────────────────────────────────────────

  private val ABBREVS = mapOf(
  Regex("""\bBP\b""")  to "Bench Press",
  Regex("""\bDL\b""")  to "Deadlift",
  Regex("""\bOHP\b""")  to "Overhead Press",
  Regex("""\bRDL\b""")  to "Romanian Deadlift",
  Regex("""\bSLDL\b""") to "Stiff Leg Deadlift",
  Regex("""\bBSS\b""")  to "Bulgarian Split Squat",
  Regex("""\bDB\b""")  to "Dumbbell",
  Regex("""\bBB\b""")  to "Barbell",
  Regex("""\bKB\b""")  to "Kettlebell",
  Regex("""\bKBS\b""")  to "Kettlebell Swing",
  Regex("""\bBOR\b""")  to "Barbell Row",
  Regex("""\bSQ\b""")  to "Squat",
  Regex("""\bLPD\b""")  to "Lat Pulldown",
  Regex("""\bGHR\b""")  to "Glute Ham Raise",
  Regex("""\bRFE\b""")  to "Rear Foot Elevated",
  Regex("""\bCGBP\b""") to "Close Grip Bench Press",
  Regex("""\bCG\b""")  to "Close Grip",
  Regex("""\bNG\b""")  to "Neutral Grip",
  Regex("""\bWG\b""")  to "Wide Grip",
  Regex("""\bBW\b""")  to "Bodyweight",
  Regex("""\bGM\b""")  to "Good Morning",
  Regex("""\bCGP\b""")  to "Close Grip Press",
  Regex("""\bPB\b""")  to "Preacher Curl",
  Regex("""\bFB\b""")  to "Face Pull",
  Regex("""\bLP\b""")  to "Leg Press",
  Regex("""\bLE\b""")  to "Leg Extension",
  Regex("""\bLC\b""")  to "Leg Curl"
  )

  // ── Public API ────────────────────────────────────────────────────────────

  /**
  * Try to extract sets, reps, weight from a single inline string like
  * "Bench Press 3x8 @80kg" or "3x8 @ 80kg" (used for horizontal cells).
  * Returns null fields when not found.
  */
  data class InlineParsed(
  val sets: Int?,
  val reps: String?,
  val weight: Float?,
  val weightUnit: WeightUnit,
  val rpe: Float?,
  val notes: String?
  )

  fun parseInline(raw: String): InlineParsed {
  var sets: Int? = null
  var reps: String? = null
  var weight: Float? = null
  var weightUnit = WeightUnit.UNKNOWN
  var rpe: Float? = null

  // Sets × reps
  SETS_REPS_INLINE.find(raw)?.also {
  sets = it.groupValues[1].toIntOrNull()
  reps = it.groupValues[2]
  } ?: SETS_OF_REPS.find(raw)?.also {
  sets = it.groupValues[1].toIntOrNull()
  reps = it.groupValues[2]
  }

  if (AMRAP.containsMatchIn(raw) && reps == null) reps = "AMRAP"

  WEIGHT.find(raw)?.also {
  weight = it.groupValues[1].toFloatOrNull()
  weightUnit = if (it.groupValues[2].lowercase().startsWith("k")) WeightUnit.KG else WeightUnit.LBS
  }

  RPE.find(raw)?.also {
  rpe = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toFloatOrNull()
  }

  val noteParts = mutableListOf<String>()
  if (rpe != null) noteParts.add("RPE $rpe")
  RIR.find(raw)?.also { noteParts.add("RIR ${it.groupValues[1]}") }

  return InlineParsed(sets, reps, weight, weightUnit, rpe, noteParts.joinToString(", ").ifBlank { null })
  }

  /** Clean an exercise name: strip bullets, expand abbreviations. */
  fun cleanName(raw: String): String {
  var name = raw.trim()
  name = LEADING_NOISE.replace(name, "")
  name = name.trim()
  for ((pattern, replacement) in ABBREVS) {
  name = pattern.replace(name, replacement)
  }
  return name.trim()
  }

  /** Detect superset group prefix ("A1.", "B2 -") → return group letter or null */
  fun supersetGroup(raw: String): String? =
  SUPERSET.find(raw.trim())?.groupValues?.get(1)

  /** Parse "90s", "2min", "120 seconds" → seconds, or null */
  fun parseRest(raw: String): Int? {
  val m = REST.find(raw) ?: return null
  val value = m.groupValues[1].toIntOrNull() ?: return null
  val unit = m.groupValues[2].lowercase()
  return if (unit.startsWith("m")) value * 60 else value
  }

  /** Parse weight unit from a column header string */
  fun weightUnitFromHeader(header: String): WeightUnit {
  val h = header.lowercase()
  return when {
  "kg" in h || "kilo" in h -> WeightUnit.KG
  "lb" in h || "pound" in h -> WeightUnit.LBS
  else -> WeightUnit.UNKNOWN
  }
  }

  // ── Column header → column type ───────────────────────────────────────────

  enum class ColType { EXERCISE, SETS, REPS, WEIGHT, NOTES, REST, RPE, PCT_1RM, SUPERSET, MUSCLE_GROUP, DAY, IGNORE }

  fun classifyHeader(header: String): ColType {
  val h = header.lowercase().trim()
  return when {
  // Exercise name column (exact + fuzzy)
  h in setOf("exercise", "ex", "movement", "name", "lift", "drill", "exercises", "exercise name") -> ColType.EXERCISE
  "exercise" in h || "movement" in h -> ColType.EXERCISE

  // Sets
  h in setOf("sets", "set", "s", "# sets", "num sets") -> ColType.SETS

  // Reps
  h in setOf("reps", "rep", "r", "repetitions", "repetition", "reps/set", "reps per set") -> ColType.REPS

  // %1RM — check before weight so "% of 1rm" doesn't fall through
  h in setOf("%1rm", "% 1rm", "pct 1rm", "% of 1rm", "%1rm target", "1rm%", "intensity%", "percentage", "% 1 rm", "1rm %") -> ColType.PCT_1RM
  "%1rm" in h || "1rm%" in h || ("1rm" in h && "%" in h) || ("%" in h && "max" in h) -> ColType.PCT_1RM

  // Weight
  h in setOf("weight", "wt", "load", "kg", "kgs", "lbs", "lb", "w", "weight (kg)", "weight (lbs)", "load (kg)", "working weight", "target weight") -> ColType.WEIGHT
  ("weight" in h || "load" in h) && "%" !in h -> ColType.WEIGHT
  h == "kg" || h == "lbs" || h == "lb" -> ColType.WEIGHT

  // RPE / RIR
  h in setOf("rpe", "intensity", "effort", "rpe target", "target rpe", "rir", "rpe/rir", "rpe or rir") -> ColType.RPE
  "rpe" in h || "rir" in h -> ColType.RPE

  // Rest
  h in setOf("rest", "rest time", "recovery", "rest (s)", "rest (sec)", "rest period", "rest between sets") -> ColType.REST
  h.startsWith("rest") -> ColType.REST

  // Superset
  h in setOf("superset", "ss", "superset group", "circuit", "pair", "super set", "group") -> ColType.SUPERSET
  "superset" in h -> ColType.SUPERSET

  // Muscle group
  h in setOf("muscle group", "muscles", "muscle", "focus", "body part", "target", "targets", "primary muscle") -> ColType.MUSCLE_GROUP
  "muscle" in h -> ColType.MUSCLE_GROUP

  // Notes
  h in setOf("notes", "note", "comments", "comment", "coaching", "cue", "description", "cues", "coaching cues", "instructions") -> ColType.NOTES
  "note" in h || "comment" in h || "cue" in h -> ColType.NOTES

  // Day
  h in setOf("day", "session", "workout") -> ColType.DAY

  else -> ColType.IGNORE
  }
  }

  // ── Day label detection ────────────────────────────────────────────────────

  private val DAY_PATTERNS = listOf(
  Regex("""^day\s*(\d+|[a-z])""", RegexOption.IGNORE_CASE),
  Regex("""^session\s*(\d+|[a-z])""", RegexOption.IGNORE_CASE),
  Regex("""^(monday|tuesday|wednesday|thursday|friday|saturday|sunday)""", RegexOption.IGNORE_CASE),
  Regex("""^(upper|lower|push|pull|legs?|full\s*body|back|chest|shoulders?|arms?)""", RegexOption.IGNORE_CASE),
  Regex("""^workout\s*([a-zA-Z]|\d+)""", RegexOption.IGNORE_CASE),
  Regex("""^training\s+day\s*(\d+)""", RegexOption.IGNORE_CASE),
  Regex("""^(squat|deadlift|bench|overhead)\s+day""", RegexOption.IGNORE_CASE),
  Regex("""^(hypertrophy|strength|power|deload|accumulation|intensification|realization)\s*(day)?\s*\d*""", RegexOption.IGNORE_CASE),
  Regex("""^(a|b|c|d)\s*[-:]\s*(upper|lower|push|pull|legs?|full|back|chest|shoulder)""", RegexOption.IGNORE_CASE),
  Regex("""^(upper|lower|push|pull)\s+[ab12]$""", RegexOption.IGNORE_CASE)
  )

  fun isDayLabel(text: String): Boolean = DAY_PATTERNS.any { it.containsMatchIn(text.trim()) }

  // ── Week label detection ───────────────────────────────────────────────────

  private val WEEK_LABEL = Regex(
  """(?:week|wk|w)\s*(\d+)|block\s*(\d+)|phase\s*(\d+)|mesocycle\s*(\d+)|cycle\s*(\d+)""",
  RegexOption.IGNORE_CASE
  )

  fun extractWeekNumber(text: String): Int? {
  val m = WEEK_LABEL.find(text.trim()) ?: return null
  return (1..5).firstNotNullOfOrNull { m.groupValues[it].toIntOrNull() }
  }

  fun isWeekLabel(text: String): Boolean = WEEK_LABEL.containsMatchIn(text.trim())
}

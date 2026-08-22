package com.wildodds.gymtracker.data.importer

/**
 * Turns one written exercise line into a [SlotDraft] — the hard, format-agnostic part of import.
 *
 * Handles the notations lifters actually write: `3x8`, `3x8-12`, `3X8`, `3*8`, `5/5/5/3/1`,
 * `3x5 @RPE8`, `3x5 @75%`, `5x5 @100kg`, top-set `+` / `5+` AMRAP, `AMRAP`, time `3x30s`,
 * distance `5x400m`, EMOM, tempo `31X0` / `3-1-2-0`, rest `rest 2-3 min`, supersets `A1)`,
 * `bodyweight (+X)`, and "optional / if time".
 *
 * Boring and deterministic on purpose: a clean line never needs an LLM. When a line can't be
 * structured, the exercise name is still captured and the whole line is preserved verbatim
 * ([SlotDraft.rawText] + a UNKNOWN prescription) — **nothing is invented and nothing is dropped.**
 */
object SetNotationParser {

  // ── component regexes (case-insensitive) ─────────────────────────────────────
  private val superset = Regex("""^\s*([A-Da-d])[\s.)-]*([12])\b[\s.):-]*""")
  private val rpe      = Regex("""(?i)@?\s*rpe\s*(\d{1,2}(?:\.\d)?)(?:\s*[-–]\s*(\d{1,2}(?:\.\d)?))?""")
  private val rir      = Regex("""(?i)@?\s*(\d{1,2})\s*rir\b|(?i)\brir\s*(\d{1,2})""")
  private val pct      = Regex("""(?i)@?\s*(\d{1,3}(?:\.\d+)?)\s*%\s*(1rm|tm|training\s*max)?""")
  private val absWeight= Regex("""(?i)@?\s*(\d{1,4}(?:\.\d+)?)\s*(kg|kgs|lb|lbs|#)\b""")
  private val restRange= Regex("""(?i)\brest[:=\s]*?(\d{1,3})\s*[-–]\s*(\d{1,3})\s*(s|sec|secs|m|min|mins)\b""")
  private val restOne  = Regex("""(?i)\brest[:=\s]*?(\d{1,3})\s*(s|sec|secs|m|min|mins)\b""")
  // Tempo requires an uppercase X (e.g. 31X0) or the dashed form (3-1-2-0), so it never captures a
  // lowercase sets×reps token like "10x8".
  private val tempo    = Regex("""\b(\d[\dX]X\d|\d-\d-\d-\d)\b""")
  // Per-set list: slash form (5/3/1) needs ≥2 numbers; comma form (10, 8, 6) needs ≥3 to avoid
  // eating "5, 2 min rest" style separators. Enforced in findCore.
  private val perSet   = Regex("""\b(\d{1,3}(?:\s*[/,]\s*\d{1,3}){1,})""")
  // sets x reps, optional rep-range, optional trailing "+", optional unit (time/distance/reps)
  // No trailing \b: a token can legitimately end in "+" (AMRAP, e.g. "1x5+"), and \b never holds
  // after a non-word char — which would otherwise force a backtrack that drops the "+".
  // Accepts the terse gym forms (3x8, 3*8, 3×8) AND the long forms a generated program uses
  // ("4 sets of 5", "4 sets x 5 reps", "4 sets × 5 reps") — the latter is what most pasted
  // programs actually contain, and without it the whole line falls through as unparsed.
  private val setsReps = Regex("""(?i)\b(\d{1,2})\s*(?:\s*sets?\s*(?:of|x|×|\*)\s*|x|×|\*)\s*(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?\s*(\+)?\s*(s|sec|secs|m|min|mins|km|yd|reps|rep)?""")
  private val bodyweight = Regex("""(?i)\b(bodyweight|body\s*weight|bw)\b(?:\s*\+\s*(\d{1,4}(?:\.\d+)?)\s*(kg|lb|lbs|#)?)?""")

  fun parseExerciseLine(line: String, orderIndex: Int): SlotDraft? {
    val original = line.trim().trimTrailingSeparators()
    if (original.isBlank()) return null
    var working = original

    // 1) superset label at the very start ("A1) Bench …")
    var ssGroup = ""
    superset.find(working)?.let { m ->
      ssGroup = m.groupValues[1].uppercase()
      working = working.removeRange(m.range)
    }

    // 2) optional flag
    val optional = Regex("""(?i)\b(optional|if\s+time(\s+permits)?)\b""").containsMatchIn(working)

    // 3) load — RPE / RIR / % / absolute / bodyweight (search the whole line; loads float around)
    val loads = mutableListOf<LoadSpec>()
    rpe.find(working)?.let { m ->
      val lo = m.groupValues[1]; val hi = m.groupValues[2]
      loads += LoadSpec(LoadKind.RPE, lo.toDoubleOrNull(), text = if (hi.isNotBlank()) "$lo-$hi" else lo)
    }
    if (loads.none { it.kind == LoadKind.RPE }) rir.find(working)?.let { m ->
      val v = (m.groupValues[1].ifBlank { m.groupValues[2] })
      loads += LoadSpec(LoadKind.RIR, v.toDoubleOrNull(), text = v)
    }
    // A single "TM" anywhere on the line governs every percentage on it: coaches write
    // "65%, 75%, 85% TM", not "65% TM, 75% TM, 85% TM".
    val lineMentionsTm = Regex("""(?i)(tm|training\s*max)""").containsMatchIn(working)
    pct.findAll(working).forEach { m ->
      val isTm = lineMentionsTm ||
        m.groupValues[2].lowercase().let { it == "tm" || it.contains("training") }
      loads += LoadSpec(if (isTm) LoadKind.PCT_TM else LoadKind.PCT_1RM, m.groupValues[1].toDoubleOrNull(), text = m.value.trim())
    }
    absWeight.find(working)?.let { m ->
      val unit = if (m.groupValues[2].startsWith("kg")) WeightUnit.KG else WeightUnit.LB
      loads += LoadSpec(LoadKind.ABSOLUTE, m.groupValues[1].toDoubleOrNull(), unit = unit, text = m.value.trim())
    }
    bodyweight.find(working)?.let { m ->
      val extra = m.groupValues[2]
      loads += LoadSpec(LoadKind.BODYWEIGHT, extra.toDoubleOrNull(),
        unit = if (m.groupValues[3].startsWith("lb")) WeightUnit.LB else WeightUnit.KG,
        text = m.value.trim())
    }

    // 4) rest (range beats single)
    val rest = restRange.find(working)?.let { m ->
      val mult = if (m.groupValues[3].startsWith("m")) 60 else 1
      RestSpec(RestKind.RANGE, m.groupValues[1].toInt() * mult, m.groupValues[2].toInt() * mult, m.value.trim())
    } ?: restOne.find(working)?.let { m ->
      val mult = if (m.groupValues[2].startsWith("m")) 60 else 1
      RestSpec(RestKind.FIXED, m.groupValues[1].toInt() * mult, text = m.value.trim())
    } ?: when {
      Regex("""(?i)\bno\s+rest\b|\bsuperset\b""").containsMatchIn(working) -> RestSpec(RestKind.SUPERSET_NONE, text = "no rest")
      Regex("""(?i)\bas\s+needed\b""").containsMatchIn(working) -> RestSpec(RestKind.AS_NEEDED, text = "as needed")
      else -> RestSpec()
    }

    // 5) tempo
    val tempoStr = tempo.find(working)?.groupValues?.get(1).orEmpty()

    // 6) the sets×reps core — this also anchors where the exercise name ends
    val emom = Regex("""(?i)\bemom\b""").containsMatchIn(working)
    val core = findCore(working)

    val (name, prescription) = if (core != null) {
      val (range, spec) = core
      val nm = working.substring(0, range.first).trimTrailingSeparators()
      val withLoad = spec.copy(
        load = loads.firstOrNull { it.kind != LoadKind.NONE },
        tempo = tempoStr,
        kind = if (emom) SetKind.EMOM else spec.kind
      )
      nm to withLoad
    } else {
      // No sets/reps recognised — keep the exercise, mark the prescription UNKNOWN (never guess a count).
      val nm = stripTrailingNoise(working)
      nm to SetPrescription(rawText = original, kind = SetKind.UNKNOWN, load = loads.firstOrNull { it.kind != LoadKind.NONE }, tempo = tempoStr)
    }

    if (name.isBlank()) return null

    val confidence = when {
      core == null -> 0.35            // recognised a name but not the loading
      loads.isEmpty() && prescription.reps == null -> 0.6
      else -> 0.9
    }

    return SlotDraft(
      rawText = original,
      exercise = ExerciseRef(original = name, resolvedName = titleCase(name)),
      sets = listOf(prescription),
      rest = rest,
      supersetGroup = ssGroup,
      optional = optional,
      confidence = confidence,
      orderIndex = orderIndex
    )
  }

  /** Finds the sets×reps token (or a per-set list) and returns its span + a structured prescription. */
  private fun findCore(s: String): Pair<IntRange, SetPrescription>? {
    setsReps.find(s)?.let { m ->
      val count = m.groupValues[1].toInt()
      val repLo = m.groupValues[2].toInt()
      val repHi = m.groupValues[3].toIntOrNull()
      val plus  = m.groupValues[4] == "+"
      val unit  = m.groupValues[5].lowercase()
      val reps = when (unit) {
        "s", "sec", "secs" -> RepSpec(RepKind.TIME_SEC, repLo, text = m.value)
        "min", "mins"      -> RepSpec(RepKind.TIME_SEC, repLo * 60, text = m.value)
        "m", "yd"          -> RepSpec(RepKind.DISTANCE_M, repLo, text = m.value)
        "km"               -> RepSpec(RepKind.DISTANCE_M, repLo * 1000, text = m.value)
        else -> when {
          plus         -> RepSpec(RepKind.AMRAP_MIN, repLo, text = "$repLo+")
          repHi != null -> RepSpec(RepKind.RANGE, repLo, repHi)
          else         -> RepSpec(RepKind.COUNT, repLo)
        }
      }
      val kind = when {
        reps.kind == RepKind.TIME_SEC -> SetKind.TIMED
        reps.kind == RepKind.DISTANCE_M -> SetKind.CARRY
        reps.kind == RepKind.RANGE -> SetKind.RANGE
        else -> SetKind.STRAIGHT
      }
      return m.range to SetPrescription(
        rawText = m.value, kind = kind, count = count, reps = reps, isFinalSetAmrap = plus
      )
    }
    // per-set list like 5/5/5/3/1, 10/8/6, or 10, 8, 6
    perSet.find(s)?.let { m ->
      val raw = m.groupValues[1]
      val nums = raw.split(Regex("[/,]")).mapNotNull { it.trim().toIntOrNull() }
      val enough = if (raw.contains('/')) nums.size >= 2 else nums.size >= 3
      if (enough) {
        val children = nums.map { SetPrescription(rawText = it.toString(), count = 1, reps = RepSpec(RepKind.COUNT, it)) }
        return m.range to SetPrescription(
          rawText = m.value, kind = SetKind.STRAIGHT, count = nums.size,
          reps = RepSpec(RepKind.COUNT, nums.first()), perSet = children
        )
      }
    }
    // "AMRAP" with no count
    Regex("""(?i)\bamrap\b""").find(s)?.let { m ->
      return m.range to SetPrescription(m.value, SetKind.STRAIGHT, count = 1, reps = RepSpec(RepKind.AMRAP, text = "AMRAP"), isFinalSetAmrap = true)
    }
    return null
  }

  /** Removes load/rest/tempo noise from a name-only line so the movement name reads cleanly. */
  private fun stripTrailingNoise(s: String): String {
    var out = s
    for (r in listOf(rpe, rir, pct, absWeight, restRange, restOne, tempo, bodyweight)) out = r.replace(out, " ")
    return out.replace(Regex("""\s{2,}"""), " ").trimTrailingSeparators()
  }

  private fun titleCase(s: String): String =
    s.split(" ").filter { it.isNotBlank() }.joinToString(" ") { w ->
      if (w.length <= 3 && w.uppercase() == w) w  // keep BB/DB/OHP acronyms
      else w.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * Trim whitespace and any trailing run of separator punctuation together.
 *
 * `trimEnd(',', '-', ':')` stops at the first character outside its set, so "Back Squat - " keeps
 * its trailing space once the dash is removed — and a name with a trailing space then fails every
 * comparison against the exercise library.
 */
internal fun String.trimTrailingSeparators(): String =
  trim().replace(Regex("""[\s,;:.\-@|]+$"""), "").trim()

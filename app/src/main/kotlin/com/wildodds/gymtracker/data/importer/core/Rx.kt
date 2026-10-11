package com.wildodds.gymtracker.data.importer.core

import com.wildodds.gymtracker.data.importer.LoadKind
import com.wildodds.gymtracker.data.importer.LoadSpec
import com.wildodds.gymtracker.data.importer.Modifier
import com.wildodds.gymtracker.data.importer.RepKind
import com.wildodds.gymtracker.data.importer.RepSpec
import com.wildodds.gymtracker.data.importer.RestKind
import com.wildodds.gymtracker.data.importer.RestSpec
import com.wildodds.gymtracker.data.importer.SetKind
import com.wildodds.gymtracker.data.importer.SetPrescription
import com.wildodds.gymtracker.data.importer.WeightUnit
import com.wildodds.gymtracker.data.importer.grammar.LineGrammar
import java.util.Locale

/**
 * Reads ONE line (or one cell) of a training program: the exercise name and its prescription.
 *
 * This is the notation half of the import core. It is deliberately forgiving about *where* the
 * numbers are — "Squat 4x6", "4 x 6 Squat", "Squat: 4 sets of 6 reps", "Squat – Sets: 4, Reps: 6",
 * "squats four sets of six", "Bench 5 sets: 12, 10, 8, 6, 4" — and strict about *what* they are:
 * a number only becomes a set count or a rep target when the notation around it says so. A number
 * that is a load ("@ 65/75/85%"), a rest ("rest 3 min"), a tempo or part of a name ("45 Degree Back
 * Extension") is never read as reps.
 *
 * Everything the line says that is not the name is consumed and removed from it, so the name that
 * comes back is the movement and nothing else: no "(1 sec pause)", no "@ RPE 8", no "each leg".
 *
 * Pure, deterministic and locale-independent.
 */
object Rx {

  /** One line, read. [hasRx] is false when no set/rep notation was found at all. */
  data class Line(
    val raw: String,
    val name: String,
    val sets: Int? = null,
    val setsMax: Int? = null,
    val reps: RepSpec? = null,
    /** One rep target per set, when the line prescribes different reps per set ("12/10/8"). */
    val ladder: List<RepSpec> = emptyList(),
    val load: LoadSpec? = null,
    val rest: RestSpec? = null,
    val tempo: String = "",
    val modifiers: List<String> = emptyList(),
    val note: String = "",
    val supersetGroup: String = "",
    val supersetOrder: Int? = null,
    val warmupOnly: Boolean = false,
    /** Fraction of the line's non-space characters that were understood, in [0, 1]. */
    val coverage: Double = 1.0,
    /** A richer reading from the specialist grammar (EMOM, clusters, pyramids…), when it applies. */
    val override: SetPrescription? = null
  ) {
    val hasRx: Boolean get() = sets != null || reps != null || ladder.isNotEmpty()
    val hasSetsAndReps: Boolean get() = sets != null && (reps != null || ladder.isNotEmpty())

    fun toPrescription(): SetPrescription {
      override?.let { o ->
        return o.copy(rawText = raw, load = o.load ?: load, tempo = o.tempo.ifBlank { tempo }, rest = o.rest ?: rest,
          modifiers = (o.modifiers + modifiers).distinct())
      }
      val equalLadder = ladder.size > 1 && ladder.all { it == ladder[0] }
      val effLadder = if (equalLadder) emptyList() else ladder
      val effReps = if (equalLadder) ladder[0] else reps
      val count = sets ?: effLadder.size.takeIf { it > 0 }
      val kind = when {
        effLadder.isNotEmpty() -> SetKind.LADDER
        effReps?.kind == RepKind.RANGE -> SetKind.RANGE
        effReps?.kind == RepKind.TIME_SEC -> SetKind.TIMED
        effReps?.kind == RepKind.DISTANCE_M -> SetKind.CARRY
        effReps == null && count == null -> SetKind.UNKNOWN
        else -> SetKind.STRAIGHT
      }
      return SetPrescription(
        rawText = raw,
        kind = kind,
        count = count,
        reps = effReps,
        load = load,
        tempo = tempo,
        rest = rest,
        modifiers = modifiers,
        perSet = effLadder.map { r -> SetPrescription(rawText = r.text, kind = SetKind.STRAIGHT, count = 1, reps = r, load = load) }
      )
    }
  }

  // ── building blocks ──────────────────────────────────────────────────────────

  private const val UNIT =
    "(?:seconds|second|secs|sec|minutes|minute|mins|min|metres|meters|metre|meter|yards|yard|yds|yd|kcal|cals|cal|s|m|\")"
  /** One rep target: 8 | 8-12 | 5+ | 45s | 1 min | 500m | AMRAP | max | failure. */
  private const val ATOM =
    "(?:\\d{1,2}:\\d{2}(?![\\d:])|\\d{1,4}(?:\\.\\d)?(?:\\s*-\\s*\\d{1,4})?\\+?(?:\\s*$UNIT(?![a-z]))?|amrap|amap|max(?:\\s+reps)?|to\\s+failure|failure|f(?![a-z]))"
  /** Several rep targets, one per set: 12/10/8 | 12, 10, 8 | 5/3/1+ */
  private const val LADDER = "$ATOM(?:\\s*[/,]\\s*$ATOM)+"
  private const val REPS = "(?:$LADDER|$ATOM)"
  private const val SETS = "(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?"

  /** The forms, most specific first. */
  // Not preceded by a list separator either: in "1,2,3,4,5 x 5 rounds" the "5 x 5" is the tail of a
  // ladder, not a prescription of its own.
  private val SETS_X_REPS = Regex("(?<![\\w.,/])$SETS\\s*(?:x|\\*|by)\\s*($REPS)(?!\\s*%)(?![\\w.])")
  private val SETS_OF_REPS = Regex("(?<![\\w.])$SETS\\s*(?:sets?|rounds?)\\b\\s*(?:of|x|by|:|,|-|of\\s+about)?\\s*($REPS)(?!\\s*%)\\s*(?:reps?\\b|repetitions?\\b)?")
  private val SETS_TO_FAILURE = Regex("(?<![\\w.])$SETS\\s*sets?\\s*(?:to\\s+(?:failure|max)|of\\s+max(?:\\s+reps)?|of\\s+amrap|amrap|x\\s*max)")
  private val LABEL_SETS = Regex("\\bsets?\\s*[:=]\\s*$SETS\\b")
  private val LABEL_REPS = Regex("\\breps?\\s*[:=]\\s*($REPS)(?!\\s*%)")
  private val REPS_THEN_SETS = Regex("(?<![\\w.])($REPS)\\s*reps?\\s*(?:x|,|for|of)?\\s*(\\d{1,2})\\s*sets?\\b")
  private val SETS_ONLY = Regex("(?<![\\w.])$SETS\\s*sets?\\b(?!\\s*(?:of|x|:))")
  private val REPS_ONLY = Regex("(?<![\\w.])($REPS)\\s*(?:reps?|repetitions?)\\b")
  private val TIME_ONLY = Regex("(?<![\\w.])(\\d{1,3}(?:\\.\\d)?\\s*(?:seconds|second|secs|sec|s|minutes|minute|mins|min))\\s*(?:hold)?\\s*$")

  private const val REST_UNIT =
    "(seconds|second|secs|sec|sekunden|sek|segundos|seg|secondes|s|minutes|minute|minuten|minutos|mins|min|m|'|\")"
  private val REST = listOf(
    Regex("\\brest(?:\\s*(?:for|:|-|of))?\\s*(\\d{1,2}:\\d{2}|\\d{1,3}(?:\\.\\d)?)(?:\\s*-\\s*(\\d{1,3}(?:\\.\\d)?))?\\s*$REST_UNIT?(?![a-z])" +
      // "…between (working) sets / exercises / rounds" belongs to the rest instruction.
      "(?:\\s*(?:between|after|per)\\s+(?:each\\s+)?(?:working\\s+)?(?:sets?|exercises?|rounds?|movements?|lifts?))?"),
    Regex("(\\d{1,3}(?:\\.\\d)?)(?:\\s*-\\s*(\\d{1,3}(?:\\.\\d)?))?\\s*$REST_UNIT\\s*rest\\b(?:\\s*(?:between|after)\\s+(?:sets?|exercises?|rounds?))?")
  )
  /** Accommodating resistance: "+ 20kg chain", "+ red band", "with bands". */
  private val CHAIN_BAND = Regex("(?:\\+|\\bwith\\b|\\bplus\\b)?\\s*(?:(\\d{1,3}(?:\\.\\d)?)\\s*(kg|kgs|lb|lbs)\\s*(?:of\\s+)?)?((?:red|green|blue|black|purple|orange|yellow|grey|gray|light|mini|monster|average|strong|heavy|micro|pink)\\s+)?(chains?|bands?)\\b")
  private val LOAD_ABS = Regex("@?\\s*(?<![\\w.])(\\d{1,4}(?:\\.\\d{1,2})?)(?:\\s*-\\s*(\\d{1,4}(?:\\.\\d{1,2})?))?\\s*(kg|kgs|kilos?|lb|lbs|pounds?)\\b")
  private val LOAD_PCT = Regex("@?\\s*(?<![\\w.])(\\d{1,3}(?:\\.\\d)?)((?:\\s*%?\\s*[-/,]\\s*\\d{1,3}(?:\\.\\d)?)*)\\s*%\\s*(?:(?:of\\s*)?(1rm|tm|e1rm|max|training\\s+max))?")
  private val LOAD_RPE = Regex("@?\\s*\\brpe\\s*[:=]?\\s*(\\d{1,2}(?:\\.\\d)?)(?:\\s*-\\s*(\\d{1,2}(?:\\.\\d)?))?")
  private val LOAD_RPE_AT = Regex("@\\s*(\\d{1,2}(?:\\.\\d)?)(?![\\d%.]|\\s*(?:kg|lb|x))")
  private val LOAD_RIR = Regex("\\b(\\d)\\s*rir\\b|\\brir\\s*[:=]?\\s*(\\d)")
  private val LOAD_BW = Regex("\\b(bodyweight|body\\s+weight|bw)\\b")
  private val EACH_SIDE = Regex("(?:\\b(?:each|per|a)\\s+(?:side|leg|arm|way|hand)\\b|\\beach\\b|/\\s*(?:side|leg|arm)\\b|\\be/?s\\b|\\bl/r\\b|\\bper\\s+side\\b)")
  private val TEMPO = Regex("\\btempo\\s*[:=-]?\\s*([0-9x]{4}|\\d-\\d-\\d-\\d)\\b")
  private val WARMUP = Regex("\\b(warm\\s*-?\\s*ups?|wu|ramp(?:ing)?|feeder|primer)\\b")

  private val NUMBER_WORDS = mapOf(
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
    "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20
  )
  private val NUM_WORD = NUMBER_WORDS.keys.joinToString("|")

  // ── normalisation ────────────────────────────────────────────────────────────

  /** Unicode tidy + symbol strip. Keeps letters, digits, everyday punctuation. */
  fun clean(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
      when {
        ch == '×' || ch == '✕' || ch == '✖' || ch == 'Х' -> sb.append('x')
        ch == '–' || ch == '—' || ch == '−' || ch == '‒' -> sb.append('-')
        ch == '’' || ch == '‘' || ch == '`' -> sb.append('\'')
        ch == '“' || ch == '”' -> sb.append('"')
        ch == '\t' || ch == ' ' || ch == ' ' || ch == ' ' -> sb.append(' ')
        ch == '•' || ch == '▪' || ch == '◦' || ch == '·' || ch == '●' || ch == '►' || ch == '➤' || ch == '→' -> sb.append(" - ")
        Character.isSurrogate(ch) -> {}
        Character.getType(ch) == Character.OTHER_SYMBOL.toInt() -> {}
        else -> sb.append(ch)
      }
    }
    return sb.toString().replace(Regex("\\s+"), " ").trim()
  }

  /** Number words → digits, but only where they are notation ("three sets of ten"), never in names. */
  private fun numberWords(s: String): String {
    var out = s
    out = Regex("(?i)\\b($NUM_WORD)\\s+(sets?|reps?|rounds?|x)\\b").replace(out) { m ->
      "${NUMBER_WORDS[m.groupValues[1].lowercase()]} ${m.groupValues[2]}"
    }
    out = Regex("(?i)\\b(sets?\\s+of|x|by)\\s+($NUM_WORD)\\b").replace(out) { m ->
      "${m.groupValues[1]} ${NUMBER_WORDS[m.groupValues[2].lowercase()]}"
    }
    return out
  }

  /** OCR confusables, ONLY in tokens that are already numeric-shaped ("l0", "8-lO", "3 x l5"). */
  fun repairNumericToken(t: String): String {
    if (!t.any { it.isDigit() } && !(t.length in 2..3 && t.all { it in "lIoO" })) return t
    if (!t.all { it.isDigit() || it in "lIoO-+" }) return t
    return t.map { when (it) { 'l', 'I' -> '1'; 'o', 'O' -> '0'; else -> it } }.joinToString("")
  }

  private fun repairConfusables(s: String): String =
    Regex("(?<![A-Za-z])[0-9lIoO][0-9lIoO\\-+]*(?![A-Za-z])").replace(s) { m ->
      val t = m.value
      // Only rewrite inside a prescription context: next to an x, or a number-ish run with a digit.
      if (t.any { it.isDigit() } || (t.length >= 2 && t.all { it in "lIoO" } && Regex("[xX*]\\s*$").containsMatchIn(s.substring(0, m.range.first))))
        repairNumericToken(t) else t
    }

  // ── reps / sets atoms (also used for spreadsheet cells) ─────────────────────

  fun repAtom(rawAtom: String): RepSpec? {
    val a = rawAtom.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    if (a.isEmpty()) return null
    if (Regex("^(amrap|amap|max|max reps|to failure|failure|f|af)\\+?$").matches(a)) return RepSpec(RepKind.AMRAP, text = "AMRAP")
    Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(a)?.let {
      return timeSpec(it.groupValues[1].toInt() * 60.0 + it.groupValues[2].toInt(), null)
    }
    Regex("^(\\d{1,4})\\+$").matchEntire(a)?.let { return RepSpec(RepKind.AMRAP_MIN, it.groupValues[1].toInt(), text = "${it.groupValues[1]}+") }
    Regex("^(\\d{1,4})\\s*-\\s*(\\d{1,4})$").matchEntire(a)?.let {
      val lo = it.groupValues[1].toInt(); val hi = it.groupValues[2].toInt()
      return if (lo == hi) RepSpec(RepKind.COUNT, lo, text = "$lo") else RepSpec(RepKind.RANGE, lo, hi, text = "$lo-$hi")
    }
    Regex("^(\\d{1,4})$").matchEntire(a)?.let { return RepSpec(RepKind.COUNT, it.groupValues[1].toInt(), text = it.groupValues[1]) }
    Regex("^(\\d{1,4}(?:\\.\\d)?)(?:\\s*-\\s*(\\d{1,4}))?\\s*($UNIT)$").matchEntire(a)?.let { m ->
      val v = m.groupValues[1].toDouble()
      val hi = m.groupValues[2].toIntOrNull()
      return when (val u = m.groupValues[3]) {
        "s", "sec", "secs", "second", "seconds", "\"" -> timeSpec(v, hi?.toDouble())
        "min", "mins", "minute", "minutes" -> timeSpec(v * 60, hi?.let { it * 60.0 })
        "m" -> if (v < 15) timeSpec(v * 60, hi?.let { it * 60.0 }) else distSpec(v, hi?.toDouble())
        "metre", "metres", "meter", "meters" -> distSpec(v, hi?.toDouble())
        "yd", "yds", "yard", "yards" -> RepSpec(RepKind.DISTANCE_M, (v * 0.9144).toInt(), text = "${v.toInt()}yd")
        "cal", "cals", "kcal" -> RepSpec(RepKind.CALORIES, v.toInt(), text = "${v.toInt()} cal")
        else -> RepSpec(RepKind.TEXT, text = "$v$u")
      }
    }
    return null
  }

  private fun timeSpec(sec: Double, hiSec: Double?): RepSpec =
    if (hiSec != null) RepSpec(RepKind.TIME_SEC, sec.toInt(), hiSec.toInt(), "${sec.toInt()}-${hiSec.toInt()}s")
    else RepSpec(RepKind.TIME_SEC, sec.toInt(), text = "${sec.toInt()}s")

  private fun distSpec(m: Double, hi: Double?): RepSpec =
    if (hi != null) RepSpec(RepKind.DISTANCE_M, m.toInt(), hi.toInt(), "${m.toInt()}-${hi.toInt()}m")
    else RepSpec(RepKind.DISTANCE_M, m.toInt(), text = "${m.toInt()}m")

  /**
   * A cell that should be a number but was read by OCR: "S" → 5, "l2" → 12, "B" → 8. Only applied
   * to a WHOLE cell in a numeric column, where no word could be meant.
   */
  fun cellDigits(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty() || t.length > 7 || !t.all { it.isDigit() || it in "lIoOSsBZzG-–" }) return raw
    if (t.all { it == '-' || it == '–' }) return raw
    // With real digits present, only the shapes that can't be a unit are repaired ("l2", "1O") —
    // "45s" is forty-five seconds, not 455. Letter-only cells ("S", "lO") are the OCR'd digits.
    return if (t.any { it.isDigit() }) {
      if (!t.all { it.isDigit() || it in "lIoO-–" }) raw
      else t.map { when (it) { 'l', 'I' -> '1'; 'o', 'O' -> '0'; else -> it } }.joinToString("")
    } else {
      t.map { when (it) { 'l', 'I' -> '1'; 'o', 'O' -> '0'; 'S', 's' -> '5'; 'B' -> '8'; 'Z', 'z' -> '2'; 'G' -> '6'; else -> it } }.joinToString("")
    }
  }

  /** "12/10/8", "12, 10, 8", "8", "8-12", "AMRAP" → (single, ladder). */
  fun repsField(raw: String): Pair<RepSpec?, List<RepSpec>> {
    val s = repairConfusables(clean(cellDigits(raw))).lowercase(Locale.ROOT)
      .replace(Regex("\\b(reps?|repetitions?)\\b"), "").replace(Regex("\\s*(each|per)\\s+(side|leg|arm)\\b|/\\s*(side|leg|arm)\\b"), "").trim()
    if (s.isEmpty()) return null to emptyList()
    val parts = s.split(Regex("\\s*[/,;]\\s*")).filter { it.isNotBlank() }
    if (parts.size > 1) {
      val specs = parts.map { repAtom(it) ?: return null to emptyList() }
      return null to specs
    }
    return repAtom(s) to emptyList()
  }

  /** "4", "3-4", "4 sets" → (sets, setsMax). */
  fun setsField(raw: String): Pair<Int?, Int?> {
    val s = repairConfusables(clean(cellDigits(raw))).lowercase(Locale.ROOT).replace(Regex("\\bsets?\\b"), "").trim()
    Regex("^(\\d{1,2})(?:\\s*-\\s*(\\d{1,2}))?$").matchEntire(s)?.let {
      return it.groupValues[1].toInt() to it.groupValues[2].toIntOrNull()
    }
    NUMBER_WORDS[s]?.let { return it to null }
    return null to null
  }

  // ── the line ─────────────────────────────────────────────────────────────────

  private data class Hit(val sets: Int?, val setsMax: Int?, val repsText: String?, val start: Int, val end: Int, val amrap: Boolean = false)

  /**
   * Read a line with BOTH grammars and keep the best of each. This core parser is the authority on
   * names, set counts and everyday rep notation; the specialist [LineGrammar] knows the rarer forms
   * (EMOM, for-time, clusters, rest-pause, myo-reps, per-set lists, calories, km, band and chain
   * loads, bodyweight-plus, tempo codes, "rest as needed"). Its reading is taken only where that is
   * safe: when this parser found nothing, when the two agree on the numbers (then its extra detail
   * is added), or when it recognised a special form with the same set count.
   */
  fun parseLine(raw: String): Line {
    val a = parseCore(raw)
    if (DAY_OR_WEEK.containsMatchIn(clean(raw))) return a
    // A line that is only a rest instruction ("rest 2-3 min") is not a timed exercise called Rest.
    if (a.rest != null && a.name.isBlank() && !a.hasRx) return a
    // A date is never a prescription ("Start date 2026-03-02" is not sets of 2026, 3 and 2).
    if (DATE.containsMatchIn(raw)) return a
    val g = try { LineGrammar.parse(clean(raw)) } catch (_: Exception) { null } ?: return a
    val gp = g.prescription
    if (gp.kind == SetKind.UNKNOWN) return a
    val gCount = gp.count ?: gp.perSet.size.takeIf { it > 0 }
    if (gCount == null && gp.reps == null) return a
    val special = gp.kind in SPECIAL_KINDS || gp.perSet.size >= 2 ||
      gp.reps?.kind in setOf(RepKind.CALORIES, RepKind.DISTANCE_M, RepKind.TIME_SEC, RepKind.AMRAP_MIN) ||
      g.modifiers.any { it in SPECIAL_MODIFIERS }
    val gRest = g.rest.takeIf { it.kind != RestKind.NONE_SPECIFIED }
    val richerLoad = gp.load?.takeIf { gl ->
      val al = a.load
      al == null || (al.unit == null && gl.unit != null) || gl.kind in RICH_LOADS
    }
    val gMods = g.modifiers.filter { it != LineGrammar.AMBIGUOUS_UNIT_MARKER }
    val enriched = a.copy(
      name = betterName(a.name, g.name),
      load = richerLoad ?: a.load,
      rest = if (a.rest?.lowSec != null) a.rest else gRest ?: a.rest,
      tempo = a.tempo.ifBlank { gp.tempo },
      modifiers = gMods.ifEmpty { a.modifiers },
      supersetGroup = a.supersetGroup.ifBlank { g.supersetGroup },
      supersetOrder = a.supersetOrder ?: g.supersetOrder
    )
    return when {
      // This parser found nothing; the specialist grammar found a real prescription.
      !a.hasRx && (special || gCount != null) && g.name.isNotBlank() ->
        enriched.copy(name = betterName(g.name, a.name), sets = gCount, reps = gp.reps, override = gp,
          coverage = maxOf(a.coverage, g.coverage))
      // Same numbers: keep ours, add its detail.
      a.hasSetsAndReps && gCount == a.sets && repsAgree(a, gp) ->
        if (gp.kind in SPECIAL_KINDS) enriched.copy(override = gp) else enriched
      // A special form it recognised with the same set count ("1x8+2+2" is rest-pause, not 8+).
      a.hasRx && gp.kind in SPECIAL_KINDS && (gCount == a.sets || a.sets == null) && g.coverage >= a.coverage - 0.05 ->
        enriched.copy(sets = gCount, reps = gp.reps, override = gp)
      else -> a.copy(
        tempo = a.tempo.ifBlank { gp.tempo },
        load = a.load ?: gp.load,
        rest = a.rest ?: gRest,
        supersetGroup = a.supersetGroup.ifBlank { g.supersetGroup },
        supersetOrder = a.supersetOrder ?: g.supersetOrder
      )
    }
  }

  private val DATE = Regex("\\b\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b|\\b\\d{1,2}[/.]\\d{1,2}[/.]\\d{2,4}\\b")

  private val DAY_OR_WEEK = Regex(
    "(?i)^\\s*(?:days?|weeks?|wks?|session|workout|phase|block)\\s*#?\\s*\\d{1,2}\\b(?!\\s*(?:x|sets?|reps?))" +
      "|^\\s*(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(?:day)?\\b\\s*[:\\-]?\\s*$")
  private val SPECIAL_KINDS = setOf(SetKind.EMOM, SetKind.AMRAP_TIME, SetKind.FOR_TIME, SetKind.CLUSTER,
    SetKind.RESTPAUSE, SetKind.MYO, SetKind.DROP, SetKind.PYRAMID, SetKind.LADDER, SetKind.COMPLEX, SetKind.CIRCUIT)
  private val SPECIAL_MODIFIERS = setOf(Modifier.EMOM, Modifier.FOR_TIME, Modifier.TOTAL_REPS, Modifier.AMRAP_IN_TIME,
    Modifier.CLUSTER, Modifier.REST_PAUSE, Modifier.MYO_REPS, Modifier.DROP_SET)
  private val RICH_LOADS = setOf(LoadKind.BODYWEIGHT_PLUS, LoadKind.BODYWEIGHT_MINUS, LoadKind.PCT_BODYWEIGHT,
    LoadKind.BAND, LoadKind.CHAIN, LoadKind.PCT_TM, LoadKind.PCT_E1RM, LoadKind.PCT_OF_TOP, LoadKind.RELATIVE)

  private fun repsAgree(a: Line, gp: SetPrescription): Boolean {
    if (a.ladder.isNotEmpty()) return gp.perSet.isEmpty() || gp.perSet.map { it.reps } == a.ladder
    val ar = a.reps ?: return gp.reps == null
    val gr = gp.reps ?: return gp.perSet.isNotEmpty()
    return ar.min == gr.min && ar.max == gr.max
  }

  /** The name that resolves to a known movement wins; otherwise ours (the cleaner of the two). */
  private fun betterName(ours: String, theirs: String): String {
    if (theirs.isBlank()) return ours
    if (ours.isBlank()) return theirs
    fun known(n: String) = com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex.find(n) != null
    return when {
      known(ours) -> ours
      known(theirs) -> theirs
      theirs.length < ours.length && ours.lowercase(Locale.ROOT).startsWith(theirs.lowercase(Locale.ROOT)) -> theirs
      else -> ours
    }
  }

  private fun parseCore(raw: String): Line {
    var s = numberWords(repairConfusables(clean(raw)))
      // "Lat Pulldown 3 (12)" is 3x12; a list in the brackets is a ladder the grammar reads.
      .replace(Regex("(?<![\\w.])(\\d{1,2})\\s*\\(\\s*(\\d{1,3}(?:\\s*-\\s*\\d{1,3})?)\\s*(?:reps?)?\\s*\\)"), "$1x$2")
    var ssGroup = ""
    var ssOrder: Int? = null

    // Leading list furniture: "1.", "2)", "-", "#3", and the superset tag "A1." / "B2)".
    while (true) {
      val before = s
      s = s.replace(Regex("^(?:[-*>+]+|\\d{1,2}[.)](?!\\d)|#\\d{1,2})\\s*"), "")
      Regex("^([A-Ha-h])\\s?([1-9])\\s*[.):-]?\\s+(?=\\S)").find(s)?.let { m ->
        ssGroup = m.groupValues[1].uppercase(); ssOrder = m.groupValues[2].toInt()
        s = s.substring(m.range.last + 1)
      }
      if (s == before) break
    }

    // Parentheticals are notes ("(1 sec pause)", "[each arm]") unless the prescription lives in them.
    val notes = ArrayList<String>()
    val parenRx = Regex("\\(([^()]*)\\)|\\[([^\\[\\]]*)]")
    val inParens = parenRx.findAll(s).map { (it.groupValues[1] + it.groupValues[2]).trim() }.toList()
    val outside = parenRx.replace(s, " ")
    val work = if (findHits(outside.lowercase(Locale.ROOT)).isEmpty() && inParens.any { findHits(it.lowercase(Locale.ROOT)).isNotEmpty() }) {
      // "Squat (3x5)" — the numbers are the prescription, not a note.
      val ns = inParens.filter { findHits(it.lowercase(Locale.ROOT)).isEmpty() }
      notes += ns
      parenRx.replace(s) { m -> val inner = m.groupValues[1] + m.groupValues[2]; if (findHits(inner.lowercase(Locale.ROOT)).isNotEmpty()) " $inner " else " " }
    } else {
      notes += inParens.filter { it.isNotBlank() }
      outside
    }

    val buf = StringBuilder(work)
    val lower = work.lowercase(Locale.ROOT)
    fun blank(range: IntRange) { for (k in range) if (k in buf.indices) buf.setCharAt(k, ' ') }
    fun lowerNow() = buf.toString().lowercase(Locale.ROOT)

    // Rest first: "rest 3 min" must never be read as a 3-minute set.
    var rest: RestSpec? = null
    for (r in REST) {
      val m = r.find(lowerNow()) ?: continue
      val unit = m.groupValues[3]
      // Minutes when it says so ("min", "m", "'"), or when a bare number is too small to be seconds.
      val clock = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(m.groupValues[1])
      val mult = when {
        clock != null -> 1
        unit.startsWith("m") || unit == "'" -> 60
        unit.isEmpty() && m.groupValues[1].toDouble() <= 5 -> 60
        else -> 1
      }
      val lo = if (clock != null) clock.groupValues[1].toInt() * 60 + clock.groupValues[2].toInt()
        else (m.groupValues[1].toDouble() * mult).toInt()
      val hi = m.groupValues[2].toDoubleOrNull()?.let { (it * mult).toInt() }
      rest = RestSpec(if (hi != null) RestKind.RANGE else RestKind.FIXED, lo, hi, m.value.trim())
      blank(m.range); break
    }

    // Tempo codes ("tempo 31X0") before the prescription, so "3-1-2-0" is not a ladder.
    var tempo = ""
    TEMPO.find(lowerNow())?.let { tempo = it.groupValues[1].uppercase(); blank(it.range) }

    // The prescription.
    val hits = findHits(lowerNow())
    val chosen: Hit? = run {
      if (hits.isEmpty()) return@run null
      val l = lowerNow()
      // "2x5 warm-up" (label after) or "warm-up: 2x5" (label directly before). A warm-up word that
      // ends a PREVIOUS clause ("2x5 warm-up, 3x5") does not make the next set a warm-up.
      fun isWarmup(h: Hit) = WARMUP.containsMatchIn(l.substring(h.end, minOf(l.length, h.end + 14)).substringBefore(',')) ||
        Regex("(warm\\s*-?\\s*ups?|wu|ramp(?:ing)?)\\s*[:\\-]?\\s*$").containsMatchIn(l.substring(maxOf(0, h.start - 14), h.start))
      hits.firstOrNull { !isWarmup(it) } ?: hits.first()
    }
    val warmupOnly = chosen != null && hits.all { h ->
      val l = lowerNow(); WARMUP.containsMatchIn(l.substring(h.end, minOf(l.length, h.end + 14))) }
    // Blank every hit: a warm-up prescription is not part of the name either.
    hits.forEach { blank(it.start until it.end) }
    // …and the warm-up words that labelled them.
    WARMUP.findAll(lowerNow()).forEach { blank(it.range) }
    Regex("\\bworking(?:\\s+sets?)?\\b").findAll(lowerNow()).forEach { blank(it.range) }

    var sets = chosen?.sets
    val setsMax = chosen?.setsMax
    var reps: RepSpec? = null
    var ladder: List<RepSpec> = emptyList()
    if (chosen?.amrap == true) reps = RepSpec(RepKind.AMRAP, text = "AMRAP")
    chosen?.repsText?.let { rt ->
      val (single, lad) = repsField(rt)
      reps = single; ladder = lad
      if (sets != null && lad.isNotEmpty() && lad.size != sets) {
        // "4 x 8/6/4/2" — the ladder is the per-set truth; a mismatched count is kept as stated.
      }
    }

    // Loads. Chains and bands first, so their weight is not read as the bar weight. They are
    // resistance ON TOP of the main load ("@ 60% + red band"), so they become a modifier, and the
    // load itself only when the line states no other.
    var load: LoadSpec? = null
    var accommodating: LoadSpec? = null
    val extraModifiers = ArrayList<String>()
    CHAIN_BAND.find(lowerNow())?.let { m ->
      val chain = m.groupValues[4].startsWith("chain")
      val unit = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { if (it.startsWith("k")) WeightUnit.KG else WeightUnit.LB }
      accommodating = LoadSpec(if (chain) LoadKind.CHAIN else LoadKind.BAND, m.groupValues[1].toDoubleOrNull(), unit = unit, text = m.value.trim())
      extraModifiers += if (chain) Modifier.CHAIN else Modifier.BAND
      blank(m.range)
    }
    LOAD_RPE.find(lowerNow())?.let { m ->
      load = LoadSpec(LoadKind.RPE, m.groupValues[1].toDouble(), m.groupValues[2].toDoubleOrNull(), text = m.value.trim()); blank(m.range)
    }
    LOAD_RIR.find(lowerNow())?.let { m ->
      val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toDouble()
      if (load == null) load = LoadSpec(LoadKind.RIR, v, text = m.value.trim()); blank(m.range)
    }
    LOAD_PCT.find(lowerNow())?.let { m ->
      val first = m.groupValues[1].toDouble()
      val rest2 = Regex("\\d{1,3}(?:\\.\\d)?").findAll(m.groupValues[2]).map { it.value.toDouble() }.toList()
      val kind = when (m.groupValues[3].replace(" ", "")) { "tm", "trainingmax" -> LoadKind.PCT_TM; "e1rm" -> LoadKind.PCT_E1RM; else -> LoadKind.PCT_1RM }
      if (load == null) load = LoadSpec(kind, first, rest2.lastOrNull(), text = m.value.trim())
      blank(m.range)
    }
    LOAD_ABS.find(lowerNow())?.let { m ->
      val unit = if (m.groupValues[3].startsWith("k")) WeightUnit.KG else WeightUnit.LB
      if (load == null) load = LoadSpec(LoadKind.ABSOLUTE, m.groupValues[1].toDouble(), m.groupValues[2].toDoubleOrNull(), unit, m.value.trim())
      blank(m.range)
    }
    LOAD_RPE_AT.find(lowerNow())?.let { m ->
      // "@ 8" with no unit after a prescription is an RPE target in coach shorthand.
      val v = m.groupValues[1].toDouble()
      if (v in 5.0..10.0 && load == null && chosen != null) { load = LoadSpec(LoadKind.RPE, v, text = m.value.trim()); blank(m.range) }
    }
    LOAD_BW.find(lowerNow())?.let { m ->
      if (load == null) load = LoadSpec(LoadKind.BODYWEIGHT, text = "bodyweight"); blank(m.range)
    }

    if (load == null) load = accommodating

    // Each-side and other modifiers.
    val modifiers = ArrayList<String>(extraModifiers)
    EACH_SIDE.findAll(lowerNow()).toList().let { ms -> if (ms.isNotEmpty()) modifiers += Modifier.EACH_SIDE; ms.forEach { blank(it.range) } }
    if (chosen?.repsText?.contains("fail") == true || Regex("\\bto\\s+failure\\b").containsMatchIn(lower)) modifiers += Modifier.TO_FAILURE

    // Sets-only / reps-only / time-only forms when nothing fuller matched.
    if (chosen == null) {
      val l = lowerNow()
      SETS_ONLY.find(l)?.let { m -> sets = m.groupValues[1].toInt(); blank(m.range) }
      REPS_ONLY.find(lowerNow())?.let { m -> val (single, lad) = repsField(m.groupValues[1]); reps = single; ladder = lad; blank(m.range) }
      if (reps == null && ladder.isEmpty()) TIME_ONLY.find(lowerNow())?.let { m -> reps = repAtom(m.groupValues[1]); blank(m.range) }
    }

    // A bare "@ 100" is a weight with no unit: read it as a load (the review asks kg or lb), never
    // as part of the name.
    Regex("@\\s*(\\d{2,4}(?:\\.\\d{1,2})?)(?![\\d%.])").find(lowerNow())?.let { m ->
      if (load == null) { load = LoadSpec(LoadKind.ABSOLUTE, m.groupValues[1].toDouble(), text = m.value.trim()); blank(m.range) }
    }

    // What is left is the name — unless it carries a recognisable movement followed by text that is
    // clearly not part of a name (symbols, numbers, a run of extra words). That tail is kept as a
    // note and counted as UNREAD, so a half-understood line is flagged rather than imported as a
    // movement called "Bench Press ~~~ zone 4 protocol".
    var name = tidyName(buf.toString())
    var unread = ""
    splitTrailingNoise(name)?.let { (movement, tail) -> name = movement; unread = tail }
    val nonSpace = raw.count { !it.isWhitespace() }.coerceAtLeast(1)
    val coverage = 1.0 - unread.count { !it.isWhitespace() }.toDouble() / nonSpace
    if (unread.isNotBlank()) notes += unread
    return Line(
      raw = raw.trim(),
      coverage = coverage.coerceIn(0.0, 1.0),
      name = name,
      sets = sets, setsMax = setsMax,
      reps = reps, ladder = ladder,
      load = load, rest = rest, tempo = tempo,
      modifiers = modifiers.distinct(),
      note = notes.joinToString("; "),
      supersetGroup = ssGroup, supersetOrder = ssOrder,
      warmupOnly = warmupOnly && hits.size == 1
    )
  }

  private fun findHits(l: String): List<Hit> {
    val hits = ArrayList<Hit>()
    fun overlaps(r: IntRange) = hits.any { it.start <= r.last && r.first < it.end }
    SETS_TO_FAILURE.findAll(l).forEach { m ->
      if (!overlaps(m.range)) hits += Hit(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull(), null, m.range.first, m.range.last + 1, amrap = true)
    }
    SETS_OF_REPS.findAll(l).forEach { m ->
      if (!overlaps(m.range)) hits += Hit(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull(), m.groupValues[3], m.range.first, m.range.last + 1)
    }
    SETS_X_REPS.findAll(l).forEach { m ->
      if (!overlaps(m.range)) hits += Hit(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull(), m.groupValues[3], m.range.first, m.range.last + 1)
    }
    REPS_THEN_SETS.findAll(l).forEach { m ->
      if (!overlaps(m.range)) hits += Hit(m.groupValues[2].toInt(), null, m.groupValues[1], m.range.first, m.range.last + 1)
    }
    val ls = LABEL_SETS.find(l)
    val lr = LABEL_REPS.find(l)
    if (ls != null && lr != null && !overlaps(ls.range) && !overlaps(lr.range)) {
      hits += Hit(ls.groupValues[1].toInt(), ls.groupValues[2].toIntOrNull(), lr.groupValues[1],
        minOf(ls.range.first, lr.range.first), maxOf(ls.range.last, lr.range.last) + 1)
      // The text between the two labels ("," / "|") goes with them.
    }
    return hits.sortedBy { it.start }
  }

  private val FILLER_LEAD = Regex("^(?:(?:and|then|do|finish(?:\\s+off)?\\s+with|followed\\s+by|superset(?:ted)?\\s+with|plus|also|next|after\\s+that|start\\s+with|begin\\s+with|a\\s+few|perform)\\b\\s*)+", RegexOption.IGNORE_CASE)
  private val FILLER_TAIL = Regex("(?:\\s+\\b(?:for|with|then|and|at|of|x|reps?|sets?|on|each|total|working)\\b)+$", RegexOption.IGNORE_CASE)

  /** What is left of the line once every recognised token is gone, tidied into a name. */
  fun tidyName(left: String): String {
    var n = left.replace(Regex("\\s+"), " ").trim()
    repeat(3) {
      n = n.trim().trim('-', ':', '|', ',', ';', '.', '@', '/', '=', '~', '*', '>', ' ')
      n = FILLER_LEAD.replace(n, "")
      n = FILLER_TAIL.replace(n, "")
      n = n.replace(Regex("\\s+(?:-|:|\\|)\\s*$"), "")
      n = n.replace(Regex("\\s*\\b(?:x|@)\\s*$", RegexOption.IGNORE_CASE), "")
    }
    n = n.replace(Regex("\\s*[-:|,]\\s*[-:|,]\\s*"), " - ").replace(Regex("\\s+"), " ").trim()
    n = n.trim('-', ':', '|', ',', ';', ' ')
    // Spreadsheet style "Bench Press, paused" / "Fly, cable" → "Paused Bench Press" / "Cable Fly".
    Regex("^([^,]{3,}),\\s*([A-Za-z][A-Za-z-]*(?:\\s[A-Za-z-]+)?)$").matchEntire(n)?.let { m ->
      if (QUALIFIERS.matches(m.groupValues[2].lowercase(Locale.ROOT))) n = "${m.groupValues[2]} ${m.groupValues[1]}"
    }
    return n
  }

  /**
   * "Bench Press ~~~ zone 4 protocol" → ("Bench Press", "~~~ zone 4 protocol"). Only splits when the
   * front of the name is a KNOWN movement and the tail is plainly not part of a name: it has
   * symbols or digits, or it is a run of three or more extra words. "Bench Press Close Grip" stays.
   */
  private fun splitTrailingNoise(name: String): Pair<String, String>? {
    val words = name.split(' ').filter { it.isNotBlank() }
    if (words.size < 2) return null
    if (com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex.find(name) != null) return null
    for (k in words.size - 1 downTo 1) {
      val head = words.take(k).joinToString(" ")
      if (com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex.find(head) == null) continue
      val tail = words.drop(k).joinToString(" ")
      val noisy = tail.any { !it.isLetter() && it != ' ' && it != '-' && it != '\'' } || words.size - k >= 4
      return if (noisy) head to tail else null
    }
    return null
  }

  private val QUALIFIERS = Regex("(paused?|tempo|cable|dumbbell|db|barbell|bb|machine|smith|kettlebell|kb|incline|decline|flat|seated|standing|close\\s+grip|wide\\s+grip|single\\s+arm|single\\s+leg|banded|weighted|deficit|front|reverse|lying|hammer|ez\\s+bar|trap\\s+bar|hex\\s+bar|landmine|band)")
}

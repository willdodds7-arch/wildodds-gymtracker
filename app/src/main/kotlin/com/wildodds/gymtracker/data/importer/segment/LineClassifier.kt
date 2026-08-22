package com.wildodds.gymtracker.data.importer.segment

import com.wildodds.gymtracker.data.ExerciseLibrary
import com.wildodds.gymtracker.data.importer.SetNotationParser
import com.wildodds.gymtracker.data.importer.TextNormalizer
import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary

/** What a line is doing in a written program. */
enum class LineRole {
  PROGRAM_TITLE,
  PHASE_HEADER,
  WEEK_HEADER,
  SESSION_HEADER,
  EXERCISE,
  /** A continuation of the exercise above: "  - set 1: 5 @ 70%". */
  SET_DETAIL_CONTINUATION,
  /** "Warm-up", "Accessories", "Cool down" — a section within a session. */
  SECTION_LABEL,
  NOTE,
  REST_DAY,
  IGNORE
}

/**
 * The observable properties of one line. Extracted once, scored many times.
 *
 * Everything the previous keyword-matching importer did well survives here as a FEATURE rather than
 * as the algorithm: `Week N`, `Day N`, split keywords, weekday names and the title-vs-header
 * lookahead are all still consulted — they are just evidence now, weighed against everything else,
 * instead of a first-match decision that could not be overruled.
 */
data class LineFeatures(
  val index: Int,
  val text: String,
  val indent: Int,
  val words: Int,
  val isAllCaps: Boolean,
  val endsWithColon: Boolean,
  val startsWithNumbering: Boolean,
  val hasPrescription: Boolean,
  val notationCoverage: Double,
  val resolvesAsExercise: Boolean,
  val weekNumbers: List<Int>,
  val phaseNumber: Int?,
  val dayLabel: String?,
  val weekday: String?,
  val splitKeyword: String?,
  val sectionLabel: String?,
  val isRestDayWord: Boolean,
  val looksLikeProse: Boolean,
  /** "Rest 2-3 minutes between working sets." — an instruction about rest, not an exercise. */
  val isRestInstruction: Boolean,
  /** A sentence, not a name: ends in a full stop and is long enough to mean it. */
  val endsLikeSentence: Boolean,
  /**
   * True for an UNAMBIGUOUS header form: a week, a phase, a numbered day, or a weekday.
   *
   * Deliberately NOT the split-keyword heuristic. "Upper/Lower Split - 4 Days" starts with a split
   * keyword and is a document title; "Upper Body" starts with one and is a session. That ambiguity
   * is what the title lookahead exists to resolve, so folding it in here would pre-empt the very
   * decision the segmenter is meant to make.
   */
  val isStrongHeaderForm: Boolean,
  /** The `A` of "Block A" — a phase can be lettered as easily as numbered. */
  val phaseLabel: String?,
  val repeatCount: Int,
  val blankBefore: Boolean,
  val blankAfter: Boolean,
  /** The line below, when there is one — the title-vs-header lookahead needs it. */
  val nextHasPrescription: Boolean,
  val nextIsHeaderish: Boolean
)

/**
 * Scores each line against every [LineRole], producing evidence for [Segmenter]'s dynamic-programming
 * pass rather than a decision of its own.
 *
 * Scores are in [0,1] and are read as "how well does this line look like this role" — they are not
 * probabilities and are not normalised, because normalising them would let a line with no evidence
 * at all still make a confident-looking claim.
 */
object LineClassifier {

  private val vocab = ReaderVocabulary

  private val WEEK = Regex("""(?i)^(week|wk|woche|semana|semaine)s?\s*([\d\s,\-–and]+)\b(.*)$""")
  private val WEEK_COMPACT = Regex("""(?i)^w(\d{1,2})\s*[/\-]?\s*d(\d{1,2})\b(.*)$""")
  private val PHASE = Regex("""(?i)^(phase|block|mesocycle|meso|cycle|wave)\s*([\dA-Za-z]{1,3})\b(.*)$""")
  private val DAY = Regex("""(?i)^(day|session|workout|training\s*day|tag|dia|jour)\s*([\dA-Za-z]{1,3})\b[:.\-\s]*(.*)$""")
  private val NUMBERING = Regex("""^\s*(\d{1,2}[.)]|[a-h][.)])\s+""")

  /** A tab counts as four columns, which is what every editor a coach uses renders it as. */
  private fun countIndent(raw: String): Int {
    var n = 0
    for (c in raw) {
      if (c == ' ') n += 1 else if (c == '	') n += 4 else break
    }
    return n
  }

  fun features(lines: List<String>): List<LineFeatures> {
    val counts = lines.map { it.trim() }.groupingBy { it }.eachCount()
    return lines.mapIndexed { i, raw ->
      val text = raw.trim()
      // The lookahead skips BLANK lines. A markdown document puts a blank line between a heading
      // and its content, and looking only at the immediately-next line made every heading look as
      // though nothing followed it — which is what turned a document title into a phantom session.
      val next = (i + 1 until lines.size).asSequence()
        .map { lines[it].trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
      val hasPrescription = SetNotationParser.hasPrescription(text)
      val headerish = isHeaderish(text)
      val words = if (text.isBlank()) 0 else text.split(Regex("""\s+""")).size
      LineFeatures(
        index = i,
        text = text,
        indent = countIndent(raw),
        words = words,
        isAllCaps = text.any { it.isLetter() } && text.filter { it.isLetter() }.all { it.isUpperCase() },
        endsWithColon = text.endsWith(":"),
        startsWithNumbering = NUMBERING.containsMatchIn(raw),
        hasPrescription = hasPrescription,
        notationCoverage = if (text.isBlank()) 0.0 else SetNotationParser.coverageOf(text),
        // An exercise NAME is short, and a line that reads as a header is not one however many
        // movement words it happens to contain. Without both guards, "Day 2 - Bench" resolves as an
        // exercise (it contains "bench") and competes with the session header it actually is.
        resolvesAsExercise = text.isNotBlank() && !headerish && words <= MAX_NAME_WORDS &&
          ExerciseLibrary.lookup(stripLeading(text)) != null,
        weekNumbers = weekNumbersIn(text),
        phaseNumber = PHASE.matchEntire(text)?.groupValues?.get(2)?.toIntOrNull(),
        phaseLabel = PHASE.matchEntire(text)?.groupValues?.get(2),
        isStrongHeaderForm = WEEK.matchEntire(text) != null || WEEK_COMPACT.matchEntire(text) != null ||
          PHASE.matchEntire(text) != null || DAY.matchEntire(text) != null ||
          (firstWordWeekday(text) != null && !hasPrescription),
        dayLabel = DAY.matchEntire(text)?.groupValues?.get(2),
        weekday = firstWordWeekday(text),
        splitKeyword = splitKeywordIn(text),
        sectionLabel = vocab.sectionFor(text),
        isRestDayWord = vocab.isRestDayLabel(text) || restDayTail(text),
        looksLikeProse = TextNormalizer.isProse(text, hasPrescription),
        isRestInstruction = isRestInstruction(text),
        endsLikeSentence = words >= 5 && text.endsWith(".") && !text.endsWith("..."),
        repeatCount = counts[text] ?: 1,
        blankBefore = i > 0 && lines[i - 1].isBlank(),
        blankAfter = i + 1 < lines.size && lines[i + 1].isBlank(),
        nextHasPrescription = next.isNotBlank() && SetNotationParser.hasPrescription(next),
        nextIsHeaderish = next.isNotBlank() && isHeaderish(next)
      )
    }
  }

  /**
   * `Week 1`, `Week 1-4`, `Weeks 1,3,5`, `W1D3`. A RANGE is expanded, because "Weeks 1-4" means the
   * block that follows runs for four weeks, and reading only the 1 loses three quarters of it.
   */
  fun weekNumbersIn(text: String): List<Int> {
    WEEK_COMPACT.matchEntire(text)?.let { return listOf(it.groupValues[1].toInt()) }
    val m = WEEK.matchEntire(text) ?: return emptyList()
    val spec = m.groupValues[2]
    val out = LinkedHashSet<Int>()
    // "1-4" or "1 to 4" is a range; "1,3,5" is a list; both appear constantly.
    Regex("""(\d{1,2})\s*[-–]\s*(\d{1,2})""").findAll(spec).forEach { r ->
      val a = r.groupValues[1].toInt(); val b = r.groupValues[2].toInt()
      if (b in a..(a + 51)) out += (a..b) else out += a
    }
    if (out.isEmpty()) Regex("""\d{1,2}""").findAll(spec).forEach { out += it.value.toInt() }
    return out.toList()
  }

  /** The `D3` of `W1D3`, when present. */
  fun compactDayIn(text: String): Int? =
    WEEK_COMPACT.matchEntire(text)?.groupValues?.get(2)?.toIntOrNull()

  private fun firstWordWeekday(text: String): String? {
    val first = text.substringBefore(' ').trim(':', '-', '.', ',')
    return vocab.weekdayFor(first)
  }

  private fun splitKeywordIn(text: String): String? {
    val head = vocab.lower(text).trim(':', '-', '.', ' ')
    return vocab.splitKeywords.firstOrNull { head == it || head.startsWith("$it ") || head.startsWith("$it/") }
  }

  /** "Wed — REST", "Saturday: off" — the rest marker is in the TAIL, not the whole line. */
  private fun restDayTail(text: String): Boolean {
    val tail = text.substringAfter(':', text.substringAfter('-', "")).trim()
    return tail.isNotBlank() && vocab.isRestDayLabel(tail)
  }

  private fun stripLeading(text: String) = text.replace(NUMBERING, "").trim()

  /** The longest plausible exercise name, in words. "Single Arm Dumbbell Overhead Press" is five. */
  private const val MAX_NAME_WORDS = 8

  /**
   * "Rest 2-3 minutes between working sets." parses beautifully as a timed exercise called
   * "Rest 2-3", which is exactly the kind of confident wrong answer this subsystem must not give.
   * A line whose FIRST word is a rest word and which names no movement is an instruction.
   */
  private fun isRestInstruction(text: String): Boolean {
    val first = vocab.lower(text.substringBefore(' ')).trim(':', '.', ',', '-')
    if (first !in vocab.restWords) return false
    return ExerciseLibrary.lookup(text) == null
  }

  private fun isHeaderish(line: String): Boolean {
    val f = line.trim()
    return WEEK.matchEntire(f) != null || DAY.matchEntire(f) != null ||
      PHASE.matchEntire(f) != null || WEEK_COMPACT.matchEntire(f) != null ||
      (firstWordWeekday(f) != null && !SetNotationParser.hasPrescription(f)) ||
      (splitKeywordIn(f) != null && !SetNotationParser.hasPrescription(f))
  }

  // ── scoring ──────────────────────────────────────────────────────────────────

  /** How well [f] fits each role. Absent roles score 0 and are effectively impossible. */
  fun score(f: LineFeatures, total: Int): Map<LineRole, Double> {
    if (f.text.isBlank()) return mapOf(LineRole.IGNORE to 1.0)
    val s = EnumMapOf()

    // EXERCISE — the strongest single signal in the whole document is a prescription.
    if (f.hasPrescription) s[LineRole.EXERCISE] = 0.95 * f.notationCoverage.coerceAtLeast(0.5)
    if (f.resolvesAsExercise) s.add(LineRole.EXERCISE, 0.55)
    if (f.startsWithNumbering && f.words <= 12) s.add(LineRole.EXERCISE, 0.15)
    if (!f.hasPrescription && !f.resolvesAsExercise && f.words in 1..7) s.add(LineRole.EXERCISE, 0.25)

    // WEEK — an explicit week header.
    //
    // "Weeks 1,3,5" parses beautifully as a comma-separated per-set list, which is exactly the kind
    // of confident wrong answer to guard against: a line that NAMES weeks and contains no movement
    // is a week header whatever the notation grammar makes of its numbers. A line that names a week
    // AND a movement ("Week 1: Bench 3x5") really is an exercise line.
    if (f.weekNumbers.isNotEmpty()) {
      s[LineRole.WEEK_HEADER] = if (f.resolvesAsExercise) 0.2 else 0.95
      if (!f.resolvesAsExercise) s.scale(LineRole.EXERCISE, 0.1)
    }

    // PHASE — sits above weeks. Lettered as often as numbered: "Block A", "Phase 2", "Meso 1".
    if (f.phaseLabel != null && !f.hasPrescription) s[LineRole.PHASE_HEADER] = 0.9

    // SESSION — "Day 2", "Mon", "Upper A", "Session 1 (Push)".
    var session = 0.0
    if (f.dayLabel != null) session = maxOf(session, 0.9)
    if (f.weekday != null) session = maxOf(session, 0.8)
    if (f.splitKeyword != null) session = maxOf(session, 0.7)
    if (f.isAllCaps && f.words <= 5) session = maxOf(session, 0.45)
    if (f.endsWithColon && f.words <= 5) session += 0.15
    if (f.blankBefore) session += 0.05
    // A full exercise line must never be swallowed by a session keyword — the "Pull Up" bug.
    if (f.hasPrescription) session *= 0.15
    // A session header is SHORT. "Squat & Overhead Press - 3 Days per Week" starts with a split
    // keyword and is a document title; length is what tells the two apart.
    if (f.words > 6) session *= 0.3
    // "Back Squat" starts with a split keyword too, and is an exercise. A name the library knows is
    // much stronger evidence than a keyword prefix.
    if (f.resolvesAsExercise) session *= 0.4
    // "Day 2 - Rest" is a day, but it is a REST day, and that is a different role.
    if (f.isRestDayWord) session *= 0.4
    if (session > 0) s[LineRole.SESSION_HEADER] = session.coerceAtMost(1.0)

    // REST DAY — its own role, because a rest day is part of the program, not an absence of one.
    if (f.isRestDayWord && !f.hasPrescription) s[LineRole.REST_DAY] = 0.95

    // SECTION LABEL — "Warm-up", "Accessories".
    if (f.sectionLabel != null && !f.hasPrescription) s[LineRole.SECTION_LABEL] = 0.85

    // TITLE — only near the top, and only when what FOLLOWS is a header. That lookahead is the
    // hard-won rule that stops "Upper/Lower Split - 4 Days" becoming a phantom empty first day.
    //
    // A line that names a week, a phase, a day or a weekday is that thing, not the title, even when
    // it opens the document — "Weeks 1-4" as the first line is extremely common and it is a week
    // header. And a line that reads as a sentence is a note, not a title.
    // A title can legitimately be eight words long ("Squat & Overhead Press - 3 Days per Week"), so
    // length alone must not disqualify it. What disqualifies it is being a header form, naming a
    // week or a day, or reading as a finished sentence.
    val couldBeTitle = f.weekNumbers.isEmpty() && f.phaseLabel == null && f.dayLabel == null &&
      f.weekday == null && !f.isRestDayWord && !f.isStrongHeaderForm && !f.endsLikeSentence
    if (f.index <= 2 && !f.hasPrescription && f.words in 2..14 && couldBeTitle) {
      s[LineRole.PROGRAM_TITLE] = if (f.nextIsHeaderish || f.nextHasPrescription) 0.9 else 0.55
    }

    // CONTINUATION — indented, or a "set 2:" style line, under an exercise.
    if (f.indent >= 2 && !f.resolvesAsExercise) s[LineRole.SET_DETAIL_CONTINUATION] = 0.5
    if (Regex("""(?i)^\s*set\s*\d""").containsMatchIn(f.text)) s[LineRole.SET_DETAIL_CONTINUATION] = 0.85

    // NOTE — prose, or anything long.
    if (f.looksLikeProse) s[LineRole.NOTE] = 0.8
    if (f.words > 12) s.add(LineRole.NOTE, 0.3)
    // An instruction about rest, and a line that ends like a sentence, are both prose EVEN WHEN
    // they parse as a prescription. "Rest 2-3 minutes between working sets." is the canonical case:
    // it reads perfectly as a timed exercise called "Rest 2-3", and importing it as one is worse
    // than dropping it.
    if (f.isRestInstruction) {
      s[LineRole.NOTE] = 0.9
      s.scale(LineRole.EXERCISE, 0.15)
    }
    if (f.endsLikeSentence) {
      s.add(LineRole.NOTE, 0.35)
      s.scale(LineRole.EXERCISE, 0.4)
    }

    // IGNORE — page furniture and boilerplate that repeats across the document.
    if (f.repeatCount >= 3 && !f.hasPrescription) s[LineRole.IGNORE] = 0.7
    if (f.text.length <= 2) s[LineRole.IGNORE] = 0.6

    // Everything gets a floor, so a line with no evidence is still assignable rather than a hole.
    s.add(LineRole.NOTE, 0.05)
    return s.map
  }

  /**
   * A tiny mutable helper — clearer at the call sites than repeated `merge` calls.
   *
   * [set] raises a score and never lowers it, so rules can be written in any order without one
   * quietly undoing another. Lowering is [scale], which is explicit precisely because a suppression
   * IS order-dependent: it can only suppress evidence that has already been recorded.
   */
  private class EnumMapOf {
    val map = LinkedHashMap<LineRole, Double>()
    operator fun get(role: LineRole): Double = map[role] ?: 0.0
    operator fun set(role: LineRole, v: Double) { map[role] = maxOf(map[role] ?: 0.0, v) }
    fun add(role: LineRole, v: Double) { map[role] = ((map[role] ?: 0.0) + v).coerceAtMost(1.0) }
    fun scale(role: LineRole, factor: Double) { map[role]?.let { map[role] = it * factor } }
  }
}

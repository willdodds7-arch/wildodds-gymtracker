package com.wildodds.gymtracker.data.importer.grammar

import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary

/**
 * The tokeniser for one written exercise line.
 *
 * Why a lexer at all: the previous parser was eleven ordered regexes over the raw string, and every
 * new notation risked reordering them — "add the cluster pattern before the sets pattern or `3x(3.3.3)`
 * breaks, but after the tempo pattern or `31X0` breaks". Ordering bugs are invisible until a real
 * program hits them. With an explicit token set, ordering becomes a grammar rule that can be read,
 * argued about and tested, instead of a comment about regex precedence.
 *
 * Every token carries its **source span**, which is what makes two things possible downstream: the
 * review screen can highlight exactly which characters were understood, and the confidence model can
 * measure the fraction of the line that no token consumed — the single best signal that the parser
 * missed something.
 *
 * Pure, locale-independent, and deterministic: same string in, same tokens out, on every device.
 */
enum class TokenType {
  /** A bare number: `3`, `8`, `2.5`, `1.5`. */
  NUMBER,
  /** The sets x reps operator: `x`, `X`, `×`, `*`, or the words `sets of`. */
  X,
  /** A range separator between two numbers: `-`, `–`, `to`. */
  RANGE,
  /** `@` — introduces a load. */
  AT,
  /** `%`, optionally qualified: `%1RM`, `% TM`, `%e1RM`, `%BW`. */
  PERCENT,
  /** `RPE` (the word). */
  RPE,
  /** `RIR` (the word). */
  RIR,
  /** A weight unit word: kg / lb / # / pood. */
  WEIGHT_UNIT,
  /** A time unit word: s / sec / min / hr. */
  TIME_UNIT,
  /** A distance unit word: m / km / mi / yd. */
  DISTANCE_UNIT,
  /** `cal` / `calories`. */
  CALORIE_UNIT,
  /** `reps` / `rep` — an explicit rep-count marker. */
  REPS_WORD,
  /** `sets` / `set`. */
  SETS_WORD,
  /** A 4-slot tempo: `31X0`, `3-1-2-0`, `3010`. */
  TEMPO,
  /** A clock time: `1:30`, `2:00`. */
  CLOCK,
  /** `rest` and its synonyms. */
  REST,
  /** A superset tag at the start of a line: `A1)`, `B2.`, `A -`. */
  SUPERSET_TAG,
  /** A recognised modifier phrase, canonicalised in [Token.value]. */
  MODIFIER,
  /** `AMRAP` and its synonyms. */
  AMRAP,
  /** `EMOM` / `E2MOM` / `OTM`. */
  EMOM,
  /** `bodyweight` / `BW`. */
  BODYWEIGHT,
  /** A band or chain label. */
  RESISTANCE,
  /** `+` — AMRAP marker after a rep count, or an additive load. */
  PLUS,
  /** `-` used as a subtraction (assisted work), not a range. */
  MINUS,
  /** `/` — per-set separator or "per". */
  SLASH,
  /** `,` — per-set separator. */
  COMMA,
  /** `(` `)`. */
  LPAREN, RPAREN,
  /** Any other word: part of the exercise name, or prose. */
  WORD,
  /** Punctuation the grammar ignores but the span accounting must still see. */
  PUNCT
}

/**
 * One token. [start] and [end] are indices into the ORIGINAL line, so highlighting and coverage
 * measurement are exact rather than approximate.
 */
data class Token(
  val type: TokenType,
  val text: String,
  val start: Int,
  val end: Int,
  /** Canonicalised payload where it differs from [text] — a modifier name, a unit key. */
  val value: String = ""
) {
  val number: Double? get() = text.replace(',', '.').toDoubleOrNull()
  val length: Int get() = end - start
}

object Lexer {

  private val vocab = ReaderVocabulary

  /** Unit word -> token type, built once from the vocabulary. */
  private val unitTokens: Map<String, TokenType> by lazy {
    val m = HashMap<String, TokenType>()
    vocab.units["kg"].orEmpty().forEach { m[it] = TokenType.WEIGHT_UNIT }
    vocab.units["lb"].orEmpty().forEach { m[it] = TokenType.WEIGHT_UNIT }
    vocab.units["pood"].orEmpty().forEach { m[it] = TokenType.WEIGHT_UNIT }
    vocab.units["seconds"].orEmpty().forEach { m[it] = TokenType.TIME_UNIT }
    vocab.units["minutes"].orEmpty().forEach { m[it] = TokenType.TIME_UNIT }
    vocab.units["hours"].orEmpty().forEach { m[it] = TokenType.TIME_UNIT }
    // Distance wins for the shared forms it owns outright; "m" stays a TIME_UNIT here and the
    // parser disambiguates it by position, because "3x30m" and "rest 3m" are both common.
    vocab.units["km"].orEmpty().forEach { m[it] = TokenType.DISTANCE_UNIT }
    vocab.units["miles"].orEmpty().forEach { m[it] = TokenType.DISTANCE_UNIT }
    vocab.units["yards"].orEmpty().forEach { m[it] = TokenType.DISTANCE_UNIT }
    vocab.units["metres"].orEmpty().filter { it != "m" }.forEach { m[it] = TokenType.DISTANCE_UNIT }
    vocab.units["calories"].orEmpty().forEach { m[it] = TokenType.CALORIE_UNIT }
    m
  }

  private val restWords: Set<String> by lazy { vocab.restWords.toSet() }

  /** Single-word modifier surface forms, resolved to their canonical name. */
  private val singleWordModifiers: Map<String, String> by lazy {
    vocab.modifierPatterns.filter { ' ' !in it.first && '/' !in it.first }.toMap()
  }

  private fun singleWordModifier(lower: String): String? = singleWordModifiers[lower]

  /**
   * True when the `.` or `,` at [i] is a decimal point rather than a separator.
   *
   * "2,5" and "2.5" are one number; "1,2,3,4,5" is a ladder and "3.3.3" is a cluster. The tell is
   * what comes after the fractional digits: another separator means this was a LIST, and reading it
   * as one number is how "1,2,3,4,5 x 5 rounds" became a single unparseable token.
   */
  private fun isDecimalSeparator(line: String, i: Int): Boolean {
    if (i == 0 || !line[i - 1].isDigit()) return false
    if (line.getOrNull(i + 1)?.isDigit() != true) return false

    // Look at the WHOLE run of digits and separators around this position. One separator is a
    // decimal point; two or more mean this is a list, and every separator in it is a separator.
    var lo = i
    while (lo > 0 && (line[lo - 1].isDigit() || line[lo - 1] == '.' || line[lo - 1] == ',')) lo--
    var hi = i
    while (hi < line.length && (line[hi].isDigit() || line[hi] == '.' || line[hi] == ',')) hi++
    val run = line.substring(lo, hi).trimEnd('.', ',')
    val separators = run.count { it == '.' || it == ',' }
    if (separators != 1) return false

    // A European decimal has one to three fractional digits (2,5 / 22,25 / 1.125); a rep scheme
    // never does, and a four-digit tail is a thousands separator rather than a fraction.
    val fractionDigits = run.substringAfterLast(if (run.contains(',')) ',' else '.').length
    return fractionDigits in 1..3
  }

  /** `EMOM`, `E2MOM`, `E3MOM`, `OTM` — one token whatever digits are inside it. */
  private val EMOM_FAMILY = Regex("""^(e\d*mom|otm)\b""", RegexOption.IGNORE_CASE)

  /**
   * A superset tag: a single letter, an optional digit, then a separator — `A1)`, `B2.`, `A -`.
   * Anchored to the start of the line, because a bare "A" mid-line is far more likely to be a word.
   */
  private val SUPERSET = Regex("""^\s*([A-Ha-h])\s*([1-9])?\s*[.)\-:]\s+""")

  /**
   * `31X0` and `3-1-2-0`. NOT a bare `3010`: a four-digit number is far more often a load, a year
   * or a distance than a tempo, so the plain form is only accepted after the word "tempo", which
   * [LineGrammar] handles. The uppercase-X rule is load-bearing — see [isTempo].
   */
  private val TEMPO_COMPACT = Regex("""^\d[\dX]X\d$""")
  private val TEMPO_DASHED = Regex("""^[\dX]-[\dX]-[\dX]-[\dX]$""")

  private val CLOCK = Regex("""^\d{1,3}:\d{2}$""")

  /** `%`, `%1RM`, `% TM`, `%e1RM`, `%BW`, `% of 1RM`. */
  private val PERCENT = Regex("""^%\s*(of\s*)?(1\s*rm|tm|e1\s*rm|bw|training\s*max|bodyweight)?""",
    RegexOption.IGNORE_CASE)

  fun tokenize(line: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0

    // The superset tag can only be at the very start, and consuming it first stops "A1" being read
    // as a word that then poisons the exercise name.
    SUPERSET.find(line)?.let { m ->
      out += Token(TokenType.SUPERSET_TAG, m.value.trim(), m.range.first, m.range.last + 1,
        value = m.groupValues[1].uppercase() + m.groupValues[2])
      i = m.range.last + 1
    }

    while (i < line.length) {
      val c = line[i]
      if (c.isWhitespace()) { i++; continue }

      // A multi-word modifier or rest phrase has to be matched before single words, or "each" and
      // "side" become two meaningless WORDs and the modifier is lost.
      val phrase = matchPhrase(line, i)
      if (phrase != null) { out += phrase; i = phrase.end; continue }

      when {
        c.isDigit() || (c == '.' && i + 1 < line.length && line[i + 1].isDigit()) -> {
          val start = i
          var seenSeparator = false
          while (i < line.length) {
            val ch = line[i]
            when {
              ch.isDigit() -> i++
              (ch == '.' || ch == ',') && !seenSeparator && isDecimalSeparator(line, i) -> {
                seenSeparator = true; i++
              }
              else -> break
            }
          }
          if (i == start) {
            // Consumed nothing: this is a separator that only looked like the start of a number.
            // Emitting an empty NUMBER here and not advancing spins the tokeniser forever.
            out += Token(TokenType.PUNCT, c.toString(), start, start + 1)
            i = start + 1
            continue
          }
          // A trailing '.' is a sentence, not a decimal.
          var end = i
          while (end > start && line[end - 1] == '.') end--
          val text = line.substring(start, end)
          // `3010` and `31X0` are tempos, not numbers — checked before emitting a NUMBER.
          out += if (isTempo(line, start)) {
            val te = tempoEnd(line, start)
            Token(TokenType.TEMPO, line.substring(start, te), start, te).also { i = te }
          } else if (end + 1 < line.length && line[end] == ':' && line.getOrNull(end + 1)?.isDigit() == true) {
            val ce = clockEnd(line, start)
            Token(TokenType.CLOCK, line.substring(start, ce), start, ce).also { i = ce }
          } else {
            Token(TokenType.NUMBER, text, start, end).also { i = end }
          }
        }

        c.isLetter() -> {
          val start = i
          while (i < line.length && (line[i].isLetter() || line[i] == '\'' ||
                 // Keep `3x8` glued: a letter run stops at a digit unless the letter is an x/X.
                 false)) i++
          val word = line.substring(start, i)
          // `31X0` reaches here when it starts with a letter form; handled above for digits.
          val lower = vocab.lower(word)
          val type = when {
            lower == "x" && start > 0 -> TokenType.X
            lower == "rpe" -> TokenType.RPE
            lower == "rir" -> TokenType.RIR
            lower == "amrap" || lower in vocab.amrapWords -> TokenType.AMRAP
            lower == "emom" || lower == "otm" || Regex("""^e\d+mom$""").matches(lower) -> TokenType.EMOM
            lower == "bw" || lower == "bodyweight" -> TokenType.BODYWEIGHT
            lower == "band" || lower == "bands" || lower == "banded" ||
              lower == "chain" || lower == "chains" -> TokenType.RESISTANCE
            lower == "to" -> TokenType.RANGE
            lower == "rep" || lower == "reps" -> TokenType.REPS_WORD
            lower == "set" || lower == "sets" -> TokenType.SETS_WORD
            lower in restWords -> TokenType.REST
            lower in unitTokens -> unitTokens.getValue(lower)
            isTempoWord(word) -> TokenType.TEMPO
            singleWordModifier(lower) != null -> TokenType.MODIFIER
            else -> TokenType.WORD
          }
          val payload = when (type) {
            TokenType.MODIFIER -> singleWordModifier(lower).orEmpty()
            TokenType.WORD -> ""
            else -> lower
          }
          out += Token(type, word, start, i, value = payload)
        }

        else -> {
          val type = when (c) {
            '×', '*' -> TokenType.X
            '@' -> TokenType.AT
            '%' -> TokenType.PERCENT
            '+' -> TokenType.PLUS
            '/' -> TokenType.SLASH
            ',' -> TokenType.COMMA
            '(' -> TokenType.LPAREN
            ')' -> TokenType.RPAREN
            '#' -> TokenType.WEIGHT_UNIT
            '-', '–', '—' -> if (isRangeDash(line, i)) TokenType.RANGE else TokenType.MINUS
            else -> TokenType.PUNCT
          }
          if (type == TokenType.PERCENT) {
            val m = PERCENT.find(line.substring(i))
            val len = m?.value?.length ?: 1
            out += Token(TokenType.PERCENT, line.substring(i, i + len), i, i + len,
              value = vocab.lower(m?.groupValues?.get(2).orEmpty()).replace(" ", ""))
            i += len
          } else {
            out += Token(type, c.toString(), i, i + 1)
            i++
          }
        }
      }
    }
    return out
  }

  /**
   * A dash between two numbers is a RANGE (`8-12`); a dash between words is a separator; a dash
   * before a number after "BW" or "assisted" is a MINUS. Getting this wrong turns "Bench Press -
   * 3x8" into a rep range of nothing.
   */
  private fun isRangeDash(line: String, i: Int): Boolean {
    var a = i - 1
    while (a >= 0 && line[a] == ' ') a--
    var b = i + 1
    while (b < line.length && line[b] == ' ') b++
    val before = a >= 0 && line[a].isDigit()
    val after = b < line.length && line[b].isDigit()
    if (!before || !after) return false
    // "BW -20kg" is assistance, not a range: a dash straight after a bodyweight marker subtracts.
    val head = vocab.lower(line.substring(0, i))
    return !head.trimEnd().endsWith("bw") && !head.trimEnd().endsWith("bodyweight") &&
      !head.trimEnd().endsWith("assisted")
  }

  /**
   * Tempo detection, and the **uppercase-X rule is deliberate and correct**: `31X0` is a tempo,
   * `10x8` is sets by reps. A lowercase `x` between digits is always the multiplication operator;
   * only an uppercase `X` in a 4-slot group is the isometric/explosive marker. This rule was
   * hard-won and is preserved verbatim from the previous parser, with its own named test.
   */
  private fun isTempo(line: String, start: Int): Boolean {
    val end = tempoEnd(line, start)
    if (end <= start) return false
    val s = line.substring(start, end)
    return TEMPO_COMPACT.matches(s) || TEMPO_DASHED.matches(s)
  }

  private fun isTempoWord(word: String): Boolean =
    TEMPO_COMPACT.matches(word) || TEMPO_DASHED.matches(word)

  private fun tempoEnd(line: String, start: Int): Int {
    var i = start
    // Compact: exactly four of [0-9X], and one of them must be the uppercase X.
    while (i < line.length && (line[i].isDigit() || line[i] == 'X')) i++
    if (i - start == 4 && TEMPO_COMPACT.matches(line.substring(start, i))) {
      // A fifth character that is a digit or X means this is a longer number, not a tempo.
      if (i >= line.length || !(line[i].isDigit() || line[i] == 'X')) return i
    }
    // Dashed: d-d-d-d.
    val dashed = Regex("""^[\dX]-[\dX]-[\dX]-[\dX]""").find(line.substring(start))
    return if (dashed != null) start + dashed.value.length else start
  }

  private fun clockEnd(line: String, start: Int): Int {
    val m = CLOCK.find(line.substring(start).takeWhile { it.isDigit() || it == ':' })
    return if (m != null) start + m.value.length else start
  }

  /**
   * Longest-first phrase matching against the vocabulary: modifiers, "rest as needed", "no rest",
   * "sets of", "as many reps as possible". Returns null when nothing matches at [i].
   */
  private fun matchPhrase(line: String, i: Int): Token? {
    val rest = line.substring(i)
    val lowerRest = vocab.lower(rest)

    // The EMOM family has a digit in the middle of a word, which the letter branch would split
    // into three meaningless tokens.
    EMOM_FAMILY.find(rest)?.let { m ->
      return Token(TokenType.EMOM, m.value, i, i + m.value.length, value = "emom")
    }

    // "sets of" / "sets x" is the long form of the X operator.
    Regex("""^sets?\s+(of|x|×|\*)\s+""", RegexOption.IGNORE_CASE).find(rest)?.let { m ->
      return Token(TokenType.X, m.value.trim(), i, i + m.value.trimEnd().length)
    }

    for ((pattern, canonical) in vocab.modifierPatterns) {
      // MULTI-WORD phrases only. A single-word modifier is classified in the letter branch, AFTER
      // the reserved keywords — otherwise the vocabulary's "emom" entry shadows the EMOM token
      // type, "band" shadows RESISTANCE, and the clause parsers that give those words their
      // structure never run.
      if (' ' !in pattern && '/' !in pattern) continue
      if (!lowerRest.startsWith(pattern)) continue
      // Word boundary: "band" must not match inside "bandana", and "es" must not match "escape".
      val after = lowerRest.getOrNull(pattern.length)
      if (after != null && (after.isLetterOrDigit())) continue
      val before = if (i == 0) null else line[i - 1]
      if (before != null && before.isLetterOrDigit()) continue
      return Token(TokenType.MODIFIER, rest.take(pattern.length), i, i + pattern.length, canonical)
    }

    for (phrase in vocab.restAsNeeded + vocab.noRestWords) {
      if (lowerRest.startsWith(phrase)) {
        val after = lowerRest.getOrNull(phrase.length)
        if (after != null && after.isLetterOrDigit()) continue
        return Token(TokenType.REST, rest.take(phrase.length), i, i + phrase.length,
          value = if (phrase in vocab.noRestWords) "none" else "as_needed")
      }
    }

    for (phrase in vocab.amrapWords.filter { it.contains(' ') }) {
      if (lowerRest.startsWith(phrase)) {
        return Token(TokenType.AMRAP, rest.take(phrase.length), i, i + phrase.length)
      }
    }
    return null
  }
}

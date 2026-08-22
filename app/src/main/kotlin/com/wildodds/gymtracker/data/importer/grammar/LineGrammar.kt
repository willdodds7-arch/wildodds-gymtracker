package com.wildodds.gymtracker.data.importer.grammar

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
import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary

/**
 * The grammar: a recursive-descent parser over [Lexer]'s tokens for one written exercise line.
 *
 * Structure, and why it is shaped this way:
 *
 *  1. **The name is whatever comes before the prescription.** So the parser first finds the ANCHOR —
 *     the token where the prescription starts — and everything to its left is the movement. This is
 *     the one decision that everything else depends on, and it is why the anchor search is ranked
 *     rather than first-match: a line like "Dumbbell Row 45° 3x10" has a number in the NAME, and
 *     anchoring on it produces an exercise called "Dumbbell Row" prescribed "45 degrees".
 *  2. **Clauses after the anchor are order-independent.** Coaches write "3x5 @80% rest 3min" and
 *     "3x5 rest 3min @80%" interchangeably, so each clause parser claims what it recognises and the
 *     loop keeps going. Ordering bugs — the failure mode of the eleven-regex parser this replaces —
 *     become impossible, because nothing depends on which clause ran first.
 *  3. **Every consumed token is recorded.** [ParsedLine.coverage] is the fraction of the line's
 *     characters that some token claimed. A line where 40% went unread is a line the parser probably
 *     misunderstood, and that single number catches more silent misreads than any other signal.
 *
 * Nothing is invented. A prescription the source did not state comes back null, and an unreadable
 * line still yields the movement name plus [SetKind.UNKNOWN] with the verbatim text intact.
 */
object LineGrammar {

  private val vocab = ReaderVocabulary

  /**
   * An internal marker for "this line used a unit that could honestly be read two ways". It is
   * stripped before the modifiers reach the model and turned into a confidence reason instead —
   * a modifier describes how a set is performed, and "we were unsure" is not that.
   */
  const val AMBIGUOUS_UNIT_MARKER = "__ambiguous_unit"

  data class ParsedLine(
    val name: String,
    val prescription: SetPrescription,
    val rest: RestSpec = RestSpec(),
    val supersetGroup: String = "",
    val supersetOrder: Int? = null,
    val modifiers: List<String> = emptyList(),
    val optional: Boolean = false,
    /** Fraction of the line's non-space characters claimed by a recognised token, in [0,1]. */
    val coverage: Double = 0.0,
    /** True when a unit on this line could honestly be read two ways (a bare `m`, most often). */
    val ambiguousUnit: Boolean = false,
    /** Text after the prescription that nothing claimed — a coach's cue, usually. */
    val trailingNote: String = "",
    val tokens: List<Token> = emptyList()
  )

  fun parse(rawLine: String): ParsedLine? {
    val line = rawLine.trim()
    if (line.isBlank()) return null
    val tokens = Lexer.tokenize(line)
    if (tokens.isEmpty()) return null

    val claimed = BooleanArray(line.length)
    fun claim(t: Token) { for (k in t.start until minOf(t.end, line.length)) claimed[k] = true }

    // ── superset tag ───────────────────────────────────────────────────────────
    var i = 0
    var ssGroup = ""
    var ssOrder: Int? = null
    if (tokens[0].type == TokenType.SUPERSET_TAG) {
      ssGroup = tokens[0].value.take(1)
      ssOrder = tokens[0].value.drop(1).toIntOrNull()
      claim(tokens[0])
      i = 1
    }

    // ── the anchor ─────────────────────────────────────────────────────────────
    val anchor = findAnchor(tokens, i)
    val nameEnd = if (anchor != null) tokens[anchor].start else line.length
    val nameStart = if (i < tokens.size) tokens[i].start else 0
    val rawName = line.substring(nameStart.coerceAtMost(line.length), nameEnd.coerceAtMost(line.length))
      .trimSeparators()
    // Program-dialect words and modifiers written INSIDE the name ("Bench FSL 5x5", "Dips
    // (optional) 3x10") belong to the prescription, not to the movement. Pulling them out here is
    // what stops the exercise resolver being handed "Bench FSL" and failing to match anything.
    val (name, nameModifiers) = splitNameModifiers(rawName)

    val state = ClauseState()
    state.modifiers += nameModifiers
    // A TM mention anywhere on the line governs every percentage on it. It has to be found BEFORE
    // the clauses run, because coaches write "70/80/90% TM" - the qualifier comes last and applies
    // backwards to all three.
    for (tk in tokens) {
      val w = vocab.lower(tk.text)
      if (w == "tm" || w.startsWith("training max") || tk.value == "tm" || tk.value == "trainingmax") {
        state.lineMentionsTrainingMax = true
        claim(tk)
      }
    }
    var idx = anchor ?: tokens.size

    // The name's characters count as claimed: they were understood, as a name.
    for (k in nameStart until nameEnd.coerceAtMost(line.length)) claimed[k] = true

    // ── clauses ────────────────────────────────────────────────────────────────
    while (idx < tokens.size) {
      val before = idx
      idx = parseClause(tokens, idx, state, ::claim)
      if (idx == before) {
        // Nothing claimed this token. Leave it unclaimed — that is exactly the signal the
        // coverage metric exists to carry — and move on rather than looping.
        idx++
      }
    }

    val prescription = state.build(rawLine)
    val nonSpace = line.indices.count { !line[it].isWhitespace() }
    val claimedNonSpace = line.indices.count { !line[it].isWhitespace() && claimed[it] }
    val coverage = if (nonSpace == 0) 0.0 else claimedNonSpace.toDouble() / nonSpace

    val trailing = line.indices
      .filter { !claimed[it] }
      .fold(StringBuilder()) { sb, k -> sb.append(line[k]) }
      .toString().trimSeparators()

    return ParsedLine(
      name = name,
      prescription = prescription,
      rest = state.rest,
      supersetGroup = ssGroup,
      supersetOrder = ssOrder,
      modifiers = state.modifiers.filterNot { it == AMBIGUOUS_UNIT_MARKER }.distinct().sorted(),
      ambiguousUnit = AMBIGUOUS_UNIT_MARKER in state.modifiers,
      optional = Modifier.OPTIONAL in state.modifiers,
      coverage = coverage,
      trailingNote = trailing,
      tokens = tokens
    )
  }

  /** 5/3/1 dialect words, recorded as modifiers rather than left in the exercise name. */
  private val DIALECT_WORDS = setOf("fsl", "bbb", "joker", "jokers", "amrap", "tng", "widowmaker")

  /**
   * Splits a name into the movement and the qualifiers that were written into it.
   *
   * A coach writes "Bench FSL 5x5" or "Dips (optional) 3x10", and the words before the prescription
   * are not all part of the movement. Leaving them in hands the exercise resolver "Bench FSL",
   * which matches nothing and costs the user a confirmation tap on a line that was read perfectly.
   */
  private fun splitNameModifiers(rawName: String): Pair<String, List<String>> {
    if (rawName.isBlank()) return rawName to emptyList()
    val mods = ArrayList<String>()
    var working = rawName

    // Bracketed qualifiers first: "(optional)", "[each side]".
    Regex("""[(\[]([^)\]]{1,30})[)\]]""").findAll(rawName).forEach { m ->
      val inner = vocab.lower(m.groupValues[1]).trim()
      val canonical = vocab.modifierPatterns.firstOrNull { it.first == inner }?.second
      if (canonical != null || inner in DIALECT_WORDS) {
        mods += canonical ?: inner
        working = working.replace(m.value, " ")
      }
    }

    val kept = ArrayList<String>()
    for (word in working.split(' ')) {
      val bare = vocab.lower(word.trim('(', ')', '[', ']', ',', '.'))
      when {
        bare.isBlank() -> Unit
        bare in DIALECT_WORDS -> mods += bare
        else -> kept += word
      }
    }
    return kept.joinToString(" ").trimSeparators() to mods
  }

  // ── anchor selection ─────────────────────────────────────────────────────────

  /**
   * Where the prescription starts. Ranked, not first-match: a sets x reps core is the strongest
   * anchor and beats a stray number in the movement name, which is the difference between
   * "Dumbbell Row 45°" prescribed 3x10 and an exercise called "Dumbbell Row" prescribed "45 degrees".
   *
   * An anchor at index [from] itself is rejected — a line with no name is not an exercise line.
   */
  private fun findAnchor(t: List<Token>, from: Int): Int? {
    fun candidates(pred: (Int) -> Boolean): Int? =
      (from until t.size).firstOrNull { it > from && pred(it) }

    // 1. sets x reps is the strongest signal, and a per-set list is equally strong — so take
    //    whichever starts EARLIER. "1,2,3,4,5 x 5 rounds" contains both, and anchoring on the
    //    sets x reps would cut the ladder in half and call "Pull Up 1,2,3,4," the exercise name.
    val setsReps = candidates { k ->
      t[k].type == TokenType.NUMBER && t.getOrNull(k + 1)?.type == TokenType.X
    }
    val list = candidates { k -> isListStart(t, k) }
    listOfNotNull(setsReps, list).minOrNull()?.let { return it }
    // 2. "3 sets" with no rep count — a count the source DID state.
    candidates { k -> t[k].type == TokenType.NUMBER && t.getOrNull(k + 1)?.type == TokenType.SETS_WORD }
      ?.let { return it }
    // 3. A number qualified only by a modifier: "50 total reps".
    candidates { k -> t[k].type == TokenType.NUMBER && t.getOrNull(k + 1)?.type == TokenType.MODIFIER }
      ?.let { return it }
    // 4. A standalone AMRAP / EMOM / for-time marker.
    candidates { k -> t[k].type == TokenType.AMRAP || t[k].type == TokenType.EMOM }?.let { return it }
    // 5. A load with no rep prescription at all: "Bench @ 80kg", "Squat 5RM".
    candidates { k -> t[k].type == TokenType.AT }?.let { return it }
    candidates { k -> t[k].type == TokenType.NUMBER && t.getOrNull(k + 1)?.type == TokenType.REPS_WORD }
      ?.let { return it }
    // 6. Time or distance only: "Plank 60s", "Row 2000m", "Bike 20 min".
    candidates { k ->
      t[k].type == TokenType.NUMBER &&
        t.getOrNull(k + 1)?.type in setOf(TokenType.TIME_UNIT, TokenType.DISTANCE_UNIT, TokenType.CALORIE_UNIT)
    }?.let { return it }
    // 7. Rest, or a modifier that stands alone: "Bench - rest as needed", "Dips to failure".
    candidates { k -> t[k].type == TokenType.REST }?.let { return it }
    candidates { k -> t[k].type == TokenType.MODIFIER }?.let { return it }
    return null
  }

  /** `5/3/1`, `10,8,6`, `21-15-9`, `1,2,3,4,5` — a run of numbers joined by one separator. */
  private fun isListStart(t: List<Token>, k: Int): Boolean {
    if (t[k].type != TokenType.NUMBER) return false
    val sep = t.getOrNull(k + 1)?.type ?: return false
    if (sep !in setOf(TokenType.SLASH, TokenType.COMMA, TokenType.RANGE)) return false
    if (t.getOrNull(k + 2)?.type != TokenType.NUMBER) return false
    val (numbers, _) = readList(t, k, sep)
    // A slash pair is enough (5/3), but a comma pair is not: "Bench 3x5, 2 min rest" would
    // otherwise read as a two-set list. A dash pair is a rep RANGE, not a list.
    return when (sep) {
      TokenType.SLASH -> numbers.size >= 2
      TokenType.COMMA -> numbers.size >= 3
      else -> numbers.size >= 3      // 21-15-9 is a list; 8-12 is a range
    }
  }

  /** Reads `n sep n sep n …` starting at [k]; returns the numbers and the index after the run. */
  private fun readList(t: List<Token>, k: Int, sep: TokenType): Pair<List<Int>, Int> {
    val nums = ArrayList<Int>()
    var i = k
    while (i < t.size && t[i].type == TokenType.NUMBER) {
      nums += t[i].number?.toInt() ?: break
      if (t.getOrNull(i + 1)?.type == sep && t.getOrNull(i + 2)?.type == TokenType.NUMBER) i += 2
      else { i++; break }
    }
    return nums to i
  }

  // ── clause parsing ───────────────────────────────────────────────────────────

  /** Everything the clause loop accumulates before it is folded into one [SetPrescription]. */
  private class ClauseState {
    var count: Int? = null
    var reps: RepSpec? = null
    var load: LoadSpec? = null
    var tempo: String = ""
    var kind: SetKind = SetKind.UNKNOWN
    var finalSetAmrap = false
    var rest: RestSpec = RestSpec()
    val perSet = ArrayList<SetPrescription>()
    val modifiers = ArrayList<String>()
    /** A `TM` anywhere on the line governs every percentage on it — see [applyTrainingMax]. */
    var lineMentionsTrainingMax = false
    var percentQualifier: String = ""

    fun build(raw: String): SetPrescription {
      applyTrainingMax()
      val resolvedKind = when {
        kind != SetKind.UNKNOWN -> kind
        perSet.isNotEmpty() -> SetKind.STRAIGHT
        reps?.kind == RepKind.TIME_SEC -> SetKind.TIMED
        reps?.kind == RepKind.DISTANCE_M -> SetKind.CARRY
        reps?.kind == RepKind.RANGE -> SetKind.RANGE
        reps != null || count != null -> SetKind.STRAIGHT
        else -> SetKind.UNKNOWN
      }
      return SetPrescription(
        rawText = raw.trim(),
        kind = resolvedKind,
        count = count,
        reps = reps,
        load = load,
        tempo = tempo,
        isFinalSetAmrap = finalSetAmrap,
        rest = rest.takeIf { it.kind != RestKind.NONE_SPECIFIED },
        modifiers = modifiers.distinct().sorted(),
        perSet = perSet.toList()
      )
    }

    /**
     * One TM per line governs all percentages on it: coaches write "65%, 75%, 85% TM", never
     * "65% TM, 75% TM, 85% TM". Preserved verbatim from the previous parser — it has its own named
     * test, and getting it wrong makes every 5/3/1 import wrong by the ratio of TM to 1RM.
     */
    private fun applyTrainingMax() {
      if (!lineMentionsTrainingMax) return
      fun retag(l: LoadSpec?): LoadSpec? =
        if (l?.kind == LoadKind.PCT_1RM) l.copy(kind = LoadKind.PCT_TM) else l
      load = retag(load)
      for (j in perSet.indices) perSet[j] = perSet[j].copy(load = retag(perSet[j].load))
    }
  }

  private fun parseClause(
    t: List<Token>,
    at: Int,
    s: ClauseState,
    claim: (Token) -> Unit
  ): Int {
    val tok = t[at]
    return when (tok.type) {
      TokenType.NUMBER -> parseNumberClause(t, at, s, claim)
      TokenType.AT -> { claim(tok); at + 1 }             // "@" only introduces the next clause
      TokenType.PERCENT -> parsePercent(t, at, s, claim)
      TokenType.RPE -> parseRpe(t, at, s, claim)
      TokenType.RIR -> parseRir(t, at, s, claim)
      TokenType.REST -> parseRest(t, at, s, claim)
      TokenType.TEMPO -> { s.tempo = tok.text; claim(tok); at + 1 }
      TokenType.AMRAP -> parseAmrap(t, at, s, claim)
      TokenType.EMOM -> parseEmom(t, at, s, claim)
      TokenType.BODYWEIGHT -> parseBodyweight(t, at, s, claim)
      TokenType.RESISTANCE -> parseResistance(t, at, s, claim)
      TokenType.MODIFIER -> {
        s.modifiers += tok.value
        applyModifierKind(tok.value, s)
        claim(tok)
        at + 1
      }
      TokenType.WORD -> parseWordClause(t, at, s, claim)
      TokenType.SETS_WORD, TokenType.REPS_WORD, TokenType.X, TokenType.COMMA,
      TokenType.SLASH, TokenType.PUNCT, TokenType.LPAREN, TokenType.RPAREN -> { claim(tok); at + 1 }
      else -> at
    }
  }

  /** Some modifiers imply the set STYLE, not just a qualifier on it. */
  private fun applyModifierKind(mod: String, s: ClauseState) {
    s.kind = when (mod) {
      Modifier.CLUSTER -> SetKind.CLUSTER
      Modifier.DROP_SET -> SetKind.DROP
      Modifier.REST_PAUSE -> SetKind.RESTPAUSE
      Modifier.MYO_REPS -> SetKind.MYO
      Modifier.EMOM -> SetKind.EMOM
      Modifier.FOR_TIME -> SetKind.FOR_TIME
      Modifier.AMRAP_IN_TIME -> SetKind.AMRAP_TIME
      else -> s.kind
    }
  }

  /**
   * A number, and everything it can begin: sets x reps, a per-set list, a load, a duration, a
   * distance, a rep range, or a bare rep count.
   */
  private fun parseNumberClause(
    t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit
  ): Int {
    val n = t[at].number ?: return at + 1

    // n x …  — the sets x reps core.
    if (t.getOrNull(at + 1)?.type == TokenType.X) {
      claim(t[at]); claim(t[at + 1])
      s.count = n.toInt()
      var i = at + 2

      // n x (a.b.c) — a cluster set: three mini-sets inside one set.
      if (t.getOrNull(i)?.type == TokenType.LPAREN) {
        val close = (i until t.size).firstOrNull { t[it].type == TokenType.RPAREN }
        if (close != null) {
          val inner = t.subList(i + 1, close)
          // "3.3.3" lexes as a single NUMBER token (it looks like a decimal until the second dot),
          // so the mini-set counts are read out of the raw text rather than the token types.
          val innerText = inner.joinToString(" ") { it.text }
          val nums = if (innerText.any { it.isLetter() }) emptyList()
                     else Regex("""\d+""").findAll(innerText).mapNotNull { it.value.toIntOrNull() }.toList()
          for (k in i..close) claim(t[k])
          if (nums.isNotEmpty()) {
            s.kind = SetKind.CLUSTER
            s.modifiers += Modifier.CLUSTER
            s.reps = RepSpec(RepKind.COUNT, nums.sum(),
              text = nums.joinToString(".") { it.toString() })
            for ((j, r) in nums.withIndex()) {
              s.perSet += SetPrescription("$r", SetKind.CLUSTER, 1, RepSpec(RepKind.COUNT, r))
              if (j == nums.lastIndex) Unit
            }
          } else {
            // "3x(clean+FS+jerk)" — a complex. The movements are named, not counted.
            s.kind = SetKind.COMPLEX
            s.reps = RepSpec(RepKind.TEXT, text = inner.joinToString("") { it.text })
          }
          return close + 1
        }
      }

      val repTok = t.getOrNull(i) ?: return i
      if (repTok.type == TokenType.CLOCK) {
        claim(repTok)
        s.reps = RepSpec(RepKind.TIME_SEC, clockSeconds(repTok.text), text = repTok.text)
        return i + 1
      }
      if (repTok.type != TokenType.NUMBER) {
        // "3 x AMRAP", "3 x max reps".
        if (repTok.type == TokenType.AMRAP) {
          claim(repTok)
          s.reps = RepSpec(RepKind.AMRAP, text = repTok.text)
          s.finalSetAmrap = true
          return i + 1
        }
        return i
      }
      claim(repTok)
      val lo = repTok.number?.toInt() ?: return i + 1
      i++

      // 8-12 — a rep range.
      var hi: Int? = null
      if (t.getOrNull(i)?.type == TokenType.RANGE && t.getOrNull(i + 1)?.type == TokenType.NUMBER) {
        claim(t[i]); claim(t[i + 1])
        hi = t[i + 1].number?.toInt()
        i += 2
      }

      // A trailing unit turns the rep count into a duration, a distance or calories.
      val unit = t.getOrNull(i)
      val unitKind = unitKindFor(unit, lo.toDouble())
      if (unitKind != null) { claim(unit!!); i++ }
      if (unit != null && isBareM(unit) && isAmbiguousMetres(lo.toDouble())) {
        s.modifiers += AMBIGUOUS_UNIT_MARKER
      }

      // 1x5+ — the final set is an AMRAP with a stated minimum.
      var plus = false
      if (t.getOrNull(i)?.type == TokenType.PLUS) {
        // 1x8+2+2 is rest-pause, not a bare AMRAP marker.
        if (t.getOrNull(i + 1)?.type == TokenType.NUMBER) {
          s.kind = SetKind.RESTPAUSE
          s.modifiers += Modifier.REST_PAUSE
          s.perSet += SetPrescription("$lo", SetKind.RESTPAUSE, 1, RepSpec(RepKind.COUNT, lo))
          while (t.getOrNull(i)?.type == TokenType.PLUS && t.getOrNull(i + 1)?.type == TokenType.NUMBER) {
            claim(t[i]); claim(t[i + 1])
            val extra = t[i + 1].number!!.toInt()
            s.perSet += SetPrescription("$extra", SetKind.RESTPAUSE, 1, RepSpec(RepKind.COUNT, extra))
            i += 2
          }
          s.reps = RepSpec(RepKind.COUNT, s.perSet.sumOf { it.reps?.min ?: 0 },
            text = s.perSet.joinToString("+") { "${it.reps?.min}" })
          return i
        }
        claim(t[i]); plus = true; i++
      }

      s.reps = when {
        unitKind == RepKind.TIME_SEC -> RepSpec(RepKind.TIME_SEC, toSeconds(lo, unit!!), text = "$lo${unit.text}")
        unitKind == RepKind.DISTANCE_M -> RepSpec(RepKind.DISTANCE_M, toMetres(lo, unit!!), text = "$lo${unit.text}")
        unitKind == RepKind.CALORIES -> RepSpec(RepKind.CALORIES, lo, text = "$lo${unit!!.text}")
        plus -> { s.finalSetAmrap = true; RepSpec(RepKind.AMRAP_MIN, lo, text = "$lo+") }
        hi != null -> RepSpec(RepKind.RANGE, lo, hi)
        else -> RepSpec(RepKind.COUNT, lo)
      }
      return i
    }

    // A per-set list, a ladder, or a for-time triple.
    val sep = t.getOrNull(at + 1)?.type
    if (sep in setOf(TokenType.SLASH, TokenType.COMMA, TokenType.RANGE) && isListStart(t, at)) {
      val (nums, next) = readList(t, at, sep!!)
      for (k in at until next) claim(t[k])
      // "65/75/85" with a percent right after is a LOAD wave, not a rep list.
      val pct = t.getOrNull(next)
      if (pct?.type == TokenType.PERCENT) {
        claim(pct)
        val kind = percentKind(pct.value)
        if (kind == LoadKind.PCT_TM) s.lineMentionsTrainingMax = true
        s.perSet.clear()
        for (v in nums) {
          s.perSet += SetPrescription("$v%", SetKind.STRAIGHT, 1, null,
            LoadSpec(kind, v.toDouble(), text = "$v%"))
        }
        s.count = nums.size
        s.load = LoadSpec(kind, nums.first().toDouble(), text = nums.joinToString("/") { "$it" } + "%")
        return next + 1
      }
      // "1,2,3,4,5 x 5 rounds" — a ladder repeated N times.
      if (t.getOrNull(next)?.type == TokenType.X && t.getOrNull(next + 1)?.type == TokenType.NUMBER) {
        claim(t[next]); claim(t[next + 1])
        s.kind = SetKind.LADDER
        s.count = t[next + 1].number?.toInt()?.times(nums.size)
        s.perSet += nums.map { SetPrescription("$it", SetKind.LADDER, 1, RepSpec(RepKind.COUNT, it)) }
        s.reps = RepSpec(RepKind.COUNT, nums.first(), text = nums.joinToString(","))
        return next + 2
      }
      if (sep == TokenType.RANGE) {
        // 21-15-9 is a for-time triple, and the descent is the point.
        s.kind = SetKind.FOR_TIME
        s.modifiers += Modifier.FOR_TIME
      } else if (nums.zipWithNext().all { (a, b) -> a >= b } && nums.size >= 3) {
        s.kind = SetKind.PYRAMID
      }
      s.count = nums.size
      s.perSet += nums.map { SetPrescription("$it", s.kind, 1, RepSpec(RepKind.COUNT, it)) }
      s.reps = RepSpec(RepKind.COUNT, nums.first(), text = nums.joinToString(if (sep == TokenType.SLASH) "/" else ", "))
      return next
    }

    // A number followed by a unit, with no set count: "Plank 60s", "Row 2000m", "15 cal bike".
    val unit = t.getOrNull(at + 1)
    unitKindFor(unit, n)?.let { uk ->
      claim(t[at]); claim(unit!!)
      // "rest 90s" was already claimed by the rest clause; only fill reps when nothing has.
      if (s.reps == null) {
        s.reps = when (uk) {
          RepKind.TIME_SEC -> RepSpec(RepKind.TIME_SEC, toSeconds(n.toInt(), unit), text = "${n.toInt()}${unit.text}")
          RepKind.DISTANCE_M -> RepSpec(RepKind.DISTANCE_M, toMetres(n.toInt(), unit), text = "${n.toInt()}${unit.text}")
          else -> RepSpec(RepKind.CALORIES, n.toInt(), text = "${n.toInt()} ${unit.text}")
        }
      }
      return at + 2
    }

    // A number followed by a weight unit is an absolute load: "@ 100kg", "100 kg", "225lb".
    if (unit?.type == TokenType.WEIGHT_UNIT) {
      claim(t[at]); claim(unit)
      var hi: Double? = null
      var next = at + 2
      // "60-70kg" lexes as NUMBER RANGE NUMBER WEIGHT_UNIT, handled below; this branch covers
      // "60kg-70kg", which coaches also write.
      if (t.getOrNull(next)?.type == TokenType.RANGE && t.getOrNull(next + 1)?.type == TokenType.NUMBER) {
        claim(t[next]); claim(t[next + 1])
        hi = t[next + 1].number
        next += 2
        if (t.getOrNull(next)?.type == TokenType.WEIGHT_UNIT) { claim(t[next]); next++ }
      }
      s.load = LoadSpec(LoadKind.ABSOLUTE, n, hi, weightUnit(unit), "${t[at].text}${unit.text}")
      return next
    }

    // "60-70kg" — the range comes before the unit.
    if (t.getOrNull(at + 1)?.type == TokenType.RANGE &&
        t.getOrNull(at + 2)?.type == TokenType.NUMBER &&
        t.getOrNull(at + 3)?.type == TokenType.WEIGHT_UNIT) {
      for (k in at..at + 3) claim(t[k])
      s.load = LoadSpec(LoadKind.ABSOLUTE, n, t[at + 2].number, weightUnit(t[at + 3]),
        "${t[at].text}-${t[at + 2].text}${t[at + 3].text}")
      return at + 4
    }

    // "8 reps" with no set count.
    if (t.getOrNull(at + 1)?.type == TokenType.REPS_WORD) {
      claim(t[at]); claim(t[at + 1])
      if (s.reps == null) s.reps = RepSpec(RepKind.COUNT, n.toInt())
      return at + 2
    }

    // "3 sets" with no rep count — a count the source DID state, with reps it did not.
    if (t.getOrNull(at + 1)?.type == TokenType.SETS_WORD) {
      claim(t[at]); claim(t[at + 1])
      s.count = n.toInt()
      return at + 2
    }

    // "50 total reps" — the number IS the rep count, the modifier says how to reach it.
    if (t.getOrNull(at + 1)?.type == TokenType.MODIFIER && s.reps == null && s.count == null) {
      claim(t[at])
      s.reps = RepSpec(RepKind.COUNT, n.toInt())
      return at + 1
    }

    // A bare "8-12" rep range with no set count.
    if (t.getOrNull(at + 1)?.type == TokenType.RANGE && t.getOrNull(at + 2)?.type == TokenType.NUMBER &&
        s.reps == null) {
      for (k in at..at + 2) claim(t[k])
      s.reps = RepSpec(RepKind.RANGE, n.toInt(), t[at + 2].number?.toInt())
      return at + 3
    }

    // "@ 100" with no unit. Extremely common in spreadsheets, where the unit lives in the column
    // heading or nowhere at all. It IS an absolute load; the unit stays null so review asks rather
    // than assuming kg and silently halving every American import.
    val claimedByNext = t.getOrNull(at + 1)?.type in setOf(
      TokenType.PERCENT, TokenType.RPE, TokenType.RIR, TokenType.WEIGHT_UNIT,
      TokenType.TIME_UNIT, TokenType.DISTANCE_UNIT, TokenType.CALORIE_UNIT,
      TokenType.X, TokenType.RANGE, TokenType.SETS_WORD, TokenType.REPS_WORD)
    if (t.getOrNull(at - 1)?.type == TokenType.AT && s.load == null && !claimedByNext) {
      claim(t[at])
      s.load = LoadSpec(LoadKind.ABSOLUTE, n, unit = null, text = t[at].text)
      return at + 1
    }

    // A number the grammar cannot place is left UNCLAIMED on purpose: that is what makes the
    // coverage metric able to say "31% of this line was not understood".
    return at + 1
  }

  private fun parsePercent(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    val tok = t[at]
    claim(tok)
    val qualifier = tok.value
    // The number is usually before the sign ("75%"), occasionally after ("% 75").
    val numTok = t.getOrNull(at - 1)?.takeIf { it.type == TokenType.NUMBER }
      ?: t.getOrNull(at + 1)?.takeIf { it.type == TokenType.NUMBER }
    val v = numTok?.number
    if (numTok != null) claim(numTok)
    var next = at + 1

    // "@ 70-80%" — a percentage range.
    var hi: Double? = null
    if (t.getOrNull(at - 2)?.type == TokenType.RANGE && t.getOrNull(at - 3)?.type == TokenType.NUMBER) {
      claim(t[at - 2]); claim(t[at - 3])
      hi = v
      // The FIRST number is the low end; the one adjacent to the % sign is the high end.
      return finishPercent(s, t[at - 3].number, hi, qualifier, tok, next)
    }

    // A qualifier can also follow as a separate word: "80% of 1RM", "75% TM".
    var q = qualifier
    var k = at + 1
    while (k < t.size && q.isBlank()) {
      val w = vocab.lower(t[k].text)
      when {
        w == "of" -> { claim(t[k]); k++ }
        w == "tm" || w.startsWith("training") -> { q = "tm"; claim(t[k]); k++; next = k }
        w == "1rm" -> { q = "1rm"; claim(t[k]); k++; next = k }
        w == "e1rm" -> { q = "e1rm"; claim(t[k]); k++; next = k }
        w == "bw" || w == "bodyweight" -> { q = "bw"; claim(t[k]); k++; next = k }
        w == "that" || w == "top" -> { q = "top"; claim(t[k]); k++; next = k }
        else -> break
      }
    }
    return finishPercent(s, v, hi, q, tok, maxOf(next, k))
  }

  /** What a `%` qualifier means. Shared so the wave branch and the single branch cannot disagree. */
  private fun percentKind(qualifier: String): LoadKind = when (qualifier) {
    "tm", "trainingmax" -> LoadKind.PCT_TM
    "e1rm" -> LoadKind.PCT_E1RM
    "bw", "bodyweight" -> LoadKind.PCT_BODYWEIGHT
    "top", "that" -> LoadKind.PCT_OF_TOP
    else -> LoadKind.PCT_1RM
  }

  private fun finishPercent(
    s: ClauseState, v: Double?, hi: Double?, qualifier: String, tok: Token, next: Int
  ): Int {
    val kind = percentKind(qualifier)
    if (kind == LoadKind.PCT_TM) s.lineMentionsTrainingMax = true
    // A percentage never overwrites a more specific load that is already there.
    if (s.load == null || s.load!!.kind == LoadKind.NONE) {
      s.load = LoadSpec(kind, v, hi, text = (v?.let { fmt(it) } ?: "") + tok.text.trim())
    }
    return next
  }

  private fun parseRpe(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    claim(t[at])
    val a = t.getOrNull(at + 1)?.takeIf { it.type == TokenType.NUMBER }
      ?: t.getOrNull(at - 1)?.takeIf { it.type == TokenType.NUMBER }
    var next = at + 1
    var lo = a?.number
    var hi: Double? = null
    if (a != null) { claim(a); if (a.start > t[at].start) next = at + 2 }
    if (t.getOrNull(next)?.type == TokenType.RANGE && t.getOrNull(next + 1)?.type == TokenType.NUMBER) {
      claim(t[next]); claim(t[next + 1])
      hi = t[next + 1].number
      next += 2
    }
    val text = if (hi != null) "${fmt(lo ?: 0.0)}-${fmt(hi)}" else lo?.let { fmt(it) }.orEmpty()
    s.load = LoadSpec(LoadKind.RPE, lo, hi, text = text)
    return next
  }

  private fun parseRir(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    claim(t[at])
    // Both "RIR 2" and "2 RIR" are written; take whichever number is adjacent.
    val after = t.getOrNull(at + 1)?.takeIf { it.type == TokenType.NUMBER }
    val before = t.getOrNull(at - 1)?.takeIf { it.type == TokenType.NUMBER }
    val n = after ?: before
    if (n != null) claim(n)
    // An RPE already stated is more specific and wins; RIR does not overwrite it.
    if (s.load?.kind != LoadKind.RPE) {
      s.load = LoadSpec(LoadKind.RIR, n?.number, text = n?.text.orEmpty())
    }
    return if (after != null) at + 2 else at + 1
  }

  private fun parseRest(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    val tok = t[at]
    claim(tok)
    if (tok.value == "as_needed") { s.rest = RestSpec(RestKind.AS_NEEDED, text = tok.text); return at + 1 }
    if (tok.value == "none") { s.rest = RestSpec(RestKind.SUPERSET_NONE, text = tok.text); return at + 1 }

    var i = at + 1
    // "rest: 90s", "rest = 2 min"
    while (i < t.size && t[i].type == TokenType.PUNCT && t[i].text in setOf(":", "=", "-")) { claim(t[i]); i++ }

    // "rest 1:30"
    if (t.getOrNull(i)?.type == TokenType.CLOCK) {
      claim(t[i])
      s.rest = RestSpec(RestKind.FIXED, clockSeconds(t[i].text), text = t[i].text)
      return i + 1
    }
    if (t.getOrNull(i)?.type != TokenType.NUMBER) {
      // "rest as needed" where the phrase did not lex as one token.
      s.rest = RestSpec(RestKind.AS_NEEDED, text = tok.text)
      return i
    }
    val lo = t[i].number?.toInt() ?: return i
    claim(t[i])
    i++
    var hi: Int? = null
    if (t.getOrNull(i)?.type == TokenType.RANGE && t.getOrNull(i + 1)?.type == TokenType.NUMBER) {
      claim(t[i]); claim(t[i + 1])
      hi = t[i + 1].number?.toInt()
      i += 2
      // "90-120s" — the unit sits after the range.
      if (t.getOrNull(i)?.type == TokenType.WEIGHT_UNIT) { /* not a rest unit */ }
    }
    var mult = 1
    if (t.getOrNull(i)?.type == TokenType.TIME_UNIT) {
      claim(t[i])
      mult = timeMultiplier(t[i])
      i++
    }
    s.rest = if (hi != null) RestSpec(RestKind.RANGE, lo * mult, hi * mult, tok.text)
             else RestSpec(RestKind.FIXED, lo * mult, text = tok.text)
    return i
  }

  private fun parseAmrap(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    claim(t[at])
    // "AMRAP 12 min" — an AMRAP measured in time, not in reps.
    val n = t.getOrNull(at + 1)?.takeIf { it.type == TokenType.NUMBER }
    val unit = t.getOrNull(at + 2)
    if (n != null && unit?.type == TokenType.TIME_UNIT) {
      claim(n); claim(unit)
      s.kind = SetKind.AMRAP_TIME
      s.modifiers += Modifier.AMRAP_IN_TIME
      s.count = 1
      s.reps = RepSpec(RepKind.AMRAP_MIN, toSeconds(n.number!!.toInt(), unit), text = "AMRAP ${n.text}${unit.text}")
      return at + 3
    }
    if (s.reps == null) {
      s.reps = RepSpec(RepKind.AMRAP, text = "AMRAP")
      if (s.count == null) s.count = 1
    }
    s.finalSetAmrap = true
    return at + 1
  }

  private fun parseEmom(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    claim(t[at])
    s.kind = SetKind.EMOM
    s.modifiers += Modifier.EMOM
    // "EMOM 12" — twelve minutes, so twelve sets.
    val n = t.getOrNull(at + 1)?.takeIf { it.type == TokenType.NUMBER }
    if (n != null) {
      claim(n)
      var next = at + 2
      if (t.getOrNull(next)?.type == TokenType.TIME_UNIT) { claim(t[next]); next++ }
      // Every-2-minutes doubles the interval but not the count; the interval is in the text.
      if (s.count == null) s.count = n.number?.toInt()
      return next
    }
    return at + 1
  }

  private fun parseBodyweight(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    claim(t[at])
    val sign = t.getOrNull(at + 1)
    val n = t.getOrNull(at + 2)?.takeIf { it.type == TokenType.NUMBER }
    if ((sign?.type == TokenType.PLUS || sign?.type == TokenType.MINUS) && n != null) {
      claim(sign); claim(n)
      var next = at + 3
      var unit: WeightUnit? = null
      if (t.getOrNull(next)?.type == TokenType.WEIGHT_UNIT) { unit = weightUnit(t[next]); claim(t[next]); next++ }
      s.load = LoadSpec(
        if (sign.type == TokenType.PLUS) LoadKind.BODYWEIGHT_PLUS else LoadKind.BODYWEIGHT_MINUS,
        n.number, unit = unit,
        text = "${t[at].text}${sign.text}${n.text}"
      )
      return next
    }
    if (s.load == null) s.load = LoadSpec(LoadKind.BODYWEIGHT, text = t[at].text)
    return at + 1
  }

  private fun parseResistance(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    claim(t[at])
    val isChain = vocab.lower(t[at].text).startsWith("chain")
    s.modifiers += if (isChain) Modifier.CHAIN else Modifier.BAND
    // "+20kg chain" / "+ red band" — the colour or weight sits either side of the word.
    val label = buildString {
      t.getOrNull(at - 1)?.takeIf { it.type == TokenType.WORD || it.type == TokenType.NUMBER }
        ?.let { append(it.text).append(' '); claim(it) }
      append(t[at].text)
    }
    if (s.load == null || s.load!!.kind == LoadKind.NONE) {
      s.load = LoadSpec(if (isChain) LoadKind.CHAIN else LoadKind.BAND, text = label.trim())
    }
    return at + 1
  }

  /**
   * Words that carry structure without being modifiers in the vocabulary: "rounds", "each",
   * "assisted", "of", "then", "across", and the 5/3/1 dialect words FSL / BBB / joker.
   */
  private fun parseWordClause(t: List<Token>, at: Int, s: ClauseState, claim: (Token) -> Unit): Int {
    val w = vocab.lower(t[at].text)
    when (w) {
      "rounds", "round", "sets", "set" -> { claim(t[at]); return at + 1 }
      "assisted" -> {
        claim(t[at])
        if (s.load?.kind == LoadKind.ABSOLUTE) {
          s.load = s.load!!.copy(kind = LoadKind.BODYWEIGHT_MINUS)
        }
        return at + 1
      }
      "across" -> {
        // "5 sets across" — every set at the same load, which is the default; claim it as read.
        claim(t[at]); return at + 1
      }
      "tempo" -> {
        claim(t[at])
        // "tempo 3010" — a bare four-digit number after the word IS a tempo. Without the word it
        // stays a number, because a four-digit number is far more often a load or a year.
        val n = t.getOrNull(at + 1)
        if (n != null && (n.type == TokenType.TEMPO || (n.type == TokenType.NUMBER && n.text.length == 4))) {
          claim(n); s.tempo = n.text
          return at + 2
        }
        return at + 1
      }
      "fsl", "bbb", "joker", "jokers" -> {
        claim(t[at])
        s.modifiers += Modifier.TOTAL_REPS.let { _ -> w }   // keep the dialect word as a modifier
        return at + 1
      }
      "test", "max" -> {
        // "1RM test", "max effort" — a stated intent, not a rep count.
        claim(t[at]); return at + 1
      }
      else -> return at
    }
  }

  // ── unit helpers ─────────────────────────────────────────────────────────────

  /**
   * Which rep kind a unit token implies, given the number in front of it.
   *
   * A bare `m` is genuinely ambiguous — `5x400m` is metres, `3x2m` is minutes — and no amount of
   * pattern matching resolves it, because both are real notations. It is decided by magnitude:
   * a sprint or a carry is written in tens or hundreds of metres, a work interval in single-digit
   * minutes. [AMBIGUOUS_M_MAX] is where the split sits, and [isAmbiguousMetres] marks the band
   * around it so the caller can flag rather than pretend.
   *
   * Every unambiguous unit word (`min`, `sec`, `km`, `yd`) short-circuits this entirely, which is
   * why the ambiguity almost never bites in practice: coaches who mean minutes usually write "min".
   */
  private fun unitKindFor(unit: Token?, value: Double?): RepKind? = when (unit?.type) {
    TokenType.CALORIE_UNIT -> RepKind.CALORIES
    TokenType.DISTANCE_UNIT -> RepKind.DISTANCE_M
    TokenType.TIME_UNIT ->
      if (isBareM(unit) && (value ?: 0.0) >= AMBIGUOUS_M_MAX) RepKind.DISTANCE_M else RepKind.TIME_SEC
    else -> null
  }

  /** At or above this, a bare `m` after a number is metres; below it, minutes. */
  private const val AMBIGUOUS_M_MAX = 15.0

  /** The band where a bare `m` could honestly be either. Callers surface it rather than hide it. */
  fun isAmbiguousMetres(value: Double): Boolean = value in 5.0..30.0

  private fun isBareM(unit: Token) = vocab.lower(unit.text) == "m"

  private fun toSeconds(value: Int, unit: Token): Int = value * timeMultiplier(unit)

  private fun timeMultiplier(unit: Token): Int {
    val u = vocab.lower(unit.text)
    return when {
      u in vocab.units["hours"].orEmpty() -> 3600
      u in vocab.units["minutes"].orEmpty() -> 60
      else -> 1
    }
  }

  private fun toMetres(value: Int, unit: Token): Int {
    val u = vocab.lower(unit.text)
    return when {
      u in vocab.units["km"].orEmpty() -> value * 1000
      u in vocab.units["miles"].orEmpty() -> (value * 1609.34).toInt()
      u in vocab.units["yards"].orEmpty() -> (value * 0.9144).toInt()
      // Below the threshold a bare "m" was already classified as minutes by [unitKindFor], so
      // anything reaching here with "m" is metres. Kept explicit so the two cannot drift apart.
      else -> value
    }
  }

  private fun weightUnit(t: Token): WeightUnit {
    val u = vocab.lower(t.text)
    return if (u in vocab.units["lb"].orEmpty() || u == "#") WeightUnit.LB else WeightUnit.KG
  }

  private fun clockSeconds(s: String): Int {
    val parts = s.split(':')
    return when (parts.size) {
      2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
      3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
      else -> 0
    }
  }

  private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}

/**
 * Trim whitespace and any trailing or leading run of separator punctuation together.
 *
 * `trimEnd(',', '-', ':')` stops at the first character outside its set, so "Back Squat - " keeps
 * its trailing space once the dash is removed — and a name with a trailing space then fails every
 * comparison against the exercise library. That was a real bug.
 */
internal fun String.trimSeparators(): String =
  trim()
    .replace(Regex("""^[\s,;:.\-@|·•]+"""), "")
    .replace(Regex("""[\s,;:.\-@|·•]+$"""), "")
    .trim()

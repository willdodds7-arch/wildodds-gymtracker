package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex
import com.wildodds.gymtracker.data.importer.resolve.Fuzzy
import com.wildodds.gymtracker.data.importer.resolve.Metaphone
import com.wildodds.gymtracker.data.importer.resolve.Normalise
import com.wildodds.gymtracker.data.importer.resolve.SemanticGuards
import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary

/**
 * Resolves a parsed exercise name to a canonical movement.
 *
 * Every unmatched name costs the user a confirmation tap, and "Push Press doesn't resolve" is an
 * embarrassing miss — so this reaches much further than the 49-line version it replaces, which was
 * normalise → abbreviation → alias → a 63-entry muscle map. It now searches a 950-movement indexed
 * library through eight layers, **all of which run**, with the best score winning rather than the
 * first hit.
 *
 * The honesty is unchanged and is the constraint the reach has to respect: EXACT / ALIAS / INFERRED
 * / UNRESOLVED still mean what they meant, the verbatim [ExerciseRef.original] is kept forever, and
 * a fuzzy match that crosses a [SemanticGuards] distinction is returned as INFERRED with the
 * candidate offered — never as EXACT. A typo fix must never be able to flip front squat to back
 * squat.
 *
 * Anything below EXACT carries the **top three ranked candidates**, so the review screen is one tap
 * rather than a search, and every result records WHICH LAYER produced it ([ExerciseRef.via]) so hit
 * rate per branch is measurable offline from the corpus.
 */
object ExerciseResolver {

  private val vocab = ReaderVocabulary

  /** Score below which a match is not offered at all. */
  private const val FLOOR = 0.45

  /** Score at or above which a fuzzy match is trusted as ALIAS rather than merely INFERRED. */
  private const val TRUSTED = 0.86

  fun normalize(name: String): String = Normalise.key(name)

  /**
   * Tokens that are a prescription rather than part of a movement's name: bare numbers, `3x8`,
   * a number with a unit, a percentage, an RPE or RIR target.
   */
  private val PRESCRIPTION_TOKEN = Regex(
    """(?i)^(\d+(?:[.,]\d+)?(?:kg|kgs|lb|lbs|s|sec|secs|m|min|mins|%)?|\d+\s*[x]\s*\d+|rpe\d*|rir\d*|amrap)$""")

  /** [norm] with its prescription tokens removed, or [norm] unchanged when none are present. */
  private fun withoutPrescription(norm: String): String {
    val words = norm.split(' ').filter { it.isNotBlank() }
    val kept = words.filterNot { PRESCRIPTION_TOKEN.matches(it) }
    // All numbers and nothing else is not a name we can rescue; leave it to the layers below.
    if (kept.isEmpty() || kept.size == words.size) return norm
    return kept.joinToString(" ")
  }

  fun resolve(original: String, aliasLookup: (String) -> String? = { null }): ExerciseRef {
    val norm = Normalise.key(original)
    if (norm.isBlank()) return ExerciseRef(original, original.trim(), ResolutionStatus.UNRESOLVED, 0.0)

    // ── layer 1: a learned per-user alias beats everything ─────────────────────
    // The user told us what this means. Nothing the index infers can outrank that.
    aliasLookup(norm)?.let { canonical ->
      return ExerciseRef(original, canonical, ResolutionStatus.ALIAS, 0.99, via = "learned_alias")
    }

    // ── layer 2: exact and normalised-exact against the index ──────────────────
    ExerciseIndex.byKey[norm]?.let {
      return ExerciseRef(original, it.canonical, ResolutionStatus.EXACT, 0.99, via = "exact")
    }
    ExerciseIndex.byAlias[norm]?.let {
      return ExerciseRef(original, it.canonical, ResolutionStatus.ALIAS, 0.97, via = "bundled_alias")
    }

    // ── layer 2b: the same name with its prescription still attached ───────────
    // A headerless table hands us "Overhead Press 3 8 45kg" as a name, and the fuzzy layers below
    // read that trailing noise as evidence: it scored "Machine Overhead Press" level with
    // "Overhead Press", and "Overhead Press 3x8" phonetically matched "Z Overhead Press" ABOVE the
    // real answer. Both invent a movement out of a number.
    //
    // Stripping is tried only AFTER the raw name has failed both exact layers, so a genuine name
    // with a number in it — "21s Barbell Curl", "1 1/4 Back Squat", "90 90 Hip Stretch" — has
    // already matched itself and never reaches here.
    val stripped = withoutPrescription(norm)
    if (stripped != norm && stripped.isNotBlank()) {
      ExerciseIndex.byKey[stripped]?.let {
        return ExerciseRef(original, it.canonical, ResolutionStatus.EXACT, 0.95, via = "exact_stripped")
      }
      ExerciseIndex.byAlias[stripped]?.let {
        return ExerciseRef(original, it.canonical, ResolutionStatus.ALIAS, 0.93, via = "alias_stripped")
      }
    }

    // ── layer 2c: the same name with its misspellings corrected ─────────────────
    // "Incline Dumbbel Press" is "incline dumbbell press" with one typo, and that exact phrase is a
    // known alias. Correcting word by word against the index's own vocabulary first means the typo
    // costs nothing; leaving it to whole-name fuzzy matching let "dumbbel" be dropped as noise and
    // the name resolve to the BARBELL movement.
    val corrected = spellCorrect(norm)
    if (corrected != norm) {
      val hit = ExerciseIndex.byKey[corrected]?.let { it to "spelling" }
        ?: ExerciseIndex.byAlias[corrected]?.let { it to "spelling_alias" }
      if (hit != null) {
        // A correction is still a guess about what was typed, so the alternatives travel with it.
        val others = candidates(corrected, Normalise.contentTokens(corrected))
          .filter { it.entry.canonical != hit.first.canonical }
          .sortedByDescending { it.score }.take(2)
          .map { ExerciseCandidate(it.entry.canonical, round(it.score.coerceIn(0.0, 1.0)), it.via) }
        val score = if (hit.second == "spelling") 0.92 else 0.90
        return ExerciseRef(original, hit.first.canonical, ResolutionStatus.ALIAS, score,
          listOf(ExerciseCandidate(hit.first.canonical, score, hit.second)) + others, hit.second)
      }
    }

    // ── layers 3-8: score every plausible candidate, best wins ─────────────────
    val queryTokens = Normalise.contentTokens(original)
    val scored = candidates(norm, queryTokens)
      .map { it.copy(score = it.score.coerceIn(0.0, 1.0)) }
      .sortedWith(
        compareByDescending<Scored> { it.score }
          // On a tie, the candidate that adds the FEWEST words the source never said wins. Ranking
          // ties alphabetically was deterministic and wrong: it preferred "Machine Overhead Press"
          // to "Overhead Press" for a query that said nothing about a machine, which is inventing
          // data by sort order.
          .thenBy { (it.entry.tokens - queryTokens).size }
          // Then the name, so two equally-specific candidates still resolve identically everywhere.
          .thenBy { it.entry.canonical })

    val top = scored.take(3).map { ExerciseCandidate(it.entry.canonical, round(it.score), it.via) }
    val best = scored.firstOrNull()

    if (best == null || best.score < FLOOR) {
      // Nothing in the index above the floor. There used to be a ninth layer here that consulted the
      // legacy muscle map; that map is now a view over this same index, so the layer was asking the
      // same question twice and answering INFERRED to whatever the eight layers above had already
      // declined. Saying we do not know is the honest answer, and it is the one that puts the name
      // in front of the user instead of guessing at it.
      return ExerciseRef(original, displayName(original), ResolutionStatus.UNRESOLVED, 0.30, top, "none")
    }

    // A candidate that names equipment the source never mentioned is a different exercise, however
    // well the other words matched: "Chest Supported Row" is not "Chest Supported BARBELL Row".
    val addsEquipment = (best.entry.tokens - queryTokens).any { it in EQUIPMENT_TOKENS }
    val status = when {
      // A blocked near-match is never EXACT, however close it scored. This is the guard that stops
      // a typo fix flipping the movement.
      best.blockedBy != null -> ResolutionStatus.INFERRED
      addsEquipment -> ResolutionStatus.INFERRED
      best.score >= TRUSTED -> ResolutionStatus.ALIAS
      else -> ResolutionStatus.INFERRED
    }
    val confidence = if (best.blockedBy != null) minOf(best.score, 0.55) else best.score

    // An uncertain match is OFFERED, never silently applied. The name the user wrote stays the name
    // (with the candidates attached, so the review screen makes the swap one tap), unless the match
    // is only a spelling correction or a confident widening that adds no equipment.
    // Several equally good candidates is ambiguity, not a match ("Incline Press": bench, chest or
    // machine?) — say so by keeping the source's words.
    val tied = scored.getOrNull(1)?.let { kotlin.math.abs(it.score - best.score) < 0.005 } ?: false
    val keepOriginal = status == ResolutionStatus.INFERRED && (tied || !(
      (best.via == "phonetic_typo" || best.via == "edit_distance") && best.blockedBy == null && !addsEquipment ||
        best.via.startsWith("token_set") && best.score >= 0.8 && !addsEquipment && best.blockedBy == null))
    val name = if (keepOriginal) displayName(original) else best.entry.canonical
    return ExerciseRef(original, name, status, round(confidence), top, best.via)
  }

  /** Equipment words: a candidate may never introduce one the source did not say. */
  private val EQUIPMENT_TOKENS = setOf(
    "barbell", "dumbbell", "kettlebell", "cable", "machine", "smith", "band", "banded", "ez", "trap",
    "hex", "landmine", "sled", "sandbag", "trx", "ring", "plate", "safety", "swiss", "bosu"
  )

  /** The source's own wording, tidied for display: "half kneeling cable chop" → "Half Kneeling Cable Chop". */
  private fun displayName(original: String): String =
    original.trim().replace(Regex("\\s+"), " ").split(' ').joinToString(" ") { w ->
      if (w.length <= 3 && w.uppercase() == w && w.any { it.isLetter() }) w
      else w.lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
    }

  /** Every word the index knows, for per-word spelling correction. */
  private val vocabulary: Set<String> by lazy {
    HashSet<String>().apply {
      for (e in ExerciseIndex.entries) {
        addAll(e.key.split(' '))
        for (a in e.aliases) addAll(Normalise.key(a).split(' '))
      }
      removeAll { it.length < 3 }
    }
  }

  /** Each unknown word → the nearest known word, when exactly one is close enough. */
  private fun spellCorrect(norm: String): String =
    norm.split(' ').joinToString(" ") { w ->
      if (w.length < 5 || w in vocabulary || w.any { it.isDigit() }) w
      else {
        val budget = if (w.length >= 8) 2 else 1
        val near = vocabulary.filter { kotlin.math.abs(it.length - w.length) <= budget }
          .map { it to Fuzzy.damerauLevenshtein(w, it, budget) }
          .filter { it.second <= budget }
        val best = near.minOfOrNull { it.second }
        val winners = near.filter { it.second == best }.map { it.first }
        winners.singleOrNull() ?: w
      }
    }

  // ── candidate generation ─────────────────────────────────────────────────────

  private data class Scored(
    val entry: ExerciseIndex.Entry,
    val score: Double,
    val via: String,
    val blockedBy: String? = null
  )

  /**
   * Every candidate worth scoring, from the posting lists rather than from a full scan.
   *
   * A name shares at least one content word with almost every plausible match, and a phonetic code
   * with the rest, so intersecting postings finds everything a linear pass would while touching a
   * few dozen entries instead of 950.
   */
  private fun candidates(norm: String, queryTokens: Set<String>): List<Scored> {
    if (queryTokens.isEmpty()) return emptyList()

    val pool = LinkedHashSet<ExerciseIndex.Entry>()
    for (t in queryTokens) {
      ExerciseIndex.postings[t]?.let { pool += it }
      // A misspelled word has no posting of its own, so reach it by sound.
      val code = Metaphone.encode(t)
      if (code.isNotEmpty()) ExerciseIndex.phoneticPostings[code]?.let { pool += it }
    }
    // A short query with no posting hits at all (every word misspelled) still deserves a look, but
    // only across the entries whose length is in the right neighbourhood.
    if (pool.isEmpty()) {
      pool += ExerciseIndex.entries.filter {
        kotlin.math.abs(it.key.length - norm.length) <= Fuzzy.budgetFor(norm.length) + 2
      }
    }

    return pool.mapNotNull { e -> score(norm, queryTokens, e) }
  }

  /**
   * How well one index entry matches the query, and by which layer. Every layer that applies is
   * evaluated and the strongest wins — first-match-wins would let a weak abbreviation hit pre-empt
   * a strong token-set match.
   */
  private fun score(norm: String, queryTokens: Set<String>, e: ExerciseIndex.Entry): Scored? {
    val blocked = SemanticGuards.blockedBy(queryTokens, e.tokens)

    var best = 0.0
    var via = "none"
    fun offer(s: Double, layer: String) { if (s > best) { best = s; via = layer } }

    // ── token-set match, ignoring order and stopwords ────────────────────────
    // "barbell back squat" == "back squat (barbell)" == "Squat, Back - BB".
    val overlap = queryTokens.intersect(e.tokens).size
    if (overlap > 0) {
      val union = queryTokens.size + e.tokens.size - overlap
      val jaccard = overlap.toDouble() / union
      // Full containment either way is much stronger than the raw overlap suggests: "Bench Press"
      // inside "Incline Barbell Bench Press" is a real match, just a less specific one.
      val containment = overlap.toDouble() / minOf(queryTokens.size, e.tokens.size)
      var s = 0.55 * jaccard + 0.45 * containment
      // A candidate carrying a qualifier the query never mentioned is a SUBSTITUTION, not a match:
      // plain "Deadlift" must not silently become "Romanian Deadlift".
      if (SemanticGuards.candidateOverQualifies(queryTokens, e.tokens)) s *= 0.6
      offer(s, if (queryTokens == e.tokens) "token_set_exact" else "token_set")
    }

    // ── equipment-qualifier decomposition ────────────────────────────────────
    // "machine chest press" = "Chest Press" + the machine it is already known to use. A query word
    // is only explained away when the candidate's metadata genuinely ACCOUNTS for it.
    //
    // The agreement check is the whole point. Explaining "dumbbell" away against a barbell entry
    // would make "incline DB press" resolve to "Incline Bench Press" — a silent change of implement,
    // which is exactly the kind of confident wrong answer this subsystem must not produce.
    if (overlap > 0) {
      val unmatched = queryTokens - e.tokens
      val explained = unmatched.count { t ->
        val asEquipment = EQUIPMENT_WORDS[t]
        when {
          asEquipment != null -> asEquipment == e.equipment
          t == "unilateral" -> e.unilateral
          else -> false
        }
      }
      // …and the candidate may not bring words of its own: "seated dumbbell press" is explained by a
      // dumbbell entry, but "Seated ARNOLD Press" adds a movement the source never named.
      if (unmatched.isNotEmpty() && explained == unmatched.size && (e.tokens - queryTokens).isEmpty()) {
        offer(0.88 + 0.1 * (overlap.toDouble() / e.tokens.size), "equipment_decomposition")
      }
    }

    // ── phonetic + bounded edit distance, per word ───────────────────────────
    // Reached only when the token sets do not already agree; this is the typo layer.
    if (best < TRUSTED && queryTokens.size == e.tokens.size) {
      var matched = 0
      var totalPenalty = 0.0
      val remaining = e.tokens.toMutableSet()
      for (q in queryTokens) {
        val hit = remaining.firstOrNull { c ->
          c == q || Metaphone.sounds(q, c) ||
            Fuzzy.damerauLevenshtein(q, c, Fuzzy.budgetFor(maxOf(q.length, c.length))) <=
              Fuzzy.budgetFor(maxOf(q.length, c.length))
        }
        if (hit != null) {
          remaining -= hit
          matched++
          if (hit != q) totalPenalty += 0.07
        }
      }
      if (matched == queryTokens.size) {
        offer((0.92 - totalPenalty).coerceAtLeast(0.5), "phonetic_typo")
      }
    }

    // ── whole-string edit distance, for names with no shared tokens at all ────
    if (best == 0.0) {
      val budget = Fuzzy.budgetFor(maxOf(norm.length, e.key.length))
      if (budget > 0) {
        val d = Fuzzy.damerauLevenshtein(norm, e.key, budget)
        if (d <= budget) offer(0.80 - 0.08 * d, "edit_distance")
      }
    }

    if (best <= 0.0) return null
    // A blocked match is still offered as a candidate — it is very often what the user meant, and
    // showing it makes the review one tap. It just cannot be trusted automatically.
    return Scored(e, best, via, blocked)
  }

  /**
   * Equipment words, mapped to the equipment tag they assert. A word only qualifies a candidate
   * when the candidate is ACTUALLY that equipment — see the decomposition layer.
   *
   * Bar TYPES (`ez`, `trap`, `hex`, `safety`, `landmine`) are deliberately NOT here: a trap-bar
   * deadlift and an EZ-bar curl are their own movements with their own entries, and explaining the
   * bar away silently turned "Hex Bar Deadlift" into a conventional "Deadlift".
   */
  private val EQUIPMENT_WORDS: Map<String, String> = mapOf(
    "barbell" to "BARBELL", "bar" to "BARBELL",
    "dumbbell" to "DUMBBELL", "kettlebell" to "KETTLEBELL",
    "cable" to "CABLE", "machine" to "MACHINE", "plate" to "MACHINE",
    "band" to "BAND", "banded" to "BAND",
    "smith" to "SMITH_MACHINE", "bodyweight" to "BODYWEIGHT",
    "sled" to "SLED", "prowler" to "SLED", "sandbag" to "SANDBAG"
  )

  private fun round(d: Double): Double = kotlin.math.round(d * 100) / 100.0

  private fun titleCase(s: String): String =
    s.split(" ").filter { it.isNotBlank() }.joinToString(" ") { w ->
      if (w.length <= 3 && w.uppercase() == w) w
      else w.replaceFirstChar { it.uppercaseChar() }
    }

  /**
   * The metadata for a resolved movement, for the "import as a custom exercise" path — the pattern
   * and equipment are pre-filled from the parse so adding an unknown movement is one tap rather
   * than a form. Null when the name did not resolve to anything in the index.
   */
  fun metadataFor(canonical: String): ExerciseIndex.Entry? = ExerciseIndex.find(canonical)
}

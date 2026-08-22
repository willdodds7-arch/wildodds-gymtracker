package com.wildodds.gymtracker.data.importer.resolve

import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary

/**
 * The bundled exercise library: ~950 movements with pattern, primary and secondary muscles,
 * equipment, a unilateral flag and curated aliases.
 *
 * It is the ONLY answer in the app to "what does this exercise train". The muscle map that used to
 * sit beside it covered 63 names with a hand-written if-ladder and disagreed with this file on the
 * ones they shared.
 *
 * Loaded from `resources/reader/exercises.tsv`, which is **generated** by
 * `tools/build_exercise_index.py` from a curated set of base movements crossed with the qualifiers
 * that make sense for each. Generating it is what keeps the metadata consistent: the same movement
 * cannot be tagged `HORIZONTAL_PUSH` on one row and `PUSH` on another, and adding a base movement
 * adds its whole family.
 *
 * TSV rather than JSON, and lazily loaded rather than parsed at startup: it is ~51 KB, it parses
 * with a `split` per line and no per-field allocation, and nothing touches it until the first
 * import — which most sessions never do.
 *
 * Everything here is precomputed once into the indices the matcher needs, so resolution is a few
 * map lookups plus a bounded scan rather than a linear pass over a thousand names per exercise.
 */
object ExerciseIndex {

  data class Entry(
    val canonical: String,
    val pattern: String,
    /** Muscles doing the lion's share of the work. Never empty; the first is the main mover. */
    val primaryMuscles: List<String>,
    val equipment: String,
    val unilateral: Boolean,
    val aliases: List<String>,
    /** Muscles that assist significantly. Empty for an isolation movement, and for a stretch. */
    val secondaryMuscles: List<String> = emptyList()
  ) {
    /**
     * The main mover. This is the value the index carried before it learned about co-primaries, and
     * it is deliberately still the first element, so every caller that wants one muscle keeps
     * getting the same answer it always did.
     */
    val primaryMuscle: String get() = primaryMuscles.firstOrNull().orEmpty()

    /** The canonical name, normalised — the key every lookup is made on. */
    val key: String = Normalise.key(canonical)
    /** Content words, sorted, for order-insensitive matching. */
    val tokens: Set<String> = Normalise.contentTokens(canonical)
    /** Phonetic codes of the content words, for typo tolerance. */
    val phonetic: List<String> = tokens.map(Metaphone::encode)
  }

  private const val RESOURCE = "/reader/exercises.tsv"

  val entries: List<Entry> by lazy { load() }

  /** Normalised canonical name -> entry. */
  val byKey: Map<String, Entry> by lazy {
    LinkedHashMap<String, Entry>().apply { for (e in ExerciseIndex.entries) putIfAbsent(e.key, e) }
  }

  /** Normalised alias -> entry. An alias never overwrites a canonical name. */
  val byAlias: Map<String, Entry> by lazy {
    LinkedHashMap<String, Entry>().apply {
      for (e in ExerciseIndex.entries) for (a in e.aliases) {
        val k = Normalise.key(a)
        if (k.isNotBlank() && k !in byKey) putIfAbsent(k, e)
      }
    }
  }

  /**
   * Content token -> the entries containing it. The matcher intersects postings for the query's
   * tokens instead of scanning every entry, which is what keeps a 950-entry index cheap enough to
   * run on every line of a 500-line program.
   */
  val postings: Map<String, List<Entry>> by lazy {
    HashMap<String, MutableList<Entry>>().apply {
      for (e in ExerciseIndex.entries) for (t in e.tokens) getOrPut(t) { ArrayList() }.add(e)
    }
  }

  /** Phonetic code -> entries whose name contains a word with that sound. */
  val phoneticPostings: Map<String, List<Entry>> by lazy {
    HashMap<String, MutableList<Entry>>().apply {
      for (e in ExerciseIndex.entries) for (p in e.phonetic) if (p.isNotEmpty()) getOrPut(p) { ArrayList() }.add(e)
    }
  }

  fun find(name: String): Entry? {
    val k = Normalise.key(name)
    return byKey[k] ?: byAlias[k]
  }

  /**
   * The most specific movement whose every content word appears in [text], or null.
   *
   * This is the "does this line name an exercise" question, and it is deliberately a SUBSET test
   * rather than a substring one. Substring matching answered yes to "Benchmark" for "bench" and let
   * a three-word movement lose to a one-word one; requiring whole words and preferring the entry
   * with the most of them means "Incline Bench Press" beats "Bench Press" beats "Press".
   *
   * Candidates come from the posting lists, so this is a scan of the entries sharing a word with the
   * text rather than a pass over all 952.
   */
  fun mentionedIn(text: String): Entry? {
    val tokens = Normalise.contentTokens(text)
    if (tokens.isEmpty()) return null
    var best: Entry? = null
    val seen = HashSet<String>()
    for (t in tokens) {
      for (e in postings[t].orEmpty()) {
        if (e.tokens.size > tokens.size) continue
        if (e.tokens.size <= (best?.tokens?.size ?: 0)) continue
        if (!seen.add(e.key)) continue
        if (tokens.containsAll(e.tokens)) best = e
      }
    }
    return best
  }

  private fun load(): List<Entry> {
    val stream = ExerciseIndex::class.java.getResourceAsStream(RESOURCE)
      ?: error("Exercise index missing from the classpath at $RESOURCE")
    val out = ArrayList<Entry>(1024)
    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
      for (line in lines) {
        if (line.isBlank() || line.startsWith("#")) continue
        val f = line.split('\t')
        if (f.size < 5) continue
        fun list(i: Int) =
          f.getOrNull(i).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val primaries = list(2)
        if (primaries.isEmpty()) continue
        out += Entry(
          canonical = f[0].trim(),
          pattern = f[1].trim(),
          primaryMuscles = primaries,
          equipment = f[3].trim(),
          unilateral = f[4].trim() == "1",
          aliases = list(5),
          secondaryMuscles = list(6)
        )
      }
    }
    return out
  }
}

/**
 * Name normalisation, shared by the index and every matcher layer so the two can never disagree
 * about what "the same name" means.
 */
object Normalise {

  private val vocab = ReaderVocabulary

  /** Words that carry no identity: dropping them lets "Squat, Back — BB" match "Barbell Back Squat". */
  val STOPWORDS = setOf(
    "the", "a", "an", "with", "and", "or", "of", "on", "in", "to", "for", "your", "each",
    "machine", "exercise", "movement", "variation", "style", "grip", "position", "bar"
  )

  /**
   * Lowercase, strip punctuation and possessives, collapse whitespace, expand whole-token
   * abbreviations, and singularise. Idempotent, and locale-independent — `Locale.ROOT` throughout,
   * because a Turkish device lowercasing `I` to a dotless `ı` would break every `Incline` lookup.
   */
  fun key(name: String): String {
    var s = vocab.lower(name).trim()
    s = s.replace("'s ", " ").removeSuffix("'s")
    s = s.replace(Regex("""[()\[\]{}"'`.,;:!?/\\|&+]"""), " ")
    s = s.replace(Regex("""[-_]"""), " ")
    s = s.replace(Regex("""\s+"""), " ").trim()
    if (s.isBlank()) return s
    s = vocab.expandAbbreviations(s)
    return s.split(' ').joinToString(" ") { singular(it) }.trim()
  }

  /** The identity-carrying words of a name: normalised, de-stopworded, order-insensitive. */
  fun contentTokens(name: String): Set<String> =
    key(name).split(' ').filter { it.isNotBlank() && it !in STOPWORDS }.toSet()

  /**
   * English plural stripping, deliberately shallow. "Curls" -> "curl", "presses" -> "press",
   * "flies" -> "fly". Words that end in `ss` are left alone, or "press" becomes "pres" and every
   * pressing movement stops matching.
   */
  fun singular(word: String): String = when {
    // Three-letter plurals are real and common in this vocabulary: "ups", "abs", "dls". Only two
    // letters or fewer are left alone.
    word.length <= 2 -> word
    word.endsWith("ss") -> word
    word.endsWith("ies") -> word.dropLast(3) + "y"
    word.endsWith("ches") || word.endsWith("shes") || word.endsWith("sses") ||
      word.endsWith("xes") || word.endsWith("zes") -> word.dropLast(2)
    word.endsWith("s") && !word.endsWith("us") && !word.endsWith("is") -> word.dropLast(1)
    else -> word
  }
}

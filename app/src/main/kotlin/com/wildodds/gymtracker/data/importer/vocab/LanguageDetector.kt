package com.wildodds.gymtracker.data.importer.vocab

import java.util.Locale

/**
 * Decides, from the document itself, whether it is written in a language the vocabulary knows — and
 * if so, rewrites its structural words into English at the pipeline's edge.
 *
 * The vocabulary has carried German, Spanish, French and Portuguese words for sets, reps, session,
 * week, day and rest since the rebuild, behind a comment saying they applied "only behind an
 * explicit language hint". Nothing ever set the hint, so a German program parsed by accident when
 * `3x8` happened to be universal and not at all when it said `3 Sätze à 8 Wdh`.
 *
 * **Translation at the boundary, not a second grammar.** The alternative — teaching the tokeniser,
 * the line classifier, the segmenter and the layout scorers about four more languages — would be
 * four more chances for each of them to disagree. Rewriting `Woche` to `Week` once, before any of
 * them run, means every downstream rule that already works keeps working. It is the same reason the
 * spreadsheet path projects columns instead of teaching every reader about offsets.
 *
 * **Detection is conservative on purpose.** The risk is asymmetric: an English program that says
 * "series" losing to a Spanish reading is a corrupted import, while a German program left in German
 * is merely the behaviour we already had. So a language must be named by at least
 * [MIN_DISTINCT_WORDS] different structural words, those words must not be English ones, and it
 * must beat every other language outright. A tie detects nothing.
 *
 * Pure and deterministic: same text in, same decision out, no `Locale.getDefault()` anywhere.
 */
object LanguageDetector {

  /**
   * Fewer than this and the evidence is a coincidence. Three distinct structural words in one
   * document is not.
   */
  const val MIN_DISTINCT_WORDS = 3

  /**
   * Words that appear in a foreign list AND are ordinary English gym vocabulary. They are still
   * TRANSLATED once a language is settled — "Pause" in a German program does mean rest — but they
   * may never be the evidence that settles it.
   */
  private val ALSO_ENGLISH = setOf(
    "rep", "reps", "set", "sets", "series", "training", "day", "week", "rest", "pause"
  )

  /** The English word each concept becomes. Capitalised where it is normally a heading. */
  private val ENGLISH = mapOf(
    "sets" to "sets",
    "reps" to "reps",
    "session" to "Session",
    "week" to "Week",
    "day" to "Day",
    "rest" to "Rest"
  )

  /**
   * @param code the ISO code detected, or "" for none (English, or not enough evidence).
   * @param words the distinct foreign words that were the evidence, sorted, for the flag text.
   */
  data class Detection(val code: String, val words: List<String>) {
    val detected: Boolean get() = code.isNotEmpty()
  }

  private val NONE = Detection("", emptyList())

  /** A whole-token translation table for one language, built once per detection. */
  class Dialect internal constructor(val code: String, private val table: Map<String, String>) {

    /**
     * Rewrites the structural words in [line], leaving everything else — exercise names above all —
     * exactly as it was. Case is carried over from the source token so a heading stays a heading.
     */
    fun translate(line: String): String {
      if (line.isBlank()) return line
      val out = StringBuilder(line.length)
      var i = 0
      while (i < line.length) {
        if (!line[i].isLetter()) { out.append(line[i]); i++; continue }
        val start = i
        while (i < line.length && line[i].isLetter()) i++
        val word = line.substring(start, i)
        val english = table[word.lowercase(Locale.ROOT)]
        out.append(if (english == null) word else matchCase(word, english))
      }
      return out.toString()
    }

    private fun matchCase(source: String, replacement: String): String = when {
      source.all { it.isUpperCase() } && source.length > 1 -> replacement.uppercase(Locale.ROOT)
      source.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercaseChar() }
      else -> replacement.lowercase(Locale.ROOT)
    }
  }

  /** The languages the vocabulary carries, each as a word -> English translation table. */
  private val tables: Map<String, Map<String, String>> by lazy {
    ReaderVocabulary.languages.mapValues { (_, concepts) ->
      LinkedHashMap<String, String>().apply {
        for ((concept, words) in concepts) {
          val english = ENGLISH[concept] ?: continue
          for (w in words) {
            val k = w.lowercase(Locale.ROOT)
            if (k.isNotBlank()) putIfAbsent(k, english)
          }
        }
      }
    }
  }

  fun detect(text: String): Detection {
    if (text.isBlank()) return NONE
    val words = tokenise(text)
    if (words.isEmpty()) return NONE

    // Distinct evidence words per language, so one word repeated on forty lines is still one word.
    val hits = LinkedHashMap<String, MutableSet<String>>()
    for ((code, table) in tables) {
      val found = sortedSetOf<String>()
      for (w in words) if (w !in ALSO_ENGLISH && table.containsKey(w)) found.add(w)
      if (found.isNotEmpty()) hits[code] = found
    }

    val best = hits.maxByOrNull { it.value.size } ?: return NONE
    if (best.value.size < MIN_DISTINCT_WORDS) return NONE
    // An outright win, or nothing. Two languages tied on the same evidence means the evidence is
    // words they share, which tells us nothing about which one this is.
    if (hits.count { it.value.size == best.value.size } > 1) return NONE
    return Detection(best.key, best.value.toList())
  }

  /** The translation table for a detection, or null when there is nothing to translate. */
  fun dialectFor(detection: Detection): Dialect? {
    if (!detection.detected) return null
    val table = tables[detection.code] ?: return null
    return Dialect(detection.code, table)
  }

  fun displayName(code: String): String = when (code) {
    "de" -> "German"
    "es" -> "Spanish"
    "fr" -> "French"
    "pt" -> "Portuguese"
    else -> code
  }

  private fun tokenise(text: String): List<String> {
    val out = ArrayList<String>(text.length / 6 + 1)
    var i = 0
    while (i < text.length) {
      if (!text[i].isLetter()) { i++; continue }
      val start = i
      while (i < text.length && text[i].isLetter()) i++
      out += text.substring(start, i).lowercase(Locale.ROOT)
    }
    return out
  }
}

package com.wildodds.gymtracker.data.importer.resolve

/**
 * Bounded Damerau-Levenshtein distance.
 *
 * Bounded matters: an unbounded edit distance over 950 names for every line of a 500-line program is
 * a lot of work to discover that "Prowler Sprints" is nothing like "Preacher Curl". With a cap the
 * inner loop abandons a row as soon as every cell in it exceeds the budget, which turns almost every
 * comparison into a few dozen operations.
 *
 * Damerau rather than plain Levenshtein because the mistake people actually make is a TRANSPOSITION
 * — "Bnech Press", "Squta" — and plain Levenshtein charges two edits for that, which is usually
 * enough to push a real typo past the threshold.
 */
object Fuzzy {

  /** @return the distance, or [limit] + 1 when it exceeds [limit]. */
  fun damerauLevenshtein(a: String, b: String, limit: Int): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    val n = a.length
    val m = b.length
    var prevPrev = IntArray(m + 1)
    var prev = IntArray(m + 1) { it }
    var cur = IntArray(m + 1)

    for (i in 1..n) {
      cur[0] = i
      var rowMin = cur[0]
      // Only the diagonal band within the limit can ever contribute a result under it.
      val from = maxOf(1, i - limit)
      val to = minOf(m, i + limit)
      if (from > 1) cur[from - 1] = limit + 1
      for (j in from..to) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        var v = minOf(
          cur[j - 1] + 1,        // insertion
          prev[j] + 1,           // deletion
          prev[j - 1] + cost     // substitution
        )
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
          v = minOf(v, prevPrev[j - 2] + 1)   // transposition
        }
        cur[j] = v
        if (v < rowMin) rowMin = v
      }
      if (to < m) cur[to + 1] = limit + 1
      if (rowMin > limit) return limit + 1
      val t = prevPrev; prevPrev = prev; prev = cur; cur = t
    }
    return if (prev[m] > limit) limit + 1 else prev[m]
  }

  /**
   * The edit budget for a word of this length. Scaling with length is the point: one edit in a
   * four-letter word is a different claim from one edit in a fourteen-letter word, and a fixed cap
   * either rejects real typos in long names or accepts nonsense in short ones.
   *
   * Words of three characters or fewer get NO budget at all — at that length almost every word is
   * one edit from another, and "row" would match "raw", "rop" and "bow".
   */
  fun budgetFor(length: Int): Int = when {
    length <= 3 -> 0
    length <= 6 -> 1
    length <= 11 -> 2
    else -> 3
  }
}

/**
 * A reduced double-metaphone: phonetic codes for English, with a primary and — where English
 * genuinely branches — an alternate.
 *
 * It exists for one job: matching a name someone spelled by ear. "Roumanian Deadlift",
 * "Bulgarian Split Squad", "Sissy Squat" vs "Sissi Squat". Edit distance alone gets some of those;
 * phonetics gets the ones where the spelling diverges more than the sound.
 *
 * Deliberately a REDUCTION of the full algorithm rather than a port of it: the full double metaphone
 * carries a large amount of machinery for Slavic, Germanic and Romance name endings that has no
 * bearing on gym vocabulary, and every branch of it is a branch that can misfire. The rules kept
 * here are the ones that fire on exercise names. `docs/reader/04-gaps.md` records that this is a
 * reduction, so nobody later assumes a fidelity it does not claim.
 */
object Metaphone {

  fun encode(word: String): String {
    val w = word.uppercase(java.util.Locale.ROOT).filter { it in 'A'..'Z' }
    if (w.isEmpty()) return ""
    val out = StringBuilder(w.length)
    var i = 0

    // Silent leading letters: KN(ee), GN, PN, WR, PS.
    if (w.length >= 2 && w.take(2) in setOf("KN", "GN", "PN", "WR", "PS")) i = 1
    // A leading X sounds like S ("Xiphoid"); a leading WH like W.
    if (w[0] == 'X') { out.append('S'); i = 1 }

    while (i < w.length) {
      val c = w[i]
      val next = w.getOrNull(i + 1)
      val prev = w.getOrNull(i - 1)
      // A doubled consonant sounds once ("Barbell", "Kettlebell").
      if (c == prev && c != 'C') { i++; continue }

      when (c) {
        'A', 'E', 'I', 'O', 'U', 'Y' -> if (i == 0) out.append('A')  // only a leading vowel counts
        'B' -> if (!(i == w.lastIndex && prev == 'M')) out.append('B')  // silent B in "thumb"
        'C' -> when {
          next == 'H' -> { out.append('X'); i++ }                    // CH -> "ch"
          next == 'I' && w.getOrNull(i + 2) == 'A' -> { out.append('X'); i++ }
          next == 'E' || next == 'I' || next == 'Y' -> out.append('S')
          else -> out.append('K')
        }
        'D' -> if (next == 'G') { out.append('J'); i += 2 } else out.append('T')
        'G' -> when {
          next == 'H' -> { if (i == 0 || (prev != null && prev in "AEIOU")) out.append('K'); i++ }
          next == 'N' -> i++                                          // silent in "sign"
          next == 'E' || next == 'I' || next == 'Y' -> out.append('J')
          else -> out.append('K')
        }
        'H' -> if (prev != null && prev in "AEIOU" && (next == null || next !in "AEIOU")) Unit
               else out.append('H')
        'K' -> if (prev != 'C') out.append('K')
        'P' -> if (next == 'H') { out.append('F'); i++ } else out.append('P')
        'Q' -> out.append('K')
        'S' -> when {
          next == 'H' -> { out.append('X'); i++ }
          next == 'I' && (w.getOrNull(i + 2) == 'O' || w.getOrNull(i + 2) == 'A') -> out.append('X')
          else -> out.append('S')
        }
        'T' -> when {
          next == 'H' -> { out.append('0'); i++ }                     // "th"
          next == 'I' && (w.getOrNull(i + 2) == 'O' || w.getOrNull(i + 2) == 'A') -> out.append('X')
          else -> out.append('T')
        }
        'V' -> out.append('F')
        'W' -> if (next != null && next in "AEIOU") out.append('W')
        'X' -> out.append("KS")
        'Z' -> out.append('S')
        'F', 'J', 'L', 'M', 'N', 'R' -> out.append(c)
        else -> Unit
      }
      i++
    }
    return out.toString()
  }

  /** True when two words sound the same. Empty codes never match — that would match everything. */
  fun sounds(a: String, b: String): Boolean {
    val x = encode(a)
    if (x.isEmpty()) return false
    return x == encode(b)
  }
}

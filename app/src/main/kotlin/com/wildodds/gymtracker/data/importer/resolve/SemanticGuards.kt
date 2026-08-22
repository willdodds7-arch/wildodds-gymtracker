package com.wildodds.gymtracker.data.importer.resolve

/**
 * The distinctions fuzzy matching is never allowed to cross.
 *
 * Every layer below exact matching trades precision for reach, and the cost of a wrong match is
 * asymmetric: an unresolved name costs the user one confirmation tap, while a CONFIDENTLY WRONG
 * match writes a different exercise into their program and they may never notice. Front squat and
 * back squat are two edits apart. Pull-up and pull-down are three. RDL and deadlift share a stem.
 * Without these guards, a typo fix can silently flip the movement.
 *
 * So each guard names a set of mutually-exclusive markers. If the query asserts one side and the
 * candidate asserts the other, the match is BLOCKED: it can still be offered as an [candidate], but
 * it can never be returned as EXACT, and the review screen shows it as something to confirm.
 *
 * A guard where NEITHER side is asserted does not block — "Squat" versus "Front Squat" is a
 * generalisation, not a contradiction, and blocking it would make the index unusable.
 */
object SemanticGuards {

  /** One axis of meaning, and the marker words that place a name on it. */
  data class Guard(val name: String, val sides: List<Set<String>>)

  val ALL: List<Guard> = listOf(
    Guard("bar position", listOf(setOf("front"), setOf("back"))),
    Guard("bar height", listOf(setOf("high", "highbar"), setOf("low", "lowbar"))),
    Guard("bench angle", listOf(setOf("incline"), setOf("decline"))),
    Guard("pull direction", listOf(setOf("pullup", "chinup", "pull", "chin"), setOf("pulldown", "pushdown", "down"))),
    Guard("push vs pull", listOf(setOf("press", "push"), setOf("pull", "row", "curl"))),
    Guard("body position", listOf(setOf("seated", "sitting"), setOf("standing", "stand"))),
    Guard("body position 2", listOf(setOf("lying", "prone", "supine"), setOf("standing", "seated"))),
    Guard("hinge vs pull", listOf(setOf("romanian", "rdl", "stiff", "straight"), setOf("conventional", "sumo"))),
    Guard("grip width", listOf(setOf("close", "narrow"), setOf("wide"))),
    Guard("laterality", listOf(setOf("single", "one", "unilateral", "alternating"), setOf("double", "bilateral"))),
    Guard("direction", listOf(setOf("forward", "front"), setOf("reverse", "backward", "rear"))),
    Guard("tempo", listOf(setOf("eccentric", "negative"), setOf("concentric"))),
    Guard("assistance", listOf(setOf("assisted"), setOf("weighted")))
  )

  /**
   * True when [queryTokens] and [candidateTokens] land on OPPOSITE sides of some guard.
   *
   * @return the guard's name when blocked, null when the match is allowed.
   */
  fun blockedBy(queryTokens: Set<String>, candidateTokens: Set<String>): String? {
    for (g in ALL) {
      val qSide = sideOf(queryTokens, g)
      val cSide = sideOf(candidateTokens, g)
      if (qSide != null && cSide != null && qSide != cSide) return g.name
    }
    return null
  }

  /** Which side of a guard a name sits on: 0, 1, or null when it says nothing either way. */
  private fun sideOf(tokens: Set<String>, g: Guard): Int? {
    var found: Int? = null
    for ((i, side) in g.sides.withIndex()) {
      if (tokens.any { it in side }) {
        // A name that asserts BOTH sides ("Seated Standing Press") asserts neither usefully.
        if (found != null && found != i) return null
        found = i
      }
    }
    return found
  }

  /**
   * The special case that no token guard catches: a compound movement name whose STEM differs.
   * "Romanian Deadlift" and "Deadlift" share every token of the shorter name, so token-set matching
   * scores them highly — but they are different lifts, and the qualifier is the whole difference.
   *
   * This is a subset check rather than a guard because the asymmetry matters: a query of "Deadlift"
   * may legitimately mean the plain lift, while a query of "Romanian Deadlift" never means it.
   */
  val QUALIFYING_PREFIXES: Set<String> = setOf(
    "romanian", "stiff", "straight", "sumo", "deficit", "block", "rack", "snatch",
    "front", "back", "overhead", "zercher", "hack", "goblet", "bulgarian", "split",
    "incline", "decline", "close", "wide", "reverse", "single", "pause", "paused",
    "banded", "assisted", "weighted", "seated", "standing", "lying", "kneeling"
  )

  /**
   * True when the CANDIDATE carries a qualifying prefix the query does not. Matching plain
   * "Deadlift" to "Romanian Deadlift" is a silent substitution; the reverse (query has the
   * qualifier, candidate does not) is a generalisation and is allowed, at a lower score.
   */
  fun candidateOverQualifies(queryTokens: Set<String>, candidateTokens: Set<String>): Boolean =
    (candidateTokens - queryTokens).any { it in QUALIFYING_PREFIXES }
}

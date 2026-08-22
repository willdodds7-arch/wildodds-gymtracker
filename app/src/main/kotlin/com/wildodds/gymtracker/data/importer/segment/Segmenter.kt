package com.wildodds.gymtracker.data.importer.segment

import kotlin.math.ln

/**
 * Resolves the per-line evidence from [LineClassifier] into ONE consistent labelling of the whole
 * document, with a small Viterbi pass over legal transitions.
 *
 * Why not greedy: the previous importer decided each line on its own, first keyword wins, and could
 * never revisit. That is why a document title starting with "Upper" became a phantom empty session,
 * and why a stray "Monday" inside a note started a new day. A per-line decision cannot use the fact
 * that the next line is a header, or that a session with no exercises under it is probably not a
 * session — but a sequence decision can, because those become transition costs.
 *
 * The state space is tiny (ten roles) and documents are short, so this is O(lines x 100) and runs in
 * well under a millisecond for anything a person would paste. Deterministic: ties break toward the
 * lower ordinal, so the same document always labels the same way.
 */
object Segmenter {

  data class Labelled(val features: LineFeatures, val role: LineRole, val confidence: Double)

  /** The score of a labelling, and the labelling. Exposed so the corpus runner can report both. */
  data class Result(val lines: List<Labelled>, val score: Double)

  fun segment(rawLines: List<String>): Result {
    val features = LineClassifier.features(rawLines)
    if (features.isEmpty()) return Result(emptyList(), 0.0)

    val roles = LineRole.entries
    val emissions = features.map { f -> LineClassifier.score(f, features.size) }

    // Viterbi over log scores. Log rather than product, because a 200-line document multiplies 200
    // sub-one numbers and underflows to zero, at which point every path ties and the labelling
    // becomes whatever the iteration order happened to be.
    val n = features.size
    val best = Array(n) { DoubleArray(roles.size) { NEG_INF } }
    val back = Array(n) { IntArray(roles.size) { -1 } }

    for (r in roles.indices) {
      best[0][r] = logEmission(emissions[0], roles[r]) + logStart(roles[r], features[0])
    }
    for (i in 1 until n) {
      for (r in roles.indices) {
        val e = logEmission(emissions[i], roles[r])
        if (e == NEG_INF) continue
        var bestPrev = -1
        var bestScore = NEG_INF
        for (p in roles.indices) {
          if (best[i - 1][p] == NEG_INF) continue
          val t = logTransition(roles[p], roles[r], features[i])
          if (t == NEG_INF) continue
          val v = best[i - 1][p] + t
          // Strictly greater keeps the lower-ordinal previous state on a tie, which is what makes
          // the labelling identical on every run and every device.
          if (v > bestScore) { bestScore = v; bestPrev = p }
        }
        if (bestPrev >= 0) { best[i][r] = bestScore + e; back[i][r] = bestPrev }
      }
    }

    var endRole = 0
    var endScore = NEG_INF
    for (r in roles.indices) if (best[n - 1][r] > endScore) { endScore = best[n - 1][r]; endRole = r }

    val path = IntArray(n)
    path[n - 1] = endRole
    for (i in n - 1 downTo 1) path[i - 1] = back[i][path[i]].takeIf { it >= 0 } ?: LineRole.NOTE.ordinal

    val labelled = features.mapIndexed { i, f ->
      val role = roles[path[i]]
      // The reported confidence is how far this role beat the runner-up for this line — a line that
      // was a coin flip between EXERCISE and SESSION_HEADER should not look certain downstream.
      val scores = emissions[i]
      val mine = scores[role] ?: 0.0
      val other = scores.filterKeys { it != role }.values.maxOrNull() ?: 0.0
      Labelled(f, role, margin(mine, other))
    }
    return Result(labelled, endScore)
  }

  private fun margin(mine: Double, other: Double): Double = when {
    mine <= 0.0 -> 0.3
    other <= 0.0 -> 1.0
    else -> (0.5 + 0.5 * ((mine - other) / mine)).coerceIn(0.3, 1.0)
  }

  private const val NEG_INF = Double.NEGATIVE_INFINITY

  private fun logEmission(scores: Map<LineRole, Double>, role: LineRole): Double {
    val v = scores[role] ?: return NEG_INF
    return if (v <= 0.0) NEG_INF else ln(v)
  }

  /** Only a title, a phase, a week, a session or a note can open a document. */
  private fun logStart(role: LineRole, f: LineFeatures): Double = when (role) {
    LineRole.PROGRAM_TITLE -> ln(0.9)
    LineRole.PHASE_HEADER, LineRole.WEEK_HEADER, LineRole.SESSION_HEADER -> ln(0.8)
    LineRole.EXERCISE -> ln(0.6)      // a bare list of exercises with no header at all is common
    LineRole.NOTE, LineRole.IGNORE -> ln(0.4)
    LineRole.SECTION_LABEL -> ln(0.3)
    LineRole.REST_DAY -> ln(0.2)
    // A continuation with nothing to continue is not a thing.
    LineRole.SET_DETAIL_CONTINUATION -> NEG_INF
  }

  /**
   * How willing the labelling is to move from one role to the next.
   *
   * These are the structural rules of a written program, stated once: a continuation must follow
   * something to continue; a section label belongs inside a session; a week header resets the day
   * numbering. Impossible transitions are `NEG_INF` and are genuinely impossible, not merely
   * expensive — that is what stops a run of exercises being relabelled as ten separate sessions.
   */
  private fun logTransition(from: LineRole, to: LineRole, next: LineFeatures): Double {
    // A continuation only ever follows an exercise or another continuation.
    if (to == LineRole.SET_DETAIL_CONTINUATION &&
        from != LineRole.EXERCISE && from != LineRole.SET_DETAIL_CONTINUATION) return NEG_INF
    // A title appears once, at the top.
    if (to == LineRole.PROGRAM_TITLE && next.index > 2) return NEG_INF

    val p = when (from) {
      LineRole.PROGRAM_TITLE -> when (to) {
        LineRole.PHASE_HEADER, LineRole.WEEK_HEADER, LineRole.SESSION_HEADER -> 0.9
        LineRole.NOTE, LineRole.IGNORE -> 0.6
        LineRole.EXERCISE -> 0.5
        LineRole.SECTION_LABEL -> 0.4
        LineRole.REST_DAY -> 0.2
        else -> 0.1
      }
      LineRole.PHASE_HEADER -> when (to) {
        LineRole.WEEK_HEADER, LineRole.SESSION_HEADER -> 0.9
        LineRole.NOTE -> 0.5
        LineRole.EXERCISE -> 0.4
        else -> 0.2
      }
      LineRole.WEEK_HEADER -> when (to) {
        LineRole.SESSION_HEADER -> 0.9
        LineRole.REST_DAY -> 0.5
        LineRole.EXERCISE, LineRole.NOTE, LineRole.SECTION_LABEL -> 0.45
        // Two week headers in a row means an empty week, which happens but is unusual.
        LineRole.WEEK_HEADER -> 0.2
        else -> 0.15
      }
      LineRole.SESSION_HEADER -> when (to) {
        LineRole.EXERCISE -> 0.9
        LineRole.SECTION_LABEL -> 0.7
        LineRole.NOTE -> 0.5
        // A session header immediately followed by another one means the first had no exercises.
        // Possible (a rest day written as a session) but a strong hint we mislabelled.
        LineRole.SESSION_HEADER -> 0.2
        LineRole.REST_DAY -> 0.3
        LineRole.WEEK_HEADER, LineRole.PHASE_HEADER -> 0.25
        else -> 0.15
      }
      LineRole.EXERCISE -> when (to) {
        LineRole.EXERCISE -> 0.9
        LineRole.SET_DETAIL_CONTINUATION -> 0.7
        LineRole.SECTION_LABEL, LineRole.NOTE -> 0.5
        LineRole.SESSION_HEADER -> 0.55
        // A rest day right after a training day's exercises is the normal shape of a week.
        LineRole.REST_DAY -> 0.55
        LineRole.WEEK_HEADER, LineRole.PHASE_HEADER -> 0.5
        else -> 0.2
      }
      LineRole.SET_DETAIL_CONTINUATION -> when (to) {
        LineRole.SET_DETAIL_CONTINUATION -> 0.8
        LineRole.EXERCISE -> 0.8
        LineRole.SESSION_HEADER, LineRole.SECTION_LABEL, LineRole.NOTE -> 0.5
        LineRole.WEEK_HEADER -> 0.4
        else -> 0.2
      }
      LineRole.SECTION_LABEL -> when (to) {
        LineRole.EXERCISE -> 0.95
        LineRole.NOTE -> 0.4
        LineRole.SECTION_LABEL -> 0.2
        else -> 0.25
      }
      LineRole.NOTE -> when (to) {
        LineRole.NOTE -> 0.7
        LineRole.EXERCISE -> 0.7
        LineRole.SESSION_HEADER, LineRole.WEEK_HEADER, LineRole.SECTION_LABEL -> 0.6
        LineRole.REST_DAY -> 0.4
        else -> 0.3
      }
      LineRole.REST_DAY -> when (to) {
        LineRole.SESSION_HEADER -> 0.8
        LineRole.WEEK_HEADER -> 0.6
        LineRole.REST_DAY -> 0.5
        LineRole.NOTE -> 0.4
        LineRole.EXERCISE -> 0.3
        else -> 0.2
      }
      LineRole.IGNORE -> when (to) {
        LineRole.IGNORE -> 0.6
        else -> 0.5
      }
    }
    return ln(p.coerceAtLeast(0.01))
  }
}

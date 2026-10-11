package com.wildodds.gymtracker.data.importer

/**
 * Confidence, aggregated and **explained**.
 *
 * The previous model was three constants (0.90 / 0.60 / 0.35) and two caps (0.55 / 0.30). It was a
 * reasonable sketch and a poor instrument: it could not say WHY a slot scored what it did, so the
 * review screen could only show a number, and a number with no reason behind it is something users
 * learn to ignore.
 *
 * Every score here is the product of named components (see [ConfidenceBreakdown]) and carries the
 * reasons that pulled it down. Multiplicative rather than averaged, deliberately: a single component
 * collapsing — a 0.4-confidence OCR page, an exercise nobody recognised — must drag the whole slot
 * down, and an average would let four good components hide one catastrophic one.
 *
 * The component that catches the most silent misreads is [ConfidenceBreakdown.notationCoverage]:
 * the fraction of a source line that no recognised token consumed. A line where 40% of the
 * characters went unread is a line the parser probably misunderstood, whatever the rest of it
 * looked like.
 */
object Confidence {

  /** A rolled-up score for a group of slots, with the reasons that dominated it. */
  data class Summary(
    val score: Double,
    val slotCount: Int,
    val needingReview: Int,
    val reasons: List<String>
  ) {
    /** "0.42 — exercise inferred, 31% of line unparsed" */
    fun explain(): String {
      val pct = (score * 100).toInt()
      return if (reasons.isEmpty()) "$pct%" else "$pct% — ${reasons.joinToString(", ")}"
    }
  }

  /** Below this, a slot is offered for review rather than accepted in bulk. */
  const val REVIEW_THRESHOLD = 0.75

  fun forSlots(slots: List<SlotDraft>): Summary {
    if (slots.isEmpty()) return Summary(0.0, 0, 0, listOf("nothing was found"))
    // The MINIMUM matters as much as the mean: a program with one unreadable line among forty is
    // not a 0.97 import, it is a 0.97 import with a problem, and the summary has to carry both.
    val mean = slots.sumOf { it.confidence } / slots.size
    val worst = slots.minOf { it.confidence }
    val score = (0.75 * mean + 0.25 * worst).coerceIn(0.0, 1.0)
    val reasons = slots.flatMap { it.breakdown.reasons }
      .groupingBy { it }.eachCount()
      .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
      .take(3)
      .map { (reason, n) -> if (n > 1) "$reason (x$n)" else reason }
    return Summary(score, slots.size, slots.count { it.confidence < REVIEW_THRESHOLD }, reasons)
  }

  fun forProgram(draft: ProgramImportDraft): Summary =
    forSlots(draft.weeks.flatMap { w -> w.sessions.flatMap { it.slots } })

  /**
   * Folds the extractor's own confidence and the layout engine's margin into every slot, so a
   * 0.6-confidence OCR page can never produce a 0.90 slot.
   *
   * This runs once, at the point the route is known — rather than being threaded through every
   * parser — because the extractor's doubt applies uniformly to everything it produced.
   */
  fun applyDocumentConfidence(
    draft: ProgramImportDraft,
    extractorConfidence: Double,
    layoutMargin: Double = 1.0,
    extractorReason: String? = null
  ): ProgramImportDraft {
    if (extractorConfidence >= 0.999 && layoutMargin >= 0.999) return draft
    return draft.copy(weeks = draft.weeks.map { w ->
      w.copy(sessions = w.sessions.map { s ->
        s.copy(slots = s.slots.map { slot ->
          val b = slot.breakdown.copy(
            extractor = slot.breakdown.extractor * extractorConfidence,
            layoutMargin = slot.breakdown.layoutMargin * layoutMargin,
            reasons = slot.breakdown.reasons + listOfNotNull(extractorReason)
          )
          slot.copy(breakdown = b, confidence = minOf(slot.confidence, b.score))
        })
      })
    })
  }

  /**
   * How far a layout detection beat its runner-up, as a multiplier in [0.7, 1.0].
   *
   * A close call does not make the winning reading wrong, so the floor is high — it makes it
   * UNCERTAIN, which is worth roughly a third of a confidence band, not a collapse.
   */
  fun marginMultiplier(margin: Double): Double = when {
    margin >= 0.30 -> 1.0
    margin >= 0.12 -> 0.95
    margin >= 0.05 -> 0.85
    else -> 0.75
  }
}

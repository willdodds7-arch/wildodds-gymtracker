package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.importer.grammar.LineGrammar
import com.wildodds.gymtracker.data.importer.grammar.trimSeparators
import com.wildodds.gymtracker.data.importer.vocab.ReaderVocabulary

/**
 * Turns one written exercise line into a [SlotDraft].
 *
 * This is now a thin adapter over [LineGrammar] — a tokeniser plus a recursive-descent grammar —
 * rather than the eleven ordered regexes it used to be. The old implementation was at its limit: it
 * worked, but every new notation risked reordering the patterns, and an ordering bug is invisible
 * until a real program hits it. The grammar makes ordering an explicit rule instead of a comment
 * about regex precedence, and gives every token a source span so the review screen can show exactly
 * what was read and the confidence model can measure what was not.
 *
 * The public shape is unchanged on purpose: `SetNotationParserTest` is kept verbatim as a
 * **conformance suite**, so the rewrite has to prove it reads everything the old parser read before
 * it is allowed to read anything more.
 *
 * Nothing is invented. When a line cannot be structured, the movement name is still captured and the
 * whole line is preserved ([SlotDraft.rawText] plus a [SetKind.UNKNOWN] prescription).
 */
object SetNotationParser {

  fun parseExerciseLine(line: String, orderIndex: Int): SlotDraft? {
    val original = line.trim().trimSeparators()
    if (original.isBlank()) return null

    val parsed = LineGrammar.parse(original) ?: return null
    val name = parsed.name.ifBlank { stripToName(original) }
    if (name.isBlank()) return null

    val p = parsed.prescription
    val breakdown = ConfidenceBreakdown(
      // A line whose loading was never recognised is a line we half-understood, however clean the
      // name looked.
      structure = if (p.kind == SetKind.UNKNOWN) 0.45 else 1.0,
      notationCoverage = coverageScore(parsed.coverage),
      reasons = buildList {
        if (p.kind == SetKind.UNKNOWN) add("no sets/reps recognised")
        if (parsed.coverage < 0.7) add("${((1 - parsed.coverage) * 100).toInt()}% of line unparsed")
        if (parsed.ambiguousUnit) add("unit could be metres or minutes")
      }
    )

    return SlotDraft(
      rawText = original,
      exercise = ExerciseRef(original = name, resolvedName = titleCase(name)),
      sets = listOf(p),
      rest = parsed.rest,
      supersetGroup = parsed.supersetGroup,
      supersetOrder = parsed.supersetOrder,
      modifiers = parsed.modifiers,
      optional = parsed.optional,
      notes = parsed.trailingNote.takeIf { it.length > 2 }.orEmpty(),
      confidence = breakdown.score,
      breakdown = breakdown,
      orderIndex = orderIndex
    )
  }

  /**
   * Unconsumed text is the signal, so the score falls off sharply below full coverage rather than
   * linearly: 95% read is essentially a clean line, 60% read is a line we probably misunderstood.
   */
  private fun coverageScore(coverage: Double): Double = when {
    coverage >= 0.95 -> 1.0
    coverage >= 0.85 -> 0.95
    coverage >= 0.70 -> 0.85
    coverage >= 0.50 -> 0.65
    else -> 0.45
  }

  /** Last resort when the grammar found no anchor: the whole line, minus obvious trailing noise. */
  private fun stripToName(s: String): String =
    s.replace(Regex("""(?i)\s*\((optional|if time)\)\s*"""), " ").trimSeparators()

  private fun titleCase(s: String): String =
    s.split(" ").filter { it.isNotBlank() }.joinToString(" ") { w ->
      // Keep BB/DB/OHP acronyms as the coach wrote them.
      if (w.length <= 3 && w.uppercase() == w) w
      else w.replaceFirstChar { it.uppercaseChar() }
    }

  /**
   * True when a line is clearly a full exercise line rather than a header. Used by the segmenter,
   * and kept here because "does this line have a prescription" is a grammar question.
   */
  fun hasPrescription(line: String): Boolean {
    val p = LineGrammar.parse(line)?.prescription ?: return false
    return p.kind != SetKind.UNKNOWN
  }

  /** Exposed for the segmenter's feature extraction and for tests. */
  fun coverageOf(line: String): Double = LineGrammar.parse(line)?.coverage ?: 0.0
}

/** Kept for source compatibility with call sites that predate the grammar. */
internal fun String.trimTrailingSeparators(): String = trimSeparators()

/** Re-exported so callers do not have to know where the vocabulary lives. */
internal val readerVocabulary get() = ReaderVocabulary

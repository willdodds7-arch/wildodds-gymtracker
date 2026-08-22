package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.importer.segment.ProgramAssembler
import com.wildodds.gymtracker.data.importer.segment.Segmenter

/**
 * Deterministic importer for pasted plain-text programs (Reddit copy-pasta, notes-app dumps, an
 * assistant's markdown, a PDF's extracted text).
 *
 * Now three composable steps rather than one greedy loop:
 *
 *   [TextNormalizer] → [Segmenter] (a scored line classifier + a Viterbi pass over legal
 *   transitions) → [ProgramAssembler].
 *
 * The old version decided each line on its own, first keyword wins, and could never revisit — which
 * is why a title starting with "Upper" became a phantom empty session and a "Monday" inside a note
 * started a new day. Every one of its heuristics survives, as a FEATURE weighed against the others
 * rather than as a decision that could not be overruled.
 *
 * It still does not invent weeks it was not given: a single written week stays one week
 * ([ProgramStructure.SINGLE_WEEK_REPEATED], `repeatWeeks = 1`) and the review step decides length.
 */
object TextProgramImporter {

  fun import(name: String, raw: String): ProgramImportDraft {
    val normalised = TextNormalizer.normalize(raw)
    // Blank lines are kept: "is there a blank line before this" is real evidence about whether a
    // short line is a header, and dropping them throws that away before it can be used.
    val lines = normalised.lines()
    val segmented = Segmenter.segment(lines)
    return ProgramAssembler.assemble(name, segmented.lines)
  }

  /** Exposed for tests and the corpus runner: the labelling, without assembling a program. */
  fun segment(raw: String): Segmenter.Result =
    Segmenter.segment(TextNormalizer.normalize(raw).lines())
}

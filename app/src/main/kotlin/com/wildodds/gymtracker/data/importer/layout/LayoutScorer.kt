package com.wildodds.gymtracker.data.importer.layout

import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.reader.ingest.Grid

/**
 * One way a training spreadsheet can be laid out.
 *
 * The previous engine tried seven strategies **in order until one fit**, which means the answer
 * depended on the order of a `when` block and a weird sheet was read by whichever strategy happened
 * to be listed first and not obviously fail. There was no way to know how close the runner-up was,
 * and no way to tell the user which assumption produced their import.
 *
 * Here every scorer runs on every sheet, returns a confidence WITH ITS EVIDENCE, and the highest
 * score wins. The margin over the runner-up is reported, so a close call becomes a flag on the
 * review screen instead of a silent coin flip — which is what the original design intended.
 */
interface LayoutScorer {

  /** Stable id, shown in flags and in the corpus scoreboard. */
  val id: String

  /** What this layout is, in the words the review screen shows the user. */
  val description: String

  /**
   * @return null when this layout plainly does not apply. A zero-confidence result and a null are
   *   different things: null means "not this shape", zero means "this shape, but nothing in it".
   */
  fun score(workbook: Workbook): Scored?

  data class Scored(
    val confidence: Double,
    /** Why. Shown verbatim on the review screen, so it has to read as an explanation. */
    val evidence: List<String>,
    /** Produces the draft. Deferred so only the winning scorer pays for extraction. */
    val build: () -> ProgramImportDraft?
  )
}

/**
 * Everything the scorers see: the sheets, in order, plus the workbook-level facts several of them
 * need. Passed as one object so a new scorer never has to change every signature.
 */
data class Workbook(
  val grids: List<Grid>,
  val title: String? = null,
  val fileName: String = ""
) {
  /** The sheet most likely to hold the program: the largest by non-empty cell count. */
  val primary: Grid? = grids.maxByOrNull { g ->
    g.rows.sumOf { row -> row.count { it.isNotBlank() } }
  }

}

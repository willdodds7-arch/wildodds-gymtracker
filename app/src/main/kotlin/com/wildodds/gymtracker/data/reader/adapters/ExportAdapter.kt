package com.wildodds.gymtracker.data.reader.adapters

import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument
import com.wildodds.gymtracker.data.reader.ingest.Grid

/**
 * A known app's export format, parsed **natively** rather than fuzzily.
 *
 * These formats are exact, published and enormous in the wild: a Strong or Hevy CSV has a fixed
 * column set, so guessing at it with the general column classifier is strictly worse than reading
 * it. An adapter is ~60 lines behind a signature match, and adding one is a new file plus one line
 * in [ExportAdapters.ALL].
 *
 * Two rules every adapter follows:
 *  - **Signature first.** [matches] is a cheap header check. A file that is nearly a Strong export
 *    but not quite must fall through to the general pipeline rather than be forced through here.
 *  - **Log exports are histories, not programs.** Strong, Hevy, FitNotes and JEFIT export what the
 *    user DID. Turning that into a program is legitimate and is what people want, but the weights
 *    in it are performed weights, not prescriptions — so adapters record them as `ABSOLUTE` loads
 *    and set [ProgramImportDraft.source] so the review screen can say where they came from.
 */
interface ExportAdapter {

  /** Short, stable id used in flags, docs and the corpus scoreboard. */
  val id: String

  /** Human name, shown to the user: "Read as a Strong export." */
  val displayName: String

  /** Cheap signature check against the extracted document. Must not parse the whole file. */
  fun matches(doc: ExtractedDocument, fileName: String): Boolean

  /** Full parse. Returning null means the signature matched but the content did not. */
  fun parse(doc: ExtractedDocument, fileName: String): ProgramImportDraft?
}

/** The registry. One line per adapter — that is the whole extension point. */
object ExportAdapters {

  val ALL: List<ExportAdapter> = listOf(
    WildOddsExportAdapter,
    WildOddsTemplateAdapter,
    StrongAdapter,
    HevyAdapter,
    FitNotesAdapter,
    JefitAdapter,
    BoostcampAdapter,
    TrainHeroicAdapter,
    EverfitAdapter,
    TrainerizeAdapter
  )

  /**
   * The first adapter whose signature matches AND whose parse succeeds. Order in [ALL] is the tie
   * break, with the app's own formats first so a Wild Odds export is never mistaken for anything
   * else.
   */
  fun parse(doc: ExtractedDocument, fileName: String): Pair<ExportAdapter, ProgramImportDraft>? {
    for (a in ALL) {
      if (!a.matches(doc, fileName)) continue
      val draft = runCatching { a.parse(doc, fileName) }.getOrNull() ?: continue
      if (draft.weeks.any { w -> w.sessions.any { it.slots.isNotEmpty() } }) return a to draft
    }
    return null
  }
}

// ── shared helpers ─────────────────────────────────────────────────────────────

/**
 * Header lookup that survives the punctuation and casing differences between an app's versions:
 * `Exercise Name`, `exercise_name` and `exercisename` are the same column.
 */
internal object Header {
  fun key(s: String): String = s.lowercase().replace(Regex("""[^a-z0-9]"""), "")

  fun indexOf(header: List<String>, vararg names: String): Int {
    val keys = names.map(::key)
    return header.indexOfFirst { key(it) in keys }
  }

  fun hasAll(header: List<String>, vararg names: String): Boolean =
    names.all { n -> header.any { key(it) == key(n) } }
}

/** The first grid that has at least [minRows] rows, or the document's text parsed as one. */
internal fun ExtractedDocument.firstTable(minRows: Int = 2): Grid? =
  tables.firstOrNull { it.rowCount >= minRows }

internal fun Grid.headerRow(): List<String> = rows.firstOrNull().orEmpty()

internal fun Grid.dataRows(): List<List<String>> = rows.drop(1)

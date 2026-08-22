package com.wildodds.gymtracker.data.reader

import com.wildodds.gymtracker.data.importer.FlagSeverity
import com.wildodds.gymtracker.data.importer.ImportFlag
import com.wildodds.gymtracker.data.importer.ProgramImportDraft
import com.wildodds.gymtracker.data.importer.CsvProgramImporter
import com.wildodds.gymtracker.data.importer.TextProgramImporter
import com.wildodds.gymtracker.data.importer.UniversalProgramImporter
import com.wildodds.gymtracker.data.reader.adapters.ExportAdapters
import com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument
import com.wildodds.gymtracker.data.reader.ingest.ExtractorRegistry
import com.wildodds.gymtracker.data.reader.ingest.IngestLimits
import com.wildodds.gymtracker.data.reader.ingest.IngestPipeline
import com.wildodds.gymtracker.data.reader.ingest.IngestProgress
import com.wildodds.gymtracker.data.reader.ingest.IngestWarning
import com.wildodds.gymtracker.data.reader.ingest.SourceKind

/**
 * The whole reader, end to end, as one pure function of bytes.
 *
 *   sniff → extract → normalise → **route** → resolve → flag → compile
 *
 * The routing step is this file's job, and it goes in order of certainty:
 *
 *  1. **A known app's export.** Strong, Hevy, FitNotes, the app's own formats — exact schemas, so
 *     reading them natively beats inferring them. Highest confidence available.
 *  2. **A spreadsheet.** Grids go to the layout engine, which scores every candidate layout and
 *     reports the winner, the runner-up and the margin.
 *  3. **Text.** Everything else, through the normaliser, segmenter and grammar.
 *
 * Every route converges on [UniversalProgramImporter.fromDraft], so exercise resolution, flagging
 * and compilation happen exactly once no matter where the bytes came from. That is what makes "a
 * parser improvement helps every format" true rather than aspirational.
 *
 * Pure and offline. [registry] decides which extractors exist, so the JVM corpus runs the same code
 * the app does, minus the two extractors that need the platform.
 */
class ProgramReader(
  registry: ExtractorRegistry = ExtractorRegistry.pureOnly(),
  private val limits: IngestLimits = IngestLimits.DEFAULT,
  private val gridReader: GridProgramReader = com.wildodds.gymtracker.data.importer.layout.LayoutDetector,
  /**
   * Caches extraction against the source hash. Extraction is the expensive half — OCR of a photo
   * takes seconds — and it happens more than once per import in practice: the user edits the name
   * and re-parses, corrects an exercise, or asks for a re-parse with the current reader.
   */
  private val cache: com.wildodds.gymtracker.data.reader.ingest.ExtractionCache =
    com.wildodds.gymtracker.data.reader.ingest.ExtractionCache()
) {

  private val pipeline = IngestPipeline(registry, limits)

  data class Result(
    val importResult: UniversalProgramImporter.ImportResult,
    val document: ExtractedDocument,
    val kind: SourceKind,
    /** "strong", "grid:week-per-sheet", "text" — which route produced this. Shown in review. */
    val route: String
  )

  fun read(
    bytes: ByteArray,
    fileName: String = "",
    mimeType: String = "",
    aliasLookup: (String) -> String? = { null },
    progress: IngestProgress = IngestProgress.NONE
  ): Result {
    val hash = com.wildodds.gymtracker.data.reader.ingest.ExtractionCache.hash(bytes)
    val cached = cache.get(hash)
    val (doc, kind, suggested) = if (cached != null) {
      progress.report("Already read — re-parsing")
      cached
    } else {
      val ingested = pipeline.ingest(bytes, fileName, mimeType, progress)
      cache.put(hash, ingested.document, ingested.kind, ingested.suggestedName)
      Triple(ingested.document, ingested.kind, ingested.suggestedName)
    }
    return route(doc, kind, suggested, aliasLookup)
  }

  /** Pasted text takes the same route as a `.txt` file — one pipeline, not two. */
  fun readText(
    raw: String,
    name: String = "",
    aliasLookup: (String) -> String? = { null }
  ): Result {
    val doc = ExtractedDocument(text = raw, sourceKind = SourceKind.PLAIN_TEXT)
    val suggested = name.ifBlank { pipeline.titleFromText(raw) ?: "Imported Program" }
    return route(doc, SourceKind.PLAIN_TEXT, suggested, aliasLookup)
  }

  private fun route(
    doc: ExtractedDocument,
    kind: SourceKind,
    suggestedName: String,
    aliasLookup: (String) -> String?
  ): Result {
    // 1. A known export beats every heuristic, so it is tried first.
    ExportAdapters.parse(doc, suggestedName)?.let { (adapter, draft) ->
      // A known export schema IS declared structure — the app that wrote it said what each column
      // means, which is stronger evidence than any header a person could type.
      val named = draft.copy(name = draft.name.ifBlank { suggestedName }, hasExplicitStructure = true)
      return finish(named, doc, kind, adapter.id, aliasLookup,
        extra = listOf(ImportFlag(FlagSeverity.INFO, "Read as a ${adapter.displayName} export.")))
    }

    // 2. A grid-bearing document goes to the layout engine when one is available.
    if (doc.tables.isNotEmpty()) {
      gridReader.read(doc, suggestedName)?.let { (draft, route, flags) ->
        // A layout the engine recognised is declared structure: a header row, a week label, a day
        // divider. The scorer would not have won without one.
        return finish(draft.copy(hasExplicitStructure = true), doc, kind, route, aliasLookup, flags)
      }
    }

    // 3. Text. Note the flat rendering of a grid ALSO lands here when the layout engine declined,
    // which is deliberate: a one-column spreadsheet is a pasted program with extra steps.
    val textDraft = com.wildodds.gymtracker.data.importer.Confidence.applyDocumentConfidence(
      if (CsvProgramImporter.looksLikeCsv(doc.text)) CsvProgramImporter.import(suggestedName, doc.text)
      else TextProgramImporter.import(suggestedName, doc.text),
      extractorConfidence = doc.confidence,
      extractorReason = if (doc.confidence < 0.95) "read at ${(doc.confidence * 100).toInt()}% confidence" else null
    )
    val result = UniversalProgramImporter.fromDraft(textDraft, aliasLookup)
    val flags = result.flags + extractorFlags(doc)
    return Result(
      result.copy(flags = flags, draft = result.draft.copy(flags = flags)),
      doc, kind, "text")
  }

  private fun finish(
    draft: ProgramImportDraft,
    doc: ExtractedDocument,
    kind: SourceKind,
    route: String,
    aliasLookup: (String) -> String?,
    extra: List<ImportFlag>
  ): Result {
    // The extractor's own doubt applies uniformly to everything it produced, so it is folded in
    // once here rather than threaded through every parser. A 0.6-confidence OCR page can never
    // produce a 0.90 slot.
    val weighted = com.wildodds.gymtracker.data.importer.Confidence.applyDocumentConfidence(
      draft,
      extractorConfidence = doc.confidence,
      extractorReason = if (doc.confidence < 0.95) "read at ${(doc.confidence * 100).toInt()}% confidence" else null
    )
    val result = UniversalProgramImporter.fromDraft(weighted, aliasLookup)
    val flags = result.flags + extra + extractorFlags(doc)
    return Result(
      result.copy(flags = flags, draft = result.draft.copy(flags = flags)),
      doc, kind, route)
  }

  /**
   * The extractor's own warnings become import flags, and its confidence becomes a flag when it is
   * low. Losing an extractor's doubt on the way to the review screen is how a 0.6-confidence OCR
   * read ends up looking exactly like a clean CSV.
   */
  private fun extractorFlags(doc: ExtractedDocument): List<ImportFlag> {
    val flags = doc.warnings.map { w: IngestWarning ->
      ImportFlag(FlagSeverity.INFO, w.message, sourceLine = w.detail)
    }
    return if (doc.confidence < 0.8) flags + ImportFlag(
      FlagSeverity.REVIEW,
      "This file was read with ${(doc.confidence * 100).toInt()}% confidence — check every number " +
      "against the original before saving."
    ) else flags
  }
}

/**
 * The spreadsheet route, injected so [ProgramReader] does not depend on the layout engine directly
 * and the text-only corpus can run without it. [GridProgramReader.NONE] declines everything, which
 * makes every grid fall through to the text route — the pre-v2 behaviour, and a safe default.
 */
fun interface GridProgramReader {

  /** @return draft + route id + flags, or null to decline and let the text route handle it. */
  fun read(doc: ExtractedDocument, suggestedName: String): Triple<ProgramImportDraft, String, List<ImportFlag>>?

  companion object {
    val NONE = GridProgramReader { _, _ -> null }
  }
}

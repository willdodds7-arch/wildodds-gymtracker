# Universal Program Import — engine (Phase 1)

Turns a written program (pasted text or CSV/TSV) into the app's `ParsedProgram`, which commits
through the existing `GymRepository.importProgram`. **Fully on-device, deterministic, no LLM.**
Gated by the `feat_universal_import` setting (default off).

## Pipeline

```
raw text/CSV
  → UniversalProgramImporter.import(name, raw, aliasLookup)
      → CsvProgramImporter | TextProgramImporter   (format detect)   → ProgramImportDraft (CIM)
      → SetNotationParser                          (per-line schemes)
      → ExerciseResolver                           (normalize + alias + library)
      → flags + confidence
      → CimCompiler                                 (CIM → ParsedProgram)
  → ImportResult { draft, program, flags, overallConfidence }
```

`draft` is the rich [Canonical Import Model](CanonicalImportModel.kt) for a future review UI;
`program` is commit-ready. **The commit itself is not automatic** — the review step calls
`repo.importProgram(result.program)` after the user confirms.

## Two rules that must not regress (see tests)

1. **Never fabricate.** A value the source didn't state stays null/empty — e.g. an unrecognised line
   yields `SetKind.UNKNOWN`, `count = null`, `repsTarget = ""`. Contrast the old
   `TextProgramParser`, which invents `3x8-10`.
2. **Never lose the original.** Every `SlotDraft`/`SetPrescription` keeps `rawText`; low-confidence
   slots carry the verbatim line into the exercise `notes`.

## Adding a new input format

Add an extractor that returns a `ProgramImportDraft` (or, if the format is line-oriented, just feed
cleaned text to `TextProgramImporter`). Then branch to it in `UniversalProgramImporter.import`.
Reuse `SetNotationParser` for the per-exercise notation so every format speaks one dialect.

- **xlsx / docx**: extract cells/tables → reconstruct rows → hand each row to `CsvProgramImporter`'s
  row logic (or synthesise a notation line as it does).
- **PDF text / URL**: extract text → `TextProgramImporter`.
- **Images / scanned PDF / handwriting**: needs a vision model — send the image as a base64 block to
  a vision-capable Claude (not the current haiku default) with the CIM as the structured-output
  schema, then run the result through `ExerciseResolver` + `CimCompiler`. This is the one path that
  must go through the LLM; keep the deterministic paths LLM-free.

## Tuning the notation parser

All heuristics live in `SetNotationParser` as named regexes at the top — edit those, add a case to
`findCore`, and add a fixture to `SetNotationParserTest`. Keep it boring and table-driven; this code
fails in production in ways that need reading, not cleverness.

## Built & wired

- Engine (this package) + `SetNotationParser`, importers, resolver, compiler.
- **Review/confirm UI**: `ui/programimport/ImportReviewScreen` + `ImportReviewViewModel`
  (paste → parse → editable review with confidence/flags → commit via `importProgram`; corrections
  learned into `PrefsAliasStore`). Reachable from Home empty-state and Library, gated by
  `feat_universal_import`. Verified end-to-end on an emulator.

## Not yet built (next slices)

DB-backed alias table + `Exercise.rawPrescription` column, structured-output LLM step for messy
sources, xlsx/PDF/vision extractors, multi-file merge, URL import, export, template/instance
versioning, side-by-side source highlighting + bulk column ops in the review UI. See the design
proposal in the conversation history for the full plan and decisions.

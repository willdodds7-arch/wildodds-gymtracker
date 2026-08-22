# The Reader — baseline (before v2)

An honest, file-by-file map of the program-import subsystem as it stood at commit `810e0e7`,
written before any of the v2 work began. Line counts are `wc -l`. "Callers" lists production
callers only. "Survives" records the v2 decision.

Baseline test suite: **453 tests, 0 failures** (`:app:testOfflineDebugUnitTest`).
Of those, **63** are in the reader area.

---

## `data/importer/` — the deterministic engine (1,095 lines)

| File | Lines | Purpose | Callers | Tests | Survives |
|---|---|---|---|---|---|
| `CanonicalImportModel.kt` | 138 | The rich pre-commit model (CIM): `ProgramImportDraft` → `WeekDraft` → `SessionDraft` → `SlotDraft` → `SetPrescription`. Enums for load kind, rep kind, set kind, rest kind, resolution status, flag severity. | (data only) | via `UniversalImportTest` | **Yes** — extended in Phase 2 (per-set list, modifiers, `rawSourceLine`, rest days, superset order). |
| `UniversalProgramImporter.kt` | 90 | Façade: format-detect → importer → resolve → flag → compile. Returns `ImportResult`. | `BuilderViewModel`, `BuilderSheets`, `ImportReviewViewModel` | `UniversalImportTest`, `GeneratedProgramImportTest` | **Yes** — becomes the pipeline entry point. |
| `CimCompiler.kt` | 116 | Flattens the CIM to `ParsedProgram`. **The lossy step**: rest/tempo/superset/RIR/per-set all become a `notes` blob. | `UniversalProgramImporter` | indirectly | **Renamed** → `DraftCompiler`, near-1:1 after Phase 2. |
| `CsvProgramImporter.kt` | 143 | CSV/TSV: delimiter sniff, header-row find, fuzzy column aliases, row → synthesised notation line. Minimal quote handling; no RFC-4180. | `UniversalProgramImporter` | `UniversalImportTest` | **Yes** — reader replaced by an RFC-4180 reader in Phase 3. |
| `TextProgramImporter.kt` | 148 | Plain-text structure detection: `Week N`, `Day N`, split keywords, weekdays; everything else is an exercise line. Greedy, per-line, keyword-driven. | `UniversalProgramImporter` | `UniversalImportTest`, `GeneratedProgramImportTest` | **Yes** — algorithm replaced by the scored segmenter in Phase 4b; its lookahead heuristics become features. |
| `MarkdownNormalizer.kt` | 176 | Markdown → flat text: headings, bullets, emphasis, rules, blockquotes, dash unification, table reflow. Plus `isProse()`. | `TextProgramImporter` | `UniversalImportTest` | **Renamed** → `TextNormalizer`, widened in Phase 4a. |
| `SetNotationParser.kt` | 209 | Eleven ordered regexes over one exercise line → `SlotDraft`. The heart of the reader. | `TextProgramImporter`, `CsvProgramImporter` | `SetNotationParserTest` (15 tests) | **Yes** — reimplemented as a tokeniser + recursive-descent grammar in Phase 4c; the 15 tests become a conformance suite. |
| `ExerciseResolver.kt` | 49 | normalize → abbreviation expand → learned alias → `ExerciseLibrary` → EXACT/ALIAS/INFERRED/UNRESOLVED. | `UniversalProgramImporter`, `ImportReviewViewModel` | `UniversalImportTest` | **Yes** — layered matcher in Phase 5. |
| `PrefsAliasStore.kt` | 26 | SharedPreferences-backed per-user learned aliases. | `ImportReviewViewModel` | — | **Yes**. |
| `README.md` | — | Package doc. | — | — | Replaced by `docs/reader/`. |

## `data/parser/` — the older, catalogue-era parsers (1,432 lines)

| File | Lines | Purpose | Callers | Tests | Survives |
|---|---|---|---|---|---|
| `ParsedModels.kt` | 84 | `ParsedProgram` / `ParsedSession` / `ParsedExercise` / `ParsedPhase` — the commit contract. | everywhere | many | **Yes**. |
| `DefaultProgramCatalogue.kt` | 21 | Loads the bundled block catalogue. | `HomeViewModel`, `LibraryViewModel` | `DefaultProgramCatalogueAssetTest` | **Yes** — not part of the Reader. |
| `DefaultProgramJsonParser.kt` | 175 | Parses `assets/blocks.json`. | `DefaultProgramCatalogue` | `DefaultProgramJsonParserTest` | **Yes** — not part of the Reader. |
| `BulkProgramXlsxParser.kt` | 378 | Bulk multi-program workbook import. Its own zip/XML reader, duplicated from the others. | `HomeViewModel` | — | **Yes** — refolded onto `SheetGrid` + `LayoutDetector` in Phase 6. |
| `DefaultProgramXlsxParser.kt` | 488 | Historical: read the default-program workbook. Superseded by `blocks.json`. | **none** (only its own test) | `DefaultProgramXlsxParserTest` | **DELETED** (Phase 1). |
| `XlsxParser.kt` | 193 | Historical generic xlsx reader. | **none** | none | **DELETED** (Phase 1). |
| `TextProgramParser.kt` | 93 | Historical text parser. **Invents `3x8-10`** when it cannot read a line — the exact behaviour the CIM exists to prevent. | **none** | none | **DELETED** (Phase 1). |

## `data/parser/smart/` — the spreadsheet engine (1,107 lines main)

| File | Lines | Purpose | Callers | Tests | Survives |
|---|---|---|---|---|---|
| `SmartXlsxParser.kt` | 773 | Seven layout strategies tried **in order until one fits**: classic W1D1 template, sheet-per-week, in-sheet week labels, repeating day sequences, column groups, dates, flat fallback. Own zip/XML reader. | `HomeViewModel` | `SmartXlsxParserTest` (2 tests, classic template only — the other six strategies have **zero** coverage) | **Split** in Phase 6 into `SheetGrid` (reader) + `LayoutDetector` (all strategies as scorers). |
| `ExerciseLineParser.kt` | 234 | Column-header classification, inline sets×reps, abbreviation expansion, day/week label detection. First-match-wins on headers. | `SmartXlsxParser` | — (no direct tests) | **Yes** — header classification becomes a global assignment in Phase 6. |
| `CellUtils.kt` | 51 | A1-notation helpers plus a thin `SheetGrid` view over a `Map<String,String>`. | `SmartXlsxParser` | — | **Yes** — `SheetGrid` grows into the real reader. |
| `SmartModels.kt` | 49 | `SmartParsedProgram` / `SmartParsedDay` / `SmartParsedExercise` / `ParseWarning`. A **second** parallel model to the CIM. | `SmartXlsxParser`, `HomeViewModel` | — | **Yes for now** — converges on the CIM in Phase 6. |

## `data/ai/` — the one permitted networked path (295 lines)

| File | Lines | Purpose | Callers | Tests | Survives |
|---|---|---|---|---|---|
| `AiFileExtractor.kt` | 124 | Despite the name, **fully offline**: PDF via PDFBox, xlsx via a hand-rolled zip/XML reader, everything else read as UTF-8 text. Extension-only sniffing. | `HomeViewModel`, `ImportReviewViewModel` | `UniversalImportTest` | **Replaced** by `IngestPipeline` (Phase 3). The name is a lie and goes. |
| `ClaudeApiClient.kt` | 150 | The only networked path. Opt-in, user-supplied key, off by default. | `HomeViewModel`, `RecoveryViewModel` | — | **Untouched.** |
| `AiModels.kt` | 21 | DTOs for the above. | `ClaudeApiClient` | — | **Untouched.** |

## Downstream

| File | Lines | Role |
|---|---|---|
| `data/repository/GymRepository.importProgram` | ~55 | The single commit path. Expands `repeatWeeks`, inserts program → sessions → exercises. Not transactional today (separate DAO calls). |
| `data/builder/PlanMapper.kt` | 248 | `workoutsFromParsed` reads **week 1 only** (correct — a `PlanWorkout` is one definition, so reading N weeks yields N duplicate days), and rest days do not exist in `ParsedProgram` at all. |
| `ui/programimport/ImportReviewViewModel.kt` | 221 | Paste → parse → editable review → commit. Learns corrections into `PrefsAliasStore`. |
| `ui/programimport/ImportReviewScreen.kt` | 274 | The review UI. No source-line provenance, no undo, no re-parse. |

---

## The accuracy ceiling, stated plainly

`Exercise` is `sets: Int` + `repsTarget: String` + `notes: String`. Everything the CIM understands
beyond that — rest, tempo, superset grouping and order, RIR, per-set detail, load kind, modifiers,
the verbatim source line — is flattened into `notes` by `CimCompiler`. No amount of parser
improvement raises accuracy past that flattening, which is why Phase 2 comes before Phases 3-6.

A second ceiling: `sets` is written as `(count ?: perSet.size ?: 1).coerceAtLeast(1)`. A source that
never stated a set count therefore lands in the database as **one set** — an invented number. Phase 2
makes `null` storable so that stops being true.

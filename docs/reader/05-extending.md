# Extending the Reader

Each of these is deliberately one file plus one line. If your change needs more than that, the
extension point is probably in the wrong place — say so rather than working around it.

---

## Add a word

`app/src/main/resources/reader/vocabulary.json`. Abbreviations, modifier surface forms, section
labels, weekday names, unit words, rest phrasings, column-role headers, app-export signatures,
generic sheet names.

**One vocabulary, three consumers** — the text pipeline, the delimited path and the spreadsheet
engine all read it, so an addition lands everywhere at once.

Bump `version`. `ReaderVocabularyTest` enforces what an editor could otherwise break: no surface
form claimed by two modifiers, longest-first ordering, every canonical modifier present, and no
abbreviation that would shadow a movement word (`row`, `set`, `max`, `press`, `curl`, `leg`).

---

## Add an exercise

`tools/build_exercise_index.py`, then re-run it. Add a base movement with its pattern, primary
muscle, default equipment, aliases, and the qualifiers and equipment that make sense **for that
movement** — a bench press has an incline, a deadlift does not.

The index is generated rather than hand-written so metadata stays consistent by construction and one
base movement brings its whole family. Do not edit `exercises.tsv` directly; it is regenerated.

---

## Add a notation form

1. A test in `LineGrammarTest`.
2. If it needs a new token, add it to `TokenType` and classify it in `Lexer`.
3. Add a clause parser in `LineGrammar` and dispatch to it from `parseClause`.
4. A corpus document at `reader/corpus/notation/<slug>.txt`.

Clauses after the anchor are **order-independent** — each one claims what it recognises. Do not
introduce ordering dependencies between them; that was the failure mode of the parser this replaced.

Claim every token you consume. Coverage is what tells the user the line was understood.

---

## Add a format

1. Implement `Extractor` in `data/reader/ingest/`. Work on `ByteArray`, stay pure, throw only
   `IngestError`, and never crash on truncated input.
2. Teach `Sniffer` to recognise it — magic bytes or zip-entry structure, not the extension.
3. Add it to `ExtractorRegistry.pureOnly()`, or to `AndroidReader.registry()` if it genuinely needs
   the platform.
4. A corpus document, and a case in `ExtractorFuzzTest`'s corpus if it is binary.

Populate **both** `text` and `tables` if the format has both. Set `confidence` honestly — it is
multiplied into every slot downstream, and that is the mechanism that stops a lossy read looking
like a clean one.

---

## Add a spreadsheet layout

1. Implement `LayoutScorer` in `data/importer/layout/Scorers.kt`.
2. Add it to `LayoutDetector.SCORERS`.
3. A fixture in `LayoutDetectorTest` using `WorkbookFixtures`.

Return a confidence **with its evidence** — the evidence is shown to the user verbatim, so write it
as an explanation ("3 sheets named after a week: Week 1, Week 2, Week 3"), not as a debug string.

Return `null` when the layout plainly does not apply. `null` and a zero confidence are different
things: `null` means "not this shape", zero means "this shape, but nothing in it".

Read rows through `GridReader`, not your own row logic. That is the whole reason the twelve scorers
share every notation improvement.

---

## Add an app export

1. Implement `ExportAdapter` in `data/reader/adapters/`. If the export is one-row-per-set, extend
   `LogRowAdapter`; if it is a program table, extend `ProgramTableAdapter`. Both cases are ~15 lines
   of column names plus a signature.
2. Add it to `ExportAdapters.ALL`.
3. A fixture in `ExportAdapterTest` and a corpus document.

Make `matches` **cheap and specific**. A signature that grabs too eagerly is worse than no adapter:
the general pipeline reads these formats perfectly well, and a near miss falling through costs
nothing, while a wrong native parse is confidently wrong.

If you author the fixture from published column names rather than a real export, say so in
[04-gaps.md](04-gaps.md) §1.

---

## Add a stored field

1. Add it to the entity with `@ColumnInfo(defaultValue = ...)`.
2. Add it to `ParsedExercise` / `ParsedSession` **with a default**, so every existing construction
   site is unaffected.
3. Write a real `Migration(n, n+1)`, register it in `ALL_MIGRATIONS`, bump the version.
4. A `MigrationTest` case that seeds a v(n) database, migrates, and asserts old rows are unchanged
   and the new column is `NULL`.
5. Build once and **commit the exported schema JSON**. `SchemaSnapshotTest` fails if you forget.
6. Map it in `DraftCompiler` and `GymRepository.importProgram`.
7. If it was previously in `LOSSY_FIELDS`, remove it there — `DraftCompilerLossTest` asserts that
   list is exactly the set of things that still cannot be stored.

Additive columns with safe defaults. **Backfill nothing.** `NULL` means "unknown", and inferring a
value for a program a real person imported months ago is inventing data about their training.

---

## After any change

```bash
python tools/build_corpus.py                 # only if you added corpus documents
./gradlew testOfflineDebugUnitTest           # 762 tests
```

If the corpus snapshot test fails, **read the diff before regenerating.** It names the document and
the row: `row 3: 'Bench Press 3x8' -> 'Bench Press 3x8 @ABSOLUTE:80KG'`. Deciding whether that is an
improvement is the job; `-Dreader.corpus.update=true` is what you run *after* deciding, and the
resulting diff goes in the commit.

Put the before/after scoreboard in the PR description.

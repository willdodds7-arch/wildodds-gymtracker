# The Reader — as built

~10,600 lines of production code across `data/reader/` and `data/importer/`, ~4,500 lines of tests
over it, and a 158-document corpus. This page is what it is and why; [04-gaps.md](04-gaps.md) is
what it is not.

---

## 1. The packages

| Package | What lives there | Android? |
|---|---|---|
| `data/reader/ingest/` | Sniffing, decoding, and one `Extractor` per format family. Pure Kotlin on `ByteArray`. | **No** |
| `data/reader/adapters/` | Ten app-export adapters behind signature matches, plus their registry. | **No** |
| `data/reader/android/` | PDFBox, `PdfRenderer`, ML Kit, and the only place that knows about `Context`/`Uri`. | Yes |
| `data/importer/grammar/` | The tokeniser and the recursive-descent grammar for one exercise line. | **No** |
| `data/importer/segment/` | The line classifier, the Viterbi segmenter, the program assembler. | **No** |
| `data/importer/layout/` | Column-role assignment and the twelve spreadsheet layout scorers. | **No** |
| `data/importer/resolve/` | The exercise index, fuzzy matching, phonetics, semantic guards. | **No** |
| `data/importer/vocab/` | The versioned vocabulary resource and its accessor. | **No** |
| `data/importer/` | The canonical model, the normaliser, confidence, flags, the compiler. | **No** |

Exactly one file — `AndroidReader` — sits above the extraction boundary and knows about the
platform. Everything else runs on a bare JVM, which is what makes the corpus and the fuzz tests
possible at all.

---

## 2. Sniffing

`Sniffer.sniff(bytes, fileName, mimeType, zipEntryNames)`, in that order of authority:

1. **Magic bytes** — `%PDF`, `{\rtf`, PNG/JPEG/GIF/BMP/HEIC/WebP, OLE2, `PK`.
2. **Structure** — a `PK` archive is identified by its ENTRY NAMES (`xl/` → xlsx, `word/` → docx,
   `mimetype` + `content.xml` → ODF, `Index/*.iwa` → an Apple bundle). This is the only way to tell
   the five zip-based office formats apart, and users rename them constantly.
3. **Shape** — HTML, XML, JSON, YAML and delimited are recognised from a decoded prefix.
4. **Extension**, and only then, and only to split one text-ish format from another.
5. **MIME**, last, because content providers report it unreliably.

**Delimited detection is by column-count variance across the file**, not by counting separators on
line one. Counting line one is how `Front Squat 6, 8, 10` — per-set reps — was read as a
three-column CSV. A real table has a consistent column count under exactly one delimiter; prose does
not.

---

## 3. Decoding

`TextDecoding`: explicit BOM → **strict** UTF-8 → UTF-16 without a BOM (detected by NUL parity) →
Windows-1252.

The UTF-8 decode reports errors rather than replacing them, which is the entire point: a Latin-1
file has to FAIL there to reach the fallback. Decoding everything as UTF-8 with replacement — what
the old extractor did — produces a screen of U+FFFD that looks like a working import right up until
the exercise names are garbage.

---

## 4. Extraction

One `Extractor` per family, all returning the same `ExtractedDocument(text, tables, title,
confidence, warnings, lineConfidence)`. Text-only and grid-bearing formats populate **both** `text`
and `tables` where they have both, so every format flows into the same downstream pipeline.

`confidence` is the extractor's own honesty about what it produced — 1.0 for a lossless text read,
0.9 for a positional PDF reconstruction, the measured page confidence for OCR. It is folded into
every slot's confidence exactly once, at the point the route is known, so **a 0.6-confidence OCR
page can never produce a 0.90 slot**.

`MiniXml` is a ~200-line pure-Kotlin streaming XML reader written for this. It replaced
`android.util.Xml` in four places. It handles the subset OOXML and ODF actually use, and it
deliberately does not do DTDs or external entities — which is also why an XXE payload in a
user-supplied `.docx` has nothing to resolve against.

Guardrails, every one of them because the alternative is an OOM on a mid-range phone: file size,
decompressed size, zip entries, zip depth, pages, cells, sheets, text characters, OCR pages, OCR
wall-clock. All are constructor parameters so tests can shrink them and actually exercise the limit
paths.

---

## 5. Routing

`ProgramReader.route`, in order of certainty:

1. **A known app's export.** Strong, Hevy, FitNotes, JEFIT, Boostcamp, TrainHeroic, Everfit,
   Trainerize, Wild Odds JSON, Wild Odds template. These are exact published schemas, so reading
   them natively beats inferring them. A near miss falls through — **an adapter is an accuracy
   upgrade, never a gate.**
2. **A spreadsheet.** Grids go to `LayoutDetector`.
3. **Text.** Everything else.

---

## 6. The text pipeline

### `TextNormalizer`

NFKC (full-width digits, `×`), the dash / quote / space / zero-width families, markdown, bullet
variants, emoji (the few that carry meaning become a word first — 🔥 really does mark a finisher),
markdown tables reflowed via the shared vocabulary, page headers and footers detected by
**cross-page repetition**, page numbers, and hard-wrapped lines rejoined only when the continuation
is unambiguous.

**Indentation is preserved.** It is the clearest evidence a document gives that a line continues the
one above it, and trimming it destroyed that before the segmenter ever saw it.

The invariant, asserted over every fixture: the digits that go in are the digits that come out.

### `Segmenter`

`LineClassifier` scores each line over ten roles — `PROGRAM_TITLE`, `PHASE_HEADER`, `WEEK_HEADER`,
`SESSION_HEADER`, `EXERCISE`, `SET_DETAIL_CONTINUATION`, `SECTION_LABEL`, `NOTE`, `REST_DAY`,
`IGNORE` — from features: position, indentation, casing, punctuation, a sets×reps token, a
resolvable exercise name, numbering, blank-line neighbourhood, repetition, and a lookahead that
skips blanks. `Segmenter` then resolves the whole sequence with a Viterbi pass over legal
transitions, in log space (a 200-line document multiplies 200 sub-one numbers and underflows).

Every heuristic the old importer had survives here as a **feature**, weighed against the others,
rather than as a first-match decision that could not be revisited. That is what fixes the two bugs a
per-line view cannot see: a title starting with a split keyword becoming a phantom session, and
`Rest 2-3 minutes between working sets.` importing as a timed exercise called "Rest 2".

### `LineGrammar`

A tokeniser plus a recursive-descent parser over an explicit token set. Three properties matter:

- **The anchor decides the name.** The parser finds where the prescription starts; everything left
  of it is the movement. The search is ranked, not first-match, so `Dumbbell Row 45 degree 3x10`
  does not become an exercise called "Dumbbell Row" prescribed "45 degrees".
- **Clauses after the anchor are order-independent.** Coaches write `3x5 @80% rest 3min` and
  `3x5 rest 3min @80%` interchangeably. Ordering bugs — the failure mode of the eleven-regex parser
  this replaced — become impossible.
- **Every token carries its source span.** `ParsedLine.coverage` is the fraction of the line's
  characters that some token claimed. A line where 40% went unread is a line the parser probably
  misunderstood, and that single number catches more silent misreads than anything else here.

Two hard-won rules are preserved with named tests: **one TM per line governs every percentage on
it**, and **only an uppercase X makes a tempo**, so `10x8` stays ten sets of eight.

---

## 7. The spreadsheet engine

`XlsxExtractor` produces clean `Grid`s: shared and inline strings, number formats (so a cell showing
85% is 85 and not 0.85), merged cells expanded across their span, hidden rows and columns flagged,
frozen panes as a header hint, cached formula values, defined names, and fill colours. Zero
third-party dependencies, which stays a genuine asset — POI is ~12 MB of dex for a format whose
sheet XML is a flat list of `<c>` elements.

`LayoutDetector` runs **all twelve scorers on every workbook**. Each returns a confidence with its
evidence; the highest wins; the margin over the runner-up is reported. A call within 12 points
raises a `REVIEW` flag naming both readings.

| # | Scorer | |
|---|---|---|
| 1 | `classic-template` | the app's own `W1D1` sheets |
| 2 | `sheet-per-week` | |
| 3 | `week-labels-in-sheet` | |
| 4 | `repeating-days` | |
| 5 | `column-groups` | a block of columns per week |
| 6 | `dates` | rows grouped by date |
| 7 | `flat` | the always-applicable fallback |
| 8 | `transposed` | **new** — exercises across the top |
| 9 | `one-row-per-set` | **new** — a training log |
| 10 | `month-calendar` | **new** |
| 11 | `metadata-block` | **new** — key/value rows above the table |
| 12 | `colour-semantics` | **new** — a shaded row is a heading |

Column roles are solved **globally** with the Hungarian algorithm over a role × column cost matrix
built from header text and cell-content statistics. Greedy first-match could not tell `Set` from
`Sets`, and could not read a header-less sheet at all.

---

## 8. Exercise resolution

A 952-movement index (`resources/reader/exercises.tsv`, generated by
`tools/build_exercise_index.py`) with pattern, primary muscle, equipment, a unilateral flag and
curated aliases, precomputed into token and phonetic posting lists.

Eight layers, **all of which run**, best score wins, ties broken on the candidate name so the result
is identical on every device: learned per-user alias → exact → bundled alias → token-set →
equipment decomposition → phonetic → bounded Damerau-Levenshtein → whole-string edit distance, with
plural/possessive/tense normalisation underneath.

Thirteen **semantic guards** — front/back, high/low bar, incline/decline, pull-up/pull-down,
press/pull, seated/standing, RDL/sumo, close/wide grip, unilateral/bilateral, forward/reverse,
eccentric/concentric, assisted/weighted — that fuzzy matching may never cross. A blocked near-match
becomes `INFERRED` with the candidate offered, never `EXACT`. **A typo fix can never flip the
movement.**

Measured: 60/60 on a fixed set of real-world names, against 9/60 before.

---

## 9. Confidence

Every score is the product of named components — structure certainty, notation coverage,
resolution tier, extractor confidence, layout margin — and carries the reasons that pulled it down.

Multiplicative rather than averaged, deliberately: one component collapsing must drag the whole slot
down, and an average lets four good components hide one catastrophic one. Program-level summaries
weight the **worst** slot alongside the mean, because thirty-nine clean lines and one unreadable one
is not a 0.97 import.

---

## 10. Storage

Schema **v26** made the prescription first-class: `setsStated`, `restSeconds`,
`restRangeSecondsMax`, `tempo`, `supersetGroup`, `supersetOrder`, `loadKind`, `loadValue`,
`loadValueMax`, `loadUnit`, `perSet` (typed JSON), `isFinalSetAmrap`, `modifiers`, `rawSourceLine` —
plus `sessions.sessionType`, so a rest day can exist.

Schema **v27** added `import_records`, which is what makes undo, re-parse and idempotency possible.

Both migrations are purely additive and **backfill nothing**: old rows keep their `NULL`s, and
`NULL` keeps meaning "unknown", not "zero". Inferring 90 seconds of rest for a program imported
before v26 would be inventing data about a real user's training.

What still cannot be represented is enumerated in `DraftCompiler.LOSSY_FIELDS`, goes to `notes`
**and** raises an `INFO` flag naming the field. `DraftCompilerLossTest` asserts that set is exactly
the documented one, so a field silently falling out of the model fails CI.

---

## 11. Testing

| | |
|---|---|
| Corpus | 158 documents, one snapshot each, plus a scoreboard baseline |
| Property | anti-invention, round-trip, confidence bounds, determinism, no-crash |
| Fuzz | truncation, bit flips and noise against all nine binary extractors |
| Layout | 30 fixture workbooks, built as real OOXML bytes in memory |
| Migration | v1→v27 chain, plus per-migration additive-and-no-backfill tests |
| Performance | budgets as tests, measured and asserted |
| No-network | scans the compiled class files of the reader packages |

762 tests, 0 failures.

# Gaps & known limits

Everything the Reader does not do, does badly, or does on an assumption that may not hold. This is
the most useful page in this folder, and it is written with the same candour as the rest: a reader
whose failure modes are documented can be trusted at the edges; one whose failures are discovered by
users cannot.

Ordered by how likely you are to hit it.

---

## 1. Not verified against real accounts

**The four third-party PROGRAM exporters are built from published column names, not captured
exports.** `Boostcamp`, `TrainHeroic`, `Everfit` and `Trainerize` adapters match on the header names
those apps document, and their fixtures were authored from those names. The tests prove each adapter
reads that *shape* — they do not prove the shape is current in every version of those apps.

The three LOG exporters (`Strong`, `Hevy`, `FitNotes`) have stable, widely-published column sets and
are much safer. `JEFIT`'s export has changed shape across versions and its signature is the loosest
of the ten.

This is bounded: a file that does not match a signature falls through to the general pipeline, which
reads these formats perfectly well anyway. The adapter is an accuracy upgrade, never a gate. But if
an adapter is *wrong* rather than absent — a column moved, a unit changed — it would be confidently
wrong, and nothing here would catch it.

**To fix:** capture one real export per app and replace the authored fixture.

---

## 2. OCR is untested against real images

The OCR path is complete and wired — ML Kit with the **bundled** model (so it works offline on first
launch), `PdfRenderer` rasterisation, EXIF rotation, deskew, adaptive threshold, upscaling, and the
`OcrRepair` pass that rebuilds lines by baseline clustering and columns by x-gap histogram.

What it has **not** had is a photo. `OcrExtractorTest` drives the whole path against a scripted fake
recogniser — baseline clustering, x-gap columns, confusable repair, confidence propagation, the
illegibility refusal, multi-page batching and the page cap — and exercises `ImagePrep`'s adaptive
threshold under a synthetic illumination gradient, its skew estimate and its upscale. What remains
tested only by compiling is the ML Kit binding itself and `PdfRenderer` rasterisation, both of which
need a device. And every number in `ImagePrep` — the 25-pixel threshold window, the ±8° deskew range,
the 1400-pixel minimum width — is a reasoned default tuned against synthetic images, not measured
against real ones.

The corpus contains `hostile/11-handwriting-note.txt` as a placeholder where a photo of a whiteboard
should be. It is a text file. That is a real gap, and it is the single largest one here.

**To fix:** add real photographs to the corpus behind a device test, and tune `ImagePrep` against
them.

---

## 3. `.xls` (BIFF8) is best-effort

The hand-rolled OLE2/CFB + BIFF8 reader handles BOF, BOUNDSHEET, SST (including the CONTINUE
boundary, which is the classic place to get it wrong), LABELSST, LABEL, RSTRING, NUMBER, RK, MULRK
and FORMULA cached values. Everything else is skipped by length.

It does **not** handle: encrypted workbooks (refused explicitly), BIFF5 and earlier, formulas with
no cached value, merged cells, number formats, or hidden rows. Its extractor confidence is 0.9 and
it warns that formatting is approximate.

`.doc` is OLE2 too and is **refused with a clear message** rather than read as garbage — which is
what the old extractor did.

---

## 4. Apple `.numbers` / `.pages` read a preview, not the document

The real payload is IWA — Snappy-compressed protobuf, undocumented and unstable across versions.
The reader extracts the bundled **preview PDF** and reads that.

A render has the visual layout but not the cell structure, hidden rows, or formulas. The import is
flagged as a preview read and its confidence is multiplied by 0.85. If the bundle has no preview
(Numbers omits it when the user disables it), the import fails with the one-step fix: export to
xlsx or PDF.

---

## 5. The metaphone is a reduction

`Metaphone` is a **reduced** double-metaphone: the rules that fire on gym vocabulary, not a port of
the full algorithm. The full version carries substantial machinery for Slavic, Germanic and Romance
name endings that has no bearing here, and every branch of it is a branch that can misfire.

It returns a single code, not a primary and an alternate. Names whose English pronunciation genuinely
branches will match on only one reading.

---

## 6. `m` is ambiguous and always will be

`5x400m` is metres. `3x2m` is two minutes. Both are real notations, and no amount of pattern matching
resolves them — the reader decides by magnitude at 15, and **flags the band from 5 to 30 as
ambiguous** rather than pretending certainty. A coach who means minutes usually writes `min`, which
is why this rarely bites.

Related: a weight column with no unit anywhere stays `UNKNOWN` and the review step asks. The old
parser defaulted such a column to kg, which silently halved every import from an American coach's
sheet. Refusing to guess is the correct behaviour and it costs the user a tap.

---

## 7. Progression rules are not representable

"Add 2.5 kg each week", "repeat weeks 1-3 then deload", "80% of last week's top set" — these are
RULES, and a program definition holds prescriptions. They are preserved verbatim in notes and raise
a `REVIEW` flag saying so, but the app will not apply them.

The same applies to `WeekDraft.isDeload` (no per-week storage), session-level notes (no column on
`sessions`), and set STYLE beyond what the modifiers carry — all enumerated in
`DraftCompiler.LOSSY_FIELDS` and asserted to be exactly that list.

---

## 8. The builder still reads one week

`PlanMapper.workoutsFromParsed` reads a single week, and that is correct rather than a limitation of
convenience: a `PlanWorkout` is one definition that the save step expands across every week, so
reading all N would produce N copies of each day.

What is new is that the week is **chosen** — first, heaviest, or a specific one — instead of being
an implicit week-1 rule. What has not changed is that weeks 2..N carry progression a single plan
definition cannot hold. The imported PROGRAM keeps every week in full; it is only the builder's
editable view that collapses.

---

## 9. Non-English support is shallow

The vocabulary carries German, Spanish, French and Portuguese words for sets, reps, session, week,
day and rest, plus weekday names — but they are applied only behind an explicit language hint, and
nothing sets that hint yet. In practice a German program parses because `3x8` is universal and the
exercise names often are too, not because the reader understands German.

RTL documents are read (the corpus has two) and names in other scripts are kept verbatim, but they
will not resolve against the index, so every one costs a confirmation tap.

---

## 10. The bundled OCR model costs APK size

The release APK is **51 MB**, of which roughly 17 MB is the ML Kit Latin text-recognition model.
That is the price of the offline constraint: the `play-services-mlkit-*` variant is a few hundred
kilobytes and downloads its model on first use, which would make the first photo import require a
network round trip and fail outright in airplane mode.

The trade is deliberate and it is the largest single cost of "fully offline" in this app. If size
ever becomes the binding constraint, the honest options are a Play Feature Delivery on-demand module
for the model (which reintroduces a download, just a better-managed one) or dropping photo import —
not silently switching to the downloading variant, which would break the promise without anyone
noticing until they were on a plane.

---

## 11. Things that are deliberately not done

- **Parallel sheet parsing.** A 50-sheet workbook reads in 121 ms. There is nothing to win, and
  pushing coroutines below the extraction boundary would cost the purity that makes the corpus a
  plain JVM test.
- **Parallel OCR pages.** It would multiply peak memory by the parallelism factor on a phone, to
  overlap work the user is already watching a progress bar for.
- **A second networked path.** Not a gap — a constraint. See [README.md](README.md).
- **Merging multiple files into one program.** A zip with three spreadsheets picks the best
  candidate and *says which*, rather than concatenating them into a fourth program nobody wrote.

---

## 12. Smaller sharp edges

| | |
|---|---|
| `.mhtml` | Only the first `text/html` part is read. Multi-part archives with the program split across parts will lose the rest. |
| YAML | Anchors, multi-document streams and block scalars are not resolved; the text passes through as lines. |
| Markdown tables | A table whose header names none of the known column roles is joined cell-by-cell into one line rather than reflowed. |
| `colour-semantics` | Recognises fully-shaded rows as headings. It does **not** yet group consecutive same-coloured rows into supersets, which was the other half of the idea. |
| Idempotency hash | Over the source BYTES, so the same document re-exported with a different timestamp inside it reads as a new document. |
| Learned aliases | Per-device `SharedPreferences`. They do not sync and are lost with the app's data. |
| `ExerciseLibrary` | The legacy 63-entry muscle map still exists alongside the 952-entry index, and is consulted as a last-resort fallback. Two sources of truth for the same question. |

---

## 13. What would move the number most

The corpus scoreboard sits at 91% parse rate / 89% resolution. Ranked by expected gain:

1. **Real photos in the corpus** (§2) — the only completely unmeasured path.
2. **Real app exports** (§1) — turns four "probably" adapters into four "yes" adapters.
3. **Retiring `ExerciseLibrary`** (§11) — one source of truth for what an exercise is.
4. **A language hint** (§9) — everything needed is already in the vocabulary.

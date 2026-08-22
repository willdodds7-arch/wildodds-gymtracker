# The Reader

Turns a written training program — in whatever form someone has it — into a program in the app.

**Fully on-device. Fully offline. Zero network.** Every path in this subsystem works in airplane
mode, with no account and no key, on first launch. `ReaderNoNetworkTest` reads the compiled class
files of the reader packages and fails the build if any of them so much as references a networking
API. The one permitted networked path in the app, `ClaudeApiClient`, is opt-in, off by default,
lives outside these packages, and is not reachable from them.

## The pages

| | |
|---|---|
| [00-baseline.md](00-baseline.md) | What the subsystem looked like before v2 — file by file, with the accuracy ceiling stated plainly. Kept because the diff is only meaningful against it. |
| [01-architecture.md](01-architecture.md) | How it works now: the pipeline, the packages, and why each decision went the way it did. |
| [02-formats.md](02-formats.md) | Every format it reads, how, and what is lost. |
| [03-notation.md](03-notation.md) | Every notation form the grammar parses. |
| [04-gaps.md](04-gaps.md) | **What it does not do, and what it gets wrong.** The most useful page here. |
| [05-extending.md](05-extending.md) | Adding a format, a layout, an app export, or a word. |

## The shape of it

```
bytes
  │
  ├─ Sniffer            magic bytes → structure → extension → MIME   (never extension alone)
  │
  ├─ Extractor          one per format family → ExtractedDocument(text, tables, title, confidence)
  │
  ├─ TextNormalizer     unicode, markdown, page furniture, hard wraps
  │
  ├─ route              1. a known app export   (exact schema — highest confidence)
  │                     2. a spreadsheet        (12 layout scorers, best wins, margin reported)
  │                     3. text                 (scored segmenter + grammar)
  │
  ├─ ExerciseResolver   8 layers over a 952-movement index, with semantic guards
  │
  ├─ flags + confidence per slot, session, week and program — each with its reasons
  │
  └─ DraftCompiler      → ParsedProgram → GymRepository.importProgram (transactional, recorded)
```

Every route converges on `UniversalProgramImporter.fromDraft`, so resolution, flagging and
compilation happen exactly once no matter where the bytes came from. That is what makes "a parser
improvement helps every format" true rather than aspirational.

## The rules that outrank everything else

1. **Never invent data.** A number that was not in the source is never written to the model. A
   missing set count stays `null` — `null` means "the source didn't say", which is not one set.
   Every guess is surfaced as a flag with the verbatim source line attached.
2. **Never lose the original.** `Exercise.rawSourceLine` is retained at **every** confidence level.
   It is the audit trail, and it is what makes a future parser fix retro-fixable.
3. **Determinism.** Same bytes in, same draft out, on every device and in every locale. No
   `Locale.getDefault()` in parsing, no iteration order leaking into output, no time, no randomness.
4. **Purity.** No Android types below the extraction boundary. That is what makes the 158-document
   corpus a plain JVM test.

## Where the numbers are

`CorpusTest` prints a scoreboard on every run and fails on regression against
`app/src/test/resources/reader/scoreboard-baseline.json`:

```
  documents            158
  parsed               145  (91%)
  blocked (correctly)    9
  errors                 4
  exercises read       366
  mean confidence      89%
  resolution hit rate  89%
  unconsumed chars      1%
```

Regenerate the corpus with `python tools/build_corpus.py`; regenerate the snapshots and baseline
with `-Dreader.corpus.update=true`. **The diff is the review** — that is why the 158 snapshots live
in one file.

# Formats

Every format the Reader accepts, how it reads it, and what is lost. Each row has at least one
corpus document behind it; the exceptions are called out.

Format is decided by **magic bytes and structure first, extension second, MIME third** — never
extension alone. See [01-architecture.md](01-architecture.md) §2.

---

## Text

| Format | Read | Notes |
|---|---|---|
| `.txt`, pasted text | Charset chain: BOM → strict UTF-8 → UTF-16 (NUL parity) → Windows-1252 | The strict UTF-8 decode is what makes the Latin-1 fallback reachable |
| `.md`, assistant output, Notion/Obsidian | `TextNormalizer` | Headings, bullets, emphasis, rules, blockquotes, tables reflowed |
| WhatsApp / iMessage / Discord / email | Per-message chrome stripped | `[21/03/2026, 19:04] Coach:` is a prefix on a program line |
| `.log` | As plain text | |

## Delimited

| Format | Read | Notes |
|---|---|---|
| `.csv`, `.tsv`, `.psv`, `;`-CSV | Real RFC-4180 reader | Quoted newlines, doubled quotes, ragged rows, a literal `"` mid-field |
| Delimiter | Inferred by **column-count variance** | Not by counting line one — that read per-set reps as columns |

## Spreadsheets

| Format | Read | Lost |
|---|---|---|
| `.xlsx`, `.xlsm`, `.xltx` | Own zip + `MiniXml`. Shared **and inline** strings, number formats, merged cells expanded, hidden rows/cols, frozen panes, cached formulas, defined names, fill colours | Charts, pivot tables, conditional formatting, cell comments |
| `.xls` (BIFF8) | Hand-rolled OLE2/CFB + BIFF records, no POI | Merged cells, number formats, hidden rows, uncached formulas. Encrypted files refused. See [gaps §3](04-gaps.md) |
| `.ods` | Zip + `content.xml`, honouring `number-columns-repeated` | Styles, fills |
| Google Sheets | Export as `.xlsx` or `.csv`; both read natively | |

## Documents

| Format | Read | Lost |
|---|---|---|
| `.docx` | Zip + `word/document.xml`: paragraphs, runs **and tables** | Headers/footers, footnotes, text boxes, images |
| `.odt` | Zip + `content.xml`: paragraphs and tables | Same |
| `.rtf` | Control words stripped, `\par` and `\cell` kept as structure. `\'hh`, `\uN`, ignorable destinations, nested groups | Formatting, embedded objects |
| `.doc` | **Refused** with a clear message | Reading it as a spreadsheet produced silent garbage |

## Web

| Format | Read | Lost |
|---|---|---|
| `.html`, `.htm` | `<table>` → grid, block tags → line breaks, `<script>`/`<style>` dropped, `<title>` kept | CSS-driven layout, JS-rendered content |
| `.mhtml` | First `text/html` part, quoted-printable decoded | Later parts — see [gaps §12](04-gaps.md) |
| Pasted rich text | As HTML | |

## PDF

| Format | Read | Notes |
|---|---|---|
| Digital | PDFBox **plus a positional pass**: glyph x/y clustered into lines by baseline, columns from an x-gap histogram | Reading order alone turned a four-column coach PDF into an undifferentiated stream — this is the single biggest coach-PDF fix |
| Scanned | Detected by character count per page, routed to OCR | Not an error and not an empty document — a different pipeline |
| Page furniture | Repeated headers/footers dropped by cross-page repetition | Never a line carrying a prescription |

## Images and OCR

| Format | Read | Notes |
|---|---|---|
| `.png`, `.jpg`, `.heic`, `.webp`, screenshots | ML Kit Text Recognition v2, **bundled model** | `com.google.mlkit:text-recognition`, not the Play-services variant — the bundled one works offline on first launch |
| Multi-page PDF, multi-image batch | One document, in the order picked | Four photos of a printout are one program, not four |
| Preprocessing | EXIF rotation, deskew (projection-profile), adaptive threshold, upscale | See [gaps §2](04-gaps.md) — untested against real photos |
| Repair | Baseline clustering, x-gap columns, confusables **inside numeric contexts only** | `1O` → `10`; `Row` is never touched |
| Refusal | Below the confidence threshold or the glyph floor, it fails loudly | "This image is too low quality to read — try a flatter, brighter photo" beats a program assembled from noise |

## Structured

| Format | Read | Notes |
|---|---|---|
| `.json` | Arrays of uniform objects → grids; everything else flattened to `key: value` lines | An export from an app with no adapter still imports |
| `.yaml`, `.yml` | Indentation, `key: value`, `- item` | Shallow — see [gaps §12](04-gaps.md) |
| `.xml` | Repeated sibling elements → grids; everything else to text | |

## Containers

| Format | Read | Notes |
|---|---|---|
| `.zip` | Enumerated, entries scored, best candidate extracted, alternatives reported | Zip-slip guarded, bomb-capped, depth-limited, entry-count capped |
| `.numbers`, `.pages` | The bundled preview PDF | Flagged as a preview; confidence × 0.85. See [gaps §4](04-gaps.md) |

## App exports

Parsed **natively** behind a signature match; a near miss falls through to the general pipeline.

| App | Shape | Verified |
|---|---|---|
| Strong | one row per set | published columns |
| Hevy | one row per set, snake_case, kg | published columns |
| FitNotes | one row per set, explicit unit column | published columns |
| JEFIT | one row per set | loosest signature — [gaps §1](04-gaps.md) |
| Boostcamp | week/day/exercise program table | authored fixture |
| TrainHeroic | week/day-title program table | authored fixture |
| Everfit | workout-title/exercise-title table | authored fixture |
| Trainerize | workout-name/target-reps table | authored fixture |
| Wild Odds JSON | `{"kind":"wildodds.program"}` | **exact** — this codebase writes it |
| Wild Odds template | `Program Info` + `W1D1` sheets | **exact** |

Log exports fold identical sets into a count and keep differing ones as real per-set rows. Their
weights are what was **performed**, not a prescription, and the import says so.

# Catalogue authoring — `add_program.py`

Appends a coach's program to the bundled catalogue (`app/src/main/assets/default_programs.xlsx`)
as a correctly-formatted `N-Title` worksheet plus a matching **Program Index** row, in the exact
layout `DefaultProgramXlsxParser` reads. After the 60/200-entry index cap was removed, the
catalogue can grow without limit and every program resolves its category + RPE/%1RM tracking from
the index.

## Two ways to add a coach's program

### 1. On-device AI import — the recommended route for a coach's PDF or text
Settings → AI Import → paste an Anthropic API key, then Home → **Import from Excel/AI** → upload the
file. Pipeline: `AiFileExtractor` → `ClaudeApiClient` (model `claude-haiku-4-5-20251001`) → the
review screen, where you confirm the parsed program before it's saved. No code or spreadsheet
editing required.

**Works for:** text-based PDFs, `.xlsx`/`.xls`, and plain `.txt`/`.csv`/`.md`.

**Rough edges (confirmed by reading the code; the path is unchanged by this change):**
- **No image / screenshot support.** `AiFileExtractor` only extracts *text* (PdfBox for PDFs,
  shared-strings for Excel, raw UTF-8 otherwise) and `ClaudeApiClient` sends **text only** — there
  is no OCR and no vision content block. A screenshot (PNG/JPG) or a scanned/image-only PDF yields
  no usable text. Supporting those would mean sending the image as a base64 image block to a
  vision-capable Claude model — a future enhancement, not wired today.
- Extracted text is **truncated at 14,000 chars**, so very long multi-week PDFs get cut (mitigated
  by the system prompt asking for one representative week).
- Only the **first 10 worksheets** of an `.xlsx` are read.
- Requires a user-supplied API key (stored in plaintext SharedPreferences) and a network call.

Use the AI route for one-off, on-device imports. Use the script below when you want the program
**baked into the shipped catalogue** for everyone.

### 2. The authoring script — bake a program into the shipped catalogue
```bash
python tools/add_program.py spec.json                     # append to the asset (writes a .bak first)
python tools/add_program.py spec.json --dry-run           # validate only; writes nothing
python tools/add_program.py spec.json --output copy.xlsx  # write a copy, leave the asset untouched
```
The script picks the next `N-` number automatically, writes the worksheet + index row, and
re-opens the saved file to validate the headers and index row. Requires `openpyxl`.

## Spec format — hand me ONE JSON file per coach, shaped like this

```json
{
  "name": "Coach Name 5-Day Upper/Lower",   // required — also the worksheet/index title
  "creator": "Coach Name",
  "level": "Intermediate",
  "goal": "Hypertrophy",                      // required — drives category (see mapping below)
  "intensity": "RPE",                         // "RPE" | "%1RM" | "RPE/%1RM" | "Linear" | ""
  "weeks": 4,                                  // informational (catalogue stores one representative week)
  "days_per_week": 5,
  "description": "One-line summary of the program.",
  "days": [                                    // required — one entry per training day
    {
      "name": "Day 1 - Upper",
      "exercises": [
        { "exercise": "Bench Press", "sets": 4, "reps": "6-8",
          "rpe": "8", "pct1rm": "", "weight": "", "rest": "180s", "superset": "", "notes": "" }
      ]
    }
  ]
}
```

See `tools/example_program_spec.json` for a complete, runnable example.

### `goal` → category mapping (what the catalogue groups by)
`Hypertrophy → Hypertrophy`, `Powerlifting → Powerlifting`, `Powerbuilding → Powerbuilding`,
`Strength → Strength`, `Athletic/GPP/Bodyweight → GPP`, `Conditioning → Conditioning`
(anything else → `Strength`).

### `intensity` → tracking
If the string contains `RPE`, the program tracks RPE; if it contains `%1RM`, it tracks %1RM.
`RPE/%1RM` enables both; `Linear`/empty enables neither. (Per-set RPE/%1RM values in the rows also
turn tracking on.)

### Column mapping (spec → catalogue sheet)
The catalogue sheet has 8 columns: `Week | Day/Focus | Exercise | Sets | Reps | RPE | %1RM | Notes`.
Your `exercise/sets/reps/rpe/pct1rm/notes` map 1:1. `weight`, `rest`, and `superset` have no column
in the catalogue, so they are folded into **Notes** (e.g. `… | @100kg | Rest 180s | Superset A`) so
nothing is lost.

## Validation
- The script re-opens the saved workbook and checks the new sheet's headers + index row.
- `DefaultProgramXlsxParserTest.toolAppendedProgramParsesCleanly` parses a tool-produced workbook
  through the real Kotlin parser; `shippedCatalogueParsesCleanly` guards the live asset.
- Regenerate the test fixtures with `app/src/test/resources/fixtures/make_catalogue_fixture.py`
  (the 65-entry no-cap fixture) and by re-running the script for `catalogue_appended66.xlsx`.

> The script does **not** fabricate programs. Hand me a real routine in the JSON shape above (or
> import it on-device via the AI route) and it becomes a catalogue sheet.

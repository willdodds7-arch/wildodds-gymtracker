#!/usr/bin/env python3
"""
Generates the Reader's golden corpus at app/src/test/resources/reader/corpus/.

Why generated: 120+ documents is far more than anyone will hand-author and keep consistent, and a
corpus of checked-in binaries is opaque — nobody can see what changed in a diff, or fix a fixture
without a spreadsheet application. Here every document is a few lines of Python, the whole corpus
regenerates in a second, and adding the 130th costs three lines.

The documents themselves ARE checked in (the test must not depend on Python), but they are
reproducible: re-running this script must produce byte-identical output, which is why nothing here
uses a timestamp or a random number.

Coverage, deliberately mapped to the spec:
  * every format the ingest layer claims to read
  * every notation form the grammar claims to parse
  * every layout the spreadsheet engine scores
  * the app-export adapters
  * and the hostile inputs — a recipe, a shopping list, an empty file, a 40-sheet workbook, an RTL
    document, a corrupt zip, a huge sheet — because a corpus of things that work proves nothing
    about what happens when something does not.

Run:  python tools/build_corpus.py
"""

import io
import os
import shutil
import zipfile

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "reader", "corpus")

files = {}          # name -> bytes


def text(name, body):
    files[name] = body.encode("utf-8")


def raw(name, data):
    files[name] = data


# ══════════════════════════════════════════════════════════════════════════════
# 1. Plain text and markdown — structure
# ══════════════════════════════════════════════════════════════════════════════

text("text/01-simple-day.txt", """Day 1
Bench Press 3x8
Barbell Row 3x10
Back Squat 5x5
""")

text("text/02-weeks-explicit.txt", """4-Week Strength Block

Week 1
Day 1 - Squat
Back Squat 3x5 @ 80%
Leg Press 3x10

Day 2 - Bench
Bench Press 3x5 @ 80%
Barbell Row 4x8

Week 2
Day 1 - Squat
Back Squat 3x3 @ 85%
Leg Press 3x10

Day 2 - Bench
Bench Press 3x3 @ 85%
Barbell Row 4x8
""")

text("text/03-week-range.txt", """Weeks 1-4
Day 1
Back Squat 5x5
Bench Press 5x5
""")

text("text/04-week-list.txt", """Weeks 1,3,5
Day 1
Deadlift 1x5
Pull Up 3x8
""")

text("text/05-compact-w1d3.txt", """W1D3
Overhead Press 5x3
Dip 3x10
""")

text("text/06-phase-header.txt", """Block A
Week 1
Day 1
Power Clean 5x3
Front Squat 4x5
""")

text("text/07-weekdays.txt", """Monday - Upper Body (Heavy)
Bench Press 4x6 @ RPE 8
Weighted Pull Up 4x6
Tuesday - Lower Body (Heavy)
Back Squat 4x5 @ RPE 8
Romanian Deadlift 3x8
Wednesday - REST
Thursday - Upper Body (Volume)
Incline Dumbbell Bench Press 4x10
Cable Row 4x12
""")

text("text/08-rest-day.txt", """Day 1 - Push
Bench Press 3x8
Day 2 - Rest
Day 3 - Pull
Barbell Row 3x8
""")

text("text/09-active-recovery.txt", """Sunday - Active Recovery
Monday - Push
Bench Press 3x8
""")

text("text/10-sections.txt", """Day 1
Warm-up
Assault Bike 5 min
Main
Back Squat 5x3 @ 85%
Accessories
Leg Press 3x12
Leg Curl 3x15
Cool down
Couch Stretch 2x60s each side
""")

text("text/11-day-letters.txt", """Workout A
Back Squat 3x5
Bench Press 3x5
Barbell Row 3x5
Workout B
Back Squat 3x5
Overhead Press 3x5
Deadlift 1x5
""")

text("text/12-session-with-focus.txt", """Session 1 (Push)
Bench Press 4x6
Overhead Press 3x8
Session 2 (Pull)
Barbell Row 4x6
Lat Pulldown 3x10
""")

text("text/13-deload.txt", """Week 4 - Deload
Day 1
Back Squat 3x5 @ 50%
Bench Press 3x5 @ 50%
""")

text("text/14-notes-and-prose.txt", """This block focuses on building the squat through higher frequency.

Day 1
Back Squat 5x5 @ 75%
Rest 2-3 minutes between working sets.
Leg Press 3x12
Keep the tempo controlled on the way down.
""")

text("text/15-indented-set-detail.txt", """Day 1
Back Squat
  set 1: 5 @ 70%
  set 2: 5 @ 80%
  set 3: 5 @ 85%
Bench Press 3x8
""")

text("text/16-no-headers.txt", """Bench Press 3x8
Barbell Row 3x10
Overhead Press 3x8
Lat Pulldown 3x12
""")

text("text/17-title-split-keyword.txt", """Squat & Overhead Press - 3 Days per Week

A simple strength block built around the back squat and overhead press.

Day 1 - Heavy Squat
Back Squat 4x5 @ 80%
Overhead Press 3x8 @ 70%
""")

# ══════════════════════════════════════════════════════════════════════════════
# 2. Markdown — the shape an assistant produces
# ══════════════════════════════════════════════════════════════════════════════

text("markdown/01-assistant.md", """# 4-Week Strength Block

## Week 1

**Day 1 — Squat**
1. Back Squat — 4 sets × 5 reps @ 80% 1RM
2. Leg Press — 3 × 10
3. Ab Wheel — 3 × 12

**Day 2 — Bench**
- Barbell Bench Press — 3x5 @ 85% TM
- Barbell Row — 4x8
- Tricep Pushdown — 3x15
""")

text("markdown/02-table.md", """# Push Pull Legs

| Exercise | Sets | Reps | Intensity |
|---|---|---|---|
| Back Squat | 3 | 5 | 65%, 75%, 85% TM |
| Leg Press | 3 | 10 | — |
| Ab Wheel | 3 | 12 | — |
""")

text("markdown/03-nested-bullets.md", """## Day 1

* Back Squat 5x5
  * warm up to the working weight
* Bench Press 5x5
* Barbell Row 5x5
""")

text("markdown/04-emoji.md", """## Day 1 \U0001F3CB

- Back Squat 5x5
- Burpees 3x20 \U0001F525
""")

# ══════════════════════════════════════════════════════════════════════════════
# 3. Notation — one document per family from the grammar spec
# ══════════════════════════════════════════════════════════════════════════════

NOTATION = [
    ("straight-sets", "Bench Press 3x8"),
    ("rep-range", "Barbell Curl 3x8-12"),
    ("alt-separators", "Barbell Row 4X10\nLat Pulldown 4*10\nCable Row 3 sets of 12"),
    ("per-set-slash", "Deadlift 5/3/1"),
    ("per-set-comma", "Front Squat 6, 8, 10"),
    ("pyramid", "Bench Press 12/10/8/6"),
    ("ladder", "Pull Up 1,2,3,4,5 x 5 rounds"),
    ("for-time", "Thruster 21-15-9"),
    ("amrap-final", "Bench Press 1x5+ @85% TM"),
    ("amrap-time", "Burpee AMRAP 12 min"),
    ("emom", "Power Clean EMOM 12"),
    ("e2mom", "Clean E2MOM 16"),
    ("otm", "Snatch OTM 10"),
    ("cluster", "Front Squat 3x(3.3.3) rest 15s"),
    ("rest-pause", "Lat Pulldown 1x8+2+2"),
    ("drop-set", "Leg Extension 3x10 drop set"),
    ("myo-reps", "Cable Fly 1x15 myo reps"),
    ("percent-1rm", "Back Squat 5x5 @75%"),
    ("percent-tm", "Overhead Press 3x5 @80% TM"),
    ("percent-e1rm", "Back Squat 3x3 @ 85% e1RM"),
    ("percent-bw", "Dip 3x8 @ 20% BW"),
    ("percent-wave", "Back Squat 70/80/90% TM"),
    ("percent-range", "Deadlift 5x3 @ 70-80%"),
    ("rpe", "Back Squat 3x5 @RPE8"),
    ("rpe-range", "Back Squat 3x5 @RPE 7-8"),
    ("rir", "Barbell Curl 3x12 2 RIR"),
    ("absolute-kg", "Bench Press 5x5 @100kg"),
    ("absolute-lb", "Back Squat 3x5 @ 225lb"),
    ("absolute-hash", "Back Squat 3x5 @ 225#"),
    ("load-range", "Bench Press 3x8 @ 60-70kg"),
    ("bodyweight-plus", "Weighted Pull Up 3x5 BW+25kg"),
    ("bodyweight-minus", "Assisted Pull Up 3x8 BW-20kg"),
    ("band", "Bench Press 3x3 @60% + red band"),
    ("chain", "Back Squat 5x2 + 20kg chain"),
    ("tempo-compact", "Back Squat 3x5 31X0"),
    ("tempo-dashed", "Back Squat 3x5 3-1-2-0"),
    ("tempo-word", "Back Squat 3x5 tempo 3010"),
    ("paused", "Paused Bench Press 3x3 paused 2s"),
    ("rest-fixed", "Bench Press 3x5 rest 3 min"),
    ("rest-range", "Bench Press 3x5 rest 2-3 min"),
    ("rest-seconds-range", "Bench Press 3x5 rest 90-120s"),
    ("rest-clock", "Bench Press 3x5 rest 1:30"),
    ("rest-as-needed", "Deadlift 5x1 rest as needed"),
    ("rest-walk-back", "Deadlift 5x1 walk back rest"),
    ("superset-no-rest", "A1) Bench Press 3x10 no rest"),
    ("superset-tag", "A1) Incline Dumbbell Bench Press 3x10\nA2) Cable Row 3x12"),
    ("superset-letter", "C - Face Pull 3x15"),
    ("time-seconds", "Plank 3x45s"),
    ("time-clock", "Plank 3 x 2:00"),
    ("time-each-side", "Side Plank 60s each side"),
    ("distance-metres", "Sprint 5x400m"),
    ("distance-row", "Row 2000m"),
    ("distance-km", "Treadmill Run 5km"),
    ("calories", "Assault Bike 15 cal"),
    ("each-side", "Bulgarian Split Squat 3x10 each side"),
    ("per-leg", "Step Up 3x12 per leg"),
    ("shorthand-es", "Farmer Carry 3x30m e/s"),
    ("to-failure", "Push Up 3 sets to failure"),
    ("total-reps", "Chin Up 50 total reps"),
    ("fsl", "Bench Press FSL 5x5"),
    ("bbb", "Bench Press BBB 5x10 @ 50%"),
    ("abbreviations", "RDL 3x8\nOHP 3x5\nCGBP 3x8\nBSS 3x10 each side\nGHR 3x8"),
    ("mixed-units", "Back Squat 3x5 @ 100kg\nBench Press 3x5 @ 225lb"),
    ("name-with-number", "Dumbbell Row 45 degree 3x10"),
    ("unparseable", "Some Weird Conditioning Finisher"),
]
for slug, body in NOTATION:
    text(f"notation/{slug}.txt", f"Day 1\n{body}\n")

# ══════════════════════════════════════════════════════════════════════════════
# 4. Delimited
# ══════════════════════════════════════════════════════════════════════════════

text("delimited/01-basic.csv", """Exercise,Sets,Reps
Bench Press,3,8
Barbell Row,3,10
Back Squat,5,5
""")

text("delimited/02-week-day.csv", """Week,Day,Exercise,Sets,Reps,RPE
1,Day 1,Back Squat,5,5,8
1,Day 1,Bench Press,5,5,8
1,Day 2,Deadlift,1,5,9
2,Day 1,Back Squat,5,3,8
""")

text("delimited/03-quoted-newline.csv", '''Exercise,Sets,Reps,Notes
Bench Press,3,8,"keep the elbows tucked
and pause on the chest"
Barbell Row,3,10,pull to the hip
''')

text("delimited/04-escaped-quotes.csv", '''Exercise,Reps,Notes
Pin Press,5,"stop at the ""sticking point"""
Barbell Row,10,
''')

text("delimited/05-semicolon.csv", """Ubung;Satze;Wdh
Bankdrucken;3;8
Rudern;3;10
Kniebeuge;5;5
""")

text("delimited/06-tab.tsv", """Exercise\tSets\tReps\tNotes
Bench Press\t3\t8\tkeep elbows in, chin tucked
Barbell Row\t3\t10\tsqueeze, pause
""")

text("delimited/07-ragged.csv", """Exercise,Sets,Reps
Bench Press,3,8
Barbell Row,3
Back Squat,5,5,extra
""")

text("delimited/08-no-header.csv", """Bench Press,3,8
Barbell Row,3,10
Back Squat,5,5
""")

text("delimited/09-pipe.psv", """Exercise|Sets|Reps
Bench Press|3|8
Barbell Row|3|10
""")

text("delimited/10-percent-column.csv", """Exercise,Sets,Reps,Intensity
Back Squat,5,5,75%
Bench Press,5,5,70%
""")

# ══════════════════════════════════════════════════════════════════════════════
# 5. App exports
# ══════════════════════════════════════════════════════════════════════════════

text("exports/strong.csv", """Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
2026-03-02 18:04:00,Push A,1h 5m,Bench Press,1,80,8,0,0,,,8
2026-03-02 18:04:00,Push A,1h 5m,Bench Press,2,80,8,0,0,,,8
2026-03-02 18:04:00,Push A,1h 5m,Bench Press,3,80,8,0,0,,,8
2026-03-02 18:04:00,Push A,1h 5m,Overhead Press,1,45,10,0,0,elbows in,,7
2026-03-04 18:00:00,Pull A,58m,Barbell Row,1,70,10,0,0,,,7
2026-03-04 18:00:00,Pull A,58m,Barbell Row,2,70,8,0,0,,,8
""")

text("exports/hevy.csv", """title,start_time,end_time,description,exercise_title,superset_id,exercise_notes,set_index,set_type,weight_kg,reps,distance_km,duration_seconds,rpe
Upper A,2026-03-02T18:00:00Z,2026-03-02T19:00:00Z,,Incline Bench Press,,,0,normal,60,10,,,7
Upper A,2026-03-02T18:00:00Z,2026-03-02T19:00:00Z,,Incline Bench Press,,,1,normal,60,10,,,7
Upper A,2026-03-02T18:00:00Z,2026-03-02T19:00:00Z,,Lat Pulldown,A,,0,normal,55,12,,,
""")

text("exports/fitnotes.csv", """Date,Exercise,Category,Weight,Weight Unit,Reps,Distance,Distance Unit,Time,Comment
2026-03-02,Barbell Squat,Legs,225,lbs,5,,,,felt heavy
2026-03-02,Barbell Squat,Legs,225,lbs,5,,,,
2026-03-02,Leg Press,Legs,180,lbs,12,,,,
""")

text("exports/jefit.csv", """mydate,exercise,belongSys,logs,unit
2026-03-02,Bench Press,0,80x8,kg
2026-03-02,Bench Press,0,80x8,kg
2026-03-02,Barbell Row,0,60x10,kg
""")

text("exports/trainheroic.csv", """Week,Day Title,Exercise,Sets,Reps,Load,Notes
1,Lower A,Back Squat,5,5,75%,build slowly
1,Lower A,Romanian Deadlift,3,8,,
2,Lower A,Back Squat,5,3,85%,
""")

text("exports/trainerize.csv", """Workout Name,Exercise Name,Target Sets,Target Reps,Rest Time,Notes
Full Body A,Goblet Squat,3,12,60s,keep the chest up
Full Body A,Push Up,3,15,45s,
""")

text("exports/everfit.csv", """Workout Title,Exercise Title,Sets,Reps,Weight,Note
Day 1 - Push,Dumbbell Bench Press,4,10,30,control the eccentric
Day 1 - Push,Cable Fly,3,15,,
""")

text("exports/boostcamp.csv", """Week,Day,Exercise,Sets,Reps,Intensity
1,Day 1,Bench Press,3,5,80%
1,Day 1,Barbell Row,3,8,
""")

text("exports/wildodds.json", """{"schemaVersion":1,"kind":"wildodds.program","name":"Winter Block","durationWeeks":6,
 "description":"Six weeks of upper/lower",
 "workouts":[
   {"name":"Upper A","kind":"TRAINING","exercises":[
      {"name":"Bench Press","notes":"tuck the elbows","sets":[
         {"type":"WORKING","reps":"5","rpe":"8","restSeconds":"180"},
         {"type":"WORKING","reps":"5","rpe":"8","restSeconds":"180"},
         {"type":"WORKING","reps":"5","rpe":"8","restSeconds":"180"}]}]},
   {"name":"Rest","kind":"REST","exercises":[]}]}
""")

# ══════════════════════════════════════════════════════════════════════════════
# 6. Structured
# ══════════════════════════════════════════════════════════════════════════════

text("structured/01-unknown.json", """{"program":"Block A","exercises":[
  {"exercise":"Bench Press","sets":3,"reps":8},
  {"exercise":"Barbell Row","sets":3,"reps":10},
  {"exercise":"Back Squat","sets":5,"reps":5}]}
""")

text("structured/02-program.yaml", """name: Upper Lower
weeks: 4
days:
  - name: Upper A
    exercises:
      - Bench Press 4x6
      - Barbell Row 4x6
  - name: Lower A
    exercises:
      - Back Squat 4x5
      - Romanian Deadlift 3x8
""")

text("structured/03-program.xml", """<?xml version="1.0"?>
<program name="Block A">
  <exercise><name>Bench Press</name><sets>3</sets><reps>8</reps></exercise>
  <exercise><name>Barbell Row</name><sets>3</sets><reps>10</reps></exercise>
</program>
""")

# ══════════════════════════════════════════════════════════════════════════════
# 7. HTML / RTF
# ══════════════════════════════════════════════════════════════════════════════

text("web/01-table.html", """<!DOCTYPE html><html><head><title>Upper Lower Split</title>
<style>.x{color:red}</style></head><body>
<h1>Week 1</h1><p>Push day</p>
<table><tr><th>Exercise</th><th>Sets</th><th>Reps</th></tr>
<tr><td>Bench Press</td><td>4</td><td>6</td></tr>
<tr><td>Barbell Row</td><td>4</td><td>8</td></tr></table>
<script>alert(1)</script></body></html>
""")

text("web/02-list.html", """<html><body><h2>Day 1</h2><ul>
<li>Back Squat 5x5</li><li>Bench Press 5x5</li><li>Barbell Row 5x5</li></ul></body></html>
""")

raw("web/03-word.rtf",
    r"""{\rtf1\ansi{\fonttbl{\f0 Arial;}}Week 1\par Bench Press\cell 3x8\cell\row Barbell Row\cell 3x10\cell\row}"""
    .encode("latin-1"))

# ══════════════════════════════════════════════════════════════════════════════
# 8. Chat exports
# ══════════════════════════════════════════════════════════════════════════════

text("chat/01-whatsapp.txt", """[21/03/2026, 19:04] Coach: Day 1
[21/03/2026, 19:04] Coach: Bench Press 3x8
[21/03/2026, 19:05] Coach: Barbell Row 3x10
[21/03/2026, 19:05] Coach: Back Squat 5x5
""")

text("chat/02-email-quote.txt", """> > Day 1
> > Bench Press 3x8
> > Barbell Row 3x10
""")

# ══════════════════════════════════════════════════════════════════════════════
# 9. Hostile inputs — a corpus of things that work proves nothing
# ══════════════════════════════════════════════════════════════════════════════

text("hostile/01-recipe.txt", """Roast chicken

Ingredients
1 whole chicken
2 lemons
4 cloves of garlic
200ml white wine

Method
Preheat the oven to 200C. Season the chicken generously.
Roast for 1 hour 20 minutes, basting every 20 minutes.
""")

text("hostile/02-shopping-list.txt", """Shopping list
- eggs
- milk
- oats
- chicken thighs
- 2 avocados
""")

text("hostile/03-empty.txt", "")

text("hostile/04-whitespace.txt", "   \n\n   \t\n")

text("hostile/05-lorem.txt", """Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor
incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud
exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.
""")

text("hostile/06-numbers-only.txt", "3 8\n5 5\n4 10\n3 12\n")

text("hostile/07-rtl.txt", """תוכנית אימון
יום 1
סקוואט 5x5
Bench Press 3x8
""")

text("hostile/08-single-word.txt", "Squat\n")

text("hostile/09-json-not-a-program.json", """{"users":[{"id":1,"name":"Ada"},{"id":2,"name":"Grace"}]}""")

text("hostile/10-html-blog.html", """<html><head><title>Why I love training</title></head><body>
<p>Training has been part of my life for fifteen years. It is not about the numbers.</p>
<p>It is about showing up, again and again, until it becomes who you are.</p></body></html>
""")

text("hostile/11-handwriting-note.txt", """(a photo of a whiteboard would go here)
""")

raw("hostile/12-corrupt.zip", b"PK\x03\x04" + b"\x00" * 40 + b"garbage")

raw("hostile/13-truncated.xlsx", b"PK\x03\x04\x14\x00\x00\x00\x08\x00" + b"\x11" * 60)

text("hostile/14-mixed-content.txt", """Meeting notes 12 March

Discussed Q2 roadmap with the team.
Action: Sam to write the spec by Friday.

Gym after:
Bench Press 3x8
Barbell Row 3x10
""")


# ══════════════════════════════════════════════════════════════════════════════
# 10. Spreadsheets — one per layout scorer, plus the pathological ones
# ══════════════════════════════════════════════════════════════════════════════

def xlsx(sheets, title=None):
    """sheets: list of (name, rows). Minimal but genuine OOXML."""
    shared = {}

    def sid(s):
        if s not in shared:
            shared[s] = len(shared)
        return shared[s]

    for _, rows in sheets:
        for row in rows:
            for cell in row:
                if cell != "" and not _is_number(cell):
                    sid(cell)

    def col_letters(i):
        n, out = i + 1, ""
        while n > 0:
            n -= 1
            out = chr(ord("A") + n % 26) + out
            n //= 26
        return out

    def esc(s):
        return (s.replace("&", "&amp;").replace("<", "&lt;")
                 .replace(">", "&gt;").replace('"', "&quot;"))

    buf = io.BytesIO()
    # A fixed date_time keeps the archive byte-identical between runs.
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        def put(name, body):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, body)

        put("[Content_Types].xml",
            '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
        put("xl/workbook.xml",
            '<?xml version="1.0"?><workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
            + "".join(f'<sheet name="{esc(n)}" sheetId="{i+1}" r:id="rId{i+1}"/>'
                      for i, (n, _) in enumerate(sheets))
            + "</sheets></workbook>")
        put("xl/_rels/workbook.xml.rels",
            '<?xml version="1.0"?><Relationships>'
            + "".join(f'<Relationship Id="rId{i+1}" Target="worksheets/sheet{i+1}.xml"/>'
                      for i in range(len(sheets)))
            + "</Relationships>")
        put("xl/sharedStrings.xml",
            f'<?xml version="1.0"?><sst count="{len(shared)}" uniqueCount="{len(shared)}">'
            + "".join(f"<si><t>{esc(s)}</t></si>" for s in shared)
            + "</sst>")
        put("xl/styles.xml",
            '<?xml version="1.0"?><styleSheet><fills count="2">'
            '<fill><patternFill patternType="none"/></fill>'
            '<fill><patternFill patternType="gray125"/></fill></fills>'
            '<cellXfs count="2"><xf numFmtId="0" fillId="0"/><xf numFmtId="9" fillId="0"/></cellXfs>'
            '</styleSheet>')
        if title:
            put("docProps/core.xml",
                '<?xml version="1.0"?><cp:coreProperties xmlns:cp="c" '
                'xmlns:dc="http://purl.org/dc/elements/1.1/">'
                f"<dc:title>{esc(title)}</dc:title></cp:coreProperties>")
        for i, (name, rows) in enumerate(sheets):
            body = ['<?xml version="1.0"?><worksheet><sheetData>']
            for r, row in enumerate(rows):
                body.append(f'<row r="{r+1}">')
                for c, cell in enumerate(row):
                    if cell == "":
                        continue
                    ref = f"{col_letters(c)}{r+1}"
                    if _is_number(cell):
                        body.append(f'<c r="{ref}"><v>{cell}</v></c>')
                    else:
                        body.append(f'<c r="{ref}" t="s"><v>{sid(cell)}</v></c>')
                body.append("</row>")
            body.append("</sheetData></worksheet>")
            put(f"xl/worksheets/sheet{i+1}.xml", "".join(body))
    return buf.getvalue()


def _is_number(s):
    try:
        float(s)
        return True
    except (TypeError, ValueError):
        return False


HDR = ["Exercise", "Sets", "Reps", "Load", "RPE", "Rest", "Notes"]


def exrow(name, sets="3", reps="8", load="", rpe="", rest="", notes=""):
    return [name, sets, reps, load, rpe, rest, notes]


raw("sheets/01-classic-template.xlsx", xlsx([
    ("Program Info", [["Program Name", "Winter Block"], ["Weeks", "8"]]),
    ("W1D1", [["Push Day"], ["Muscle Groups: Chest, Shoulders"], HDR,
              exrow("Bench Press", "5", "5"), exrow("Overhead Press", "3", "10")]),
    ("W1D2", [["Pull Day"], ["Muscle Groups: Back"], HDR,
              exrow("Barbell Row", "4", "8"), exrow("Lat Pulldown", "3", "12")]),
]))

raw("sheets/02-sheet-per-week.xlsx", xlsx([
    ("Week 1", [HDR, exrow("Back Squat", "5", "5"), exrow("Bench Press", "5", "5")]),
    ("Week 2", [HDR, exrow("Back Squat", "5", "3"), exrow("Bench Press", "5", "3")]),
    ("Week 3", [HDR, exrow("Back Squat", "5", "1"), exrow("Bench Press", "5", "1")]),
]))

raw("sheets/03-week-labels.xlsx", xlsx([
    ("Block", [HDR,
               ["Week 1"], exrow("Back Squat", "5", "5"), exrow("Bench Press"),
               ["Week 2"], exrow("Back Squat", "5", "3"), exrow("Bench Press")]),
]))

raw("sheets/04-repeating-days.xlsx", xlsx([
    ("Program", [HDR,
                 ["Day 1"], exrow("Bench Press"), exrow("Overhead Press"),
                 ["Day 2"], exrow("Barbell Row"), exrow("Lat Pulldown"),
                 ["Day 1"], exrow("Bench Press"), exrow("Overhead Press"),
                 ["Day 2"], exrow("Barbell Row"), exrow("Lat Pulldown")]),
]))

raw("sheets/05-column-groups.xlsx", xlsx([
    ("Block", [["Exercise", "Sets", "Reps", "Sets", "Reps", "Sets", "Reps"],
               ["Back Squat", "5", "5", "5", "3", "5", "1"],
               ["Bench Press", "5", "5", "5", "3", "5", "1"]]),
]))

raw("sheets/06-dates.xlsx", xlsx([
    ("Log", [["Date", "Exercise", "Sets", "Reps"],
             ["2026-03-02", "Back Squat", "5", "5"],
             ["2026-03-02", "Bench Press", "3", "8"],
             ["2026-03-04", "Deadlift", "1", "5"],
             ["2026-03-04", "Barbell Row", "3", "8"]]),
]))

raw("sheets/07-flat.xlsx", xlsx([
    ("Sheet1", [HDR, exrow("Back Squat", "5", "5"), exrow("Bench Press"), exrow("Barbell Row")]),
]))

raw("sheets/08-transposed.xlsx", xlsx([
    ("Block", [["", "Back Squat", "Bench Press", "Barbell Row", "Deadlift"],
               ["Sets", "5", "3", "3", "1"],
               ["Reps", "5", "8", "10", "5"]]),
]))

raw("sheets/09-one-row-per-set.xlsx", xlsx([
    ("Log", [["Exercise", "Reps", "Load"],
             ["Bench Press", "8", "80"], ["Bench Press", "8", "80"], ["Bench Press", "8", "80"],
             ["Barbell Row", "10", "60"], ["Barbell Row", "10", "60"], ["Barbell Row", "10", "60"]]),
]))

raw("sheets/10-calendar.xlsx", xlsx([
    ("March", [["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"],
               ["Bench Press 3x8", "Back Squat 5x5", "Rest", "Barbell Row 3x10",
                "Deadlift 1x5", "Rest", "Rest"]]),
]))

raw("sheets/11-metadata-block.xlsx", xlsx([
    ("Sheet1", [["Program", "Smith Off-Season"], ["Athlete", "J. Smith"],
                ["Start date", "2026-03-02"], [""],
                HDR, exrow("Back Squat", "5", "5"), exrow("Bench Press")]),
]))

raw("sheets/12-header-less.xlsx", xlsx([
    ("Sheet1", [["Back Squat", "5", "5", "100"],
                ["Bench Press", "3", "8", "80"],
                ["Barbell Row", "3", "10", "60"]]),
]))

raw("sheets/13-notes-with-exercise.xlsx", xlsx([
    ("Sheet1", [["Exercise", "Sets", "Reps", "Notes"],
                ["Back Squat", "5", "5", "last week you did Bench Press 3x8"],
                ["Bench Press", "3", "8", ""]]),
]))

raw("sheets/14-two-days-one-tab.xlsx", xlsx([
    ("Both", [HDR, ["Day 1"], exrow("Back Squat"), exrow("Bench Press"),
              ["Day 2"], exrow("Deadlift"), exrow("Barbell Row")]),
]))

raw("sheets/15-blank-first-sheet.xlsx", xlsx([
    ("Sheet1", [[""]]),
    ("Program", [HDR, exrow("Back Squat"), exrow("Bench Press")]),
]))

raw("sheets/16-forty-sheets.xlsx", xlsx(
    [(f"Week {i}", [HDR, exrow("Back Squat", "5", "5")]) for i in range(1, 41)]))

raw("sheets/17-rtl.xlsx", xlsx([
    ("תוכנית", [["Exercise", "Sets", "Reps"],
                                              ["סקוואט", "5", "5"],
                                              ["Back Squat", "3", "8"]]),
]))

raw("sheets/18-shopping-list.xlsx", xlsx([
    ("List", [["Item", "Qty"], ["Eggs", "12"], ["Milk", "2"], ["Oats", "1"]]),
]))

raw("sheets/19-words-for-numbers.xlsx", xlsx([
    ("Sheet1", [["Exercise", "Sets", "Reps"], ["Back Squat", "five", "5"]]),
]))

raw("sheets/20-big.xlsx", xlsx([
    ("Big", [HDR] + [exrow(f"Bench Press", "3", "8") for _ in range(2000)]),
]))

raw("sheets/21-superset-column.xlsx", xlsx([
    ("Sheet1", [["Exercise", "Sets", "Reps", "Superset"],
                ["Incline Dumbbell Bench Press", "3", "10", "A1"],
                ["Cable Row", "3", "12", "A2"],
                ["Back Squat", "5", "5", ""]]),
]))

raw("sheets/22-tempo-column.xlsx", xlsx([
    ("Sheet1", [["Exercise", "Sets", "Reps", "Tempo", "Rest"],
                ["Back Squat", "4", "6", "31X0", "180"],
                ["Romanian Deadlift", "3", "8", "3010", "120"]]),
]))

# ── zip of documents ─────────────────────────────────────────────────────────

def make_zip(entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    return buf.getvalue()


raw("archive/01-zip-of-csv.zip", make_zip([
    ("README.txt", b"how to use this"),
    ("block.csv", b"Exercise,Sets,Reps\nBench Press,3,8\nBarbell Row,3,10\n"),
]))

raw("archive/02-zip-two-programs.zip", make_zip([
    ("block-a.csv", b"Exercise,Sets,Reps\nBench Press,3,8\n"),
    ("block-b.csv", b"Exercise,Sets,Reps\nBack Squat,5,5\n"),
]))


# ── docx / ods ───────────────────────────────────────────────────────────────

def docx(body_xml):
    return make_zip([("word/document.xml", body_xml.encode("utf-8"))])


raw("office/01-paragraphs.docx", docx(
    '<w:document xmlns:w="w"><w:body>'
    '<w:p><w:r><w:t>Day 1</w:t></w:r></w:p>'
    '<w:p><w:r><w:t>Bench Press 3x8</w:t></w:r></w:p>'
    '<w:p><w:r><w:t>Barbell </w:t></w:r><w:r><w:t>Row 3x10</w:t></w:r></w:p>'
    "</w:body></w:document>"))

raw("office/02-table.docx", docx(
    '<w:document xmlns:w="w"><w:body>'
    '<w:p><w:r><w:t>Week 1</w:t></w:r></w:p>'
    "<w:tbl>"
    "<w:tr><w:tc><w:p><w:r><w:t>Exercise</w:t></w:r></w:p></w:tc>"
    "<w:tc><w:p><w:r><w:t>Sets</w:t></w:r></w:p></w:tc>"
    "<w:tc><w:p><w:r><w:t>Reps</w:t></w:r></w:p></w:tc></w:tr>"
    "<w:tr><w:tc><w:p><w:r><w:t>Bench Press</w:t></w:r></w:p></w:tc>"
    "<w:tc><w:p><w:r><w:t>3</w:t></w:r></w:p></w:tc>"
    "<w:tc><w:p><w:r><w:t>8</w:t></w:r></w:p></w:tc></w:tr>"
    "<w:tr><w:tc><w:p><w:r><w:t>Barbell Row</w:t></w:r></w:p></w:tc>"
    "<w:tc><w:p><w:r><w:t>3</w:t></w:r></w:p></w:tc>"
    "<w:tc><w:p><w:r><w:t>10</w:t></w:r></w:p></w:tc></w:tr>"
    "</w:tbl></w:body></w:document>"))

ODS_CONTENT = """<office:document-content xmlns:office="o" xmlns:table="t" xmlns:text="x">
<office:body><office:spreadsheet><table:table table:name="Week 1">
<table:table-row>
  <table:table-cell><text:p>Exercise</text:p></table:table-cell>
  <table:table-cell><text:p>Sets</text:p></table:table-cell>
  <table:table-cell><text:p>Reps</text:p></table:table-cell></table:table-row>
<table:table-row>
  <table:table-cell><text:p>Bench Press</text:p></table:table-cell>
  <table:table-cell><text:p>3</text:p></table:table-cell>
  <table:table-cell><text:p>8</text:p></table:table-cell></table:table-row>
<table:table-row>
  <table:table-cell><text:p>Barbell Row</text:p></table:table-cell>
  <table:table-cell><text:p>3</text:p></table:table-cell>
  <table:table-cell><text:p>10</text:p></table:table-cell></table:table-row>
</table:table></office:spreadsheet></office:body></office:document-content>"""

raw("office/03-spreadsheet.ods", make_zip([
    ("mimetype", b"application/vnd.oasis.opendocument.spreadsheet"),
    ("content.xml", ODS_CONTENT.encode("utf-8")),
]))

raw("office/04-document.odt", make_zip([
    ("mimetype", b"application/vnd.oasis.opendocument.text"),
    ("content.xml", """<office:document-content xmlns:office="o" xmlns:text="x">
<office:body><office:text>
<text:p>Day 1</text:p><text:p>Bench Press 3x8</text:p><text:p>Barbell Row 3x10</text:p>
</office:text></office:body></office:document-content>""".encode("utf-8")),
]))

# ── encodings ────────────────────────────────────────────────────────────────

raw("encoding/01-latin1.csv",
    "Übung,Sätze,Wdh\nKniebeuge,5,5\nBankdrücken,3,8\n".encode("latin-1"))

raw("encoding/02-utf16.txt",
    "﻿Day 1\nBench Press 3x8\nBarbell Row 3x10\n".encode("utf-16-le"))

raw("encoding/03-utf8-bom.txt",
    "﻿Day 1\nBench Press 3x8\n".encode("utf-8"))


# ══════════════════════════════════════════════════════════════════════════════

def main():
    if os.path.isdir(ROOT):
        shutil.rmtree(ROOT)
    for name, data in sorted(files.items()):
        path = os.path.join(ROOT, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
    total = sum(len(d) for d in files.values())
    print(f"wrote {len(files)} corpus documents to {ROOT} ({total // 1024} KB)")
    if len(files) < 120:
        raise SystemExit(f"corpus is too small: {len(files)} documents, minimum 120")


if __name__ == "__main__":
    main()

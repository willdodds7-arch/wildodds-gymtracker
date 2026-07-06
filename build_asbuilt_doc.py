# -*- coding: utf-8 -*-
"""Generates the Wild Odds Gym Tracker — As-Built & Configured reference PDF."""
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.lib import colors
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.platypus import (
    BaseDocTemplate, PageTemplate, Frame, Paragraph, Spacer, Table, TableStyle,
    PageBreak, ListFlowable, ListItem, Preformatted, HRFlowable, KeepTogether
)
from xml.sax.saxutils import escape

OUT = r"C:\Users\willd\WildOddsGymTracker\WildOdds_GymTracker_AsBuilt.pdf"

# Brand palette
DARK   = colors.HexColor("#1E3A5F")
ACCENT = colors.HexColor("#60B4E8")
LIGHT  = colors.HexColor("#B8DDF5")
BG     = colors.HexColor("#F0F8FF")
INK    = colors.HexColor("#1A1A2E")
GREY   = colors.HexColor("#5B6470")
CODEBG = colors.HexColor("#EEF4FB")

ss = getSampleStyleSheet()
styles = {}
styles['title']   = ParagraphStyle('title', parent=ss['Title'], textColor=DARK, fontSize=26, leading=30, spaceAfter=6)
styles['subtitle']= ParagraphStyle('subtitle', parent=ss['Normal'], textColor=GREY, fontSize=12, leading=16, alignment=TA_CENTER)
styles['h1']      = ParagraphStyle('h1', parent=ss['Heading1'], textColor=colors.white, fontSize=15, leading=19,
                                   backColor=DARK, borderPadding=(6,8,6,8), spaceBefore=18, spaceAfter=10, leftIndent=0)
styles['h2']      = ParagraphStyle('h2', parent=ss['Heading2'], textColor=DARK, fontSize=12.5, leading=16, spaceBefore=12, spaceAfter=4)
styles['h3']      = ParagraphStyle('h3', parent=ss['Heading3'], textColor=colors.HexColor("#2E6DA4"), fontSize=11, leading=14, spaceBefore=8, spaceAfter=2)
styles['body']    = ParagraphStyle('body', parent=ss['Normal'], textColor=INK, fontSize=9.5, leading=13.5, spaceAfter=5, alignment=TA_LEFT)
styles['small']   = ParagraphStyle('small', parent=ss['Normal'], textColor=GREY, fontSize=8.2, leading=11)
styles['bullet']  = ParagraphStyle('bullet', parent=styles['body'], spaceAfter=2)
styles['code']    = ParagraphStyle('code', parent=ss['Code'], fontSize=8, leading=10.5, textColor=INK, backColor=CODEBG,
                                   borderPadding=(6,6,6,6), spaceBefore=2, spaceAfter=8)
styles['cell']    = ParagraphStyle('cell', parent=ss['Normal'], fontSize=8.3, leading=11, textColor=INK)
styles['cellb']   = ParagraphStyle('cellb', parent=styles['cell'], textColor=colors.white, fontName='Helvetica-Bold')
styles['toc']     = ParagraphStyle('toc', parent=styles['body'], fontSize=10, leading=16, spaceAfter=0)

def P(t, s='body'):   return Paragraph(t, styles[s])
def H1(t):            return Paragraph(t, styles['h1'])
def H2(t):            return Paragraph(t, styles['h2'])
def H3(t):            return Paragraph(t, styles['h3'])
def SP(h=6):          return Spacer(1, h)
def rule():           return HRFlowable(width="100%", thickness=0.6, color=ACCENT, spaceBefore=2, spaceAfter=8)

def bullets(items, s='bullet'):
    return ListFlowable(
        [ListItem(Paragraph(i, styles[s]), leftIndent=10, value='•', bulletColor=ACCENT) for i in items],
        bulletType='bullet', start='•', leftIndent=12, spaceBefore=0, spaceAfter=6,
    )

def code(t):
    return Preformatted(t, styles['code'])

def table(rows, col_widths, header=True, font=8.3):
    data = []
    for r_i, row in enumerate(rows):
        cells = []
        for c in row:
            st = 'cellb' if (header and r_i == 0) else 'cell'
            cells.append(Paragraph(c, styles[st]))
        data.append(cells)
    t = Table(data, colWidths=col_widths, repeatRows=1 if header else 0)
    style = [
        ('VALIGN', (0,0), (-1,-1), 'TOP'),
        ('LEFTPADDING', (0,0), (-1,-1), 5),
        ('RIGHTPADDING', (0,0), (-1,-1), 5),
        ('TOPPADDING', (0,0), (-1,-1), 3.5),
        ('BOTTOMPADDING', (0,0), (-1,-1), 3.5),
        ('LINEBELOW', (0,0), (-1,-1), 0.4, colors.HexColor("#D4DEE9")),
    ]
    if header:
        style += [('BACKGROUND', (0,0), (-1,0), DARK),
                  ('LINEBELOW', (0,0), (-1,0), 0.6, DARK)]
    for r_i in range(1, len(rows)):
        if r_i % 2 == 0:
            style.append(('BACKGROUND', (0,r_i), (-1,r_i), BG))
    t.setStyle(TableStyle(style))
    return t

story = []

# ─────────────────────────── COVER ───────────────────────────
story += [
    SP(120),
    Paragraph("Wild Odds Gym Tracker", styles['title']),
    HRFlowable(width="40%", thickness=2, color=ACCENT, spaceBefore=8, spaceAfter=14),
    Paragraph("As-Built &amp; Configured Reference", ParagraphStyle('c', parent=styles['subtitle'], fontSize=16, textColor=DARK)),
    SP(10),
    Paragraph("A complete snapshot of the application as it currently exists and is configured — "
              "architecture, data model, every feature, configuration, and integration points — "
              "intended as the grounding context for AI-assisted planning and a new-feature roadmap.", styles['subtitle']),
    SP(40),
    Paragraph("Android application &nbsp;•&nbsp; package <b>com.wildodds.gymtracker</b>", styles['subtitle']),
    Paragraph("Version 2.0.0 (versionCode 2) &nbsp;•&nbsp; Database schema v13", styles['subtitle']),
    Paragraph("Document generated 19 June 2026", styles['subtitle']),
    PageBreak(),
]

# ─────────────────────────── TOC ───────────────────────────
toc_entries = [
    "1.  How to use this document",
    "2.  Product overview",
    "3.  Technology stack &amp; build configuration",
    "4.  Application architecture",
    "5.  Package / module map",
    "6.  Data model (Room schema v13)",
    "7.  Repository &amp; DAO layer",
    "8.  Navigation map",
    "9.  Feature catalogue",
    "10. Import &amp; parsing subsystem",
    "11. Default program catalogue (asset-driven)",
    "12. Configuration, feature flags &amp; secrets",
    "13. Sharing &amp; FileProvider",
    "14. Background work, receivers &amp; home-screen widget",
    "15. Data &amp; security model (access control / ABAC)",
    "16. Build, signing &amp; release artifacts",
    "17. Quality: tests &amp; static analysis",
    "18. Known constraints, tech debt &amp; risks",
    "19. Extension points (where new features plug in)",
    "20. Recent changes (this work cycle)",
    "21. Appendix — source file index",
]
story += [H1("Contents")]
for e in toc_entries:
    story.append(Paragraph(e, styles['toc']))
story.append(PageBreak())

# ─────────────── 1. HOW TO USE ───────────────
story += [H1("1.  How to use this document"),
    P("This is an <b>as-built</b> reference: it describes what the codebase does <i>today</i>, not an aspirational design. "
      "It is written to be pasted (in whole or in part) into an AI assistant as grounding context when planning new features, "
      "so that the assistant reasons about the real architecture instead of guessing."),
    bullets([
      "<b>Sections 2–8</b> establish the shape of the system: stack, architecture, data model, and navigation.",
      "<b>Section 9</b> is the feature catalogue — the &ldquo;what the app does&rdquo; inventory, screen by screen.",
      "<b>Sections 10–14</b> cover the subsystems that are easy to overlook (import/parsing, configuration, sharing, background work).",
      "<b>Sections 15–18</b> are the honest engineering picture: security posture, build/release reality, test coverage, and debt.",
      "<b>Section 19</b> is the most useful for roadmapping: the concrete seams where new capability attaches.",
    ]),
    P("Where a fact is a deliberate design choice or a known limitation, it is called out so the roadmap can treat it as a decision point rather than an accident."),
]

# ─────────────── 2. PRODUCT OVERVIEW ───────────────
story += [H1("2.  Product overview"),
    P("Wild Odds Gym Tracker is a <b>single-user, offline-first Android app</b> for planning and logging resistance-training programs. "
      "A user runs one <i>active program</i> at a time, works through its weeks and days, and logs weight / reps (plus optional RPE, %1RM, "
      "and per-side values) for each set. The app emphasises a frictionless logging loop (previous-week values carry forward as editable "
      "prefill) and a large built-in catalogue of ready-made programs."),
    H2("Primary capabilities at a glance"),
    bullets([
      "Run a program week-by-week / day-by-day with set logging and automatic carry-forward of last week's numbers.",
      "Import programs from Excel (smart auto-detection), a bundled 61-program catalogue, bulk multi-program/phased workbooks, or any file via AI.",
      "Build custom programs and reusable session templates in-app.",
      "Multi-phase periodised programs (e.g. Accumulation / Intensification / Peak) with a phase selector.",
      "Program analysis dashboard (weekly set volume per muscle group, push/pull balance, insights).",
      "History of completed sessions with training volume; a separate daily Habit tracker with a home-screen widget.",
      "Theming (dark mode, adjustable accent colour) and a set of toggotable convenience features.",
    ]),
    P("It is a <b>local app with no backend</b>. The only outbound network call is an optional, user-initiated request to the Anthropic "
      "Claude API for AI-assisted import (the user supplies their own API key)."),
]

# ─────────────── 3. STACK ───────────────
story += [H1("3.  Technology stack &amp; build configuration"),
    table([
        ["Area", "Choice / value"],
        ["Language", "Kotlin 1.9.24 (JVM target 17, Java 17 source/target)"],
        ["UI toolkit", "Jetpack Compose (BOM 2024.06.00 = Compose UI 1.6.8), Material 3, Compose compiler ext 1.5.14"],
        ["Architecture", "MVVM + single Repository; Kotlin Coroutines / Flow; AndroidX Lifecycle 2.8.2"],
        ["Persistence", "Room 2.6.1 (KSP codegen); Jetpack DataStore (Preferences) 1.1.1; SharedPreferences"],
        ["Navigation", "Navigation-Compose 2.7.7 (single-Activity, Compose nav graph)"],
        ["Async / net", "kotlinx-coroutines 1.8.1; OkHttp 4.12.0 (Claude API); Gson 2.11.0 (JSON)"],
        ["Images", "Coil 2.6.0 (program cover images)"],
        ["Widget", "Glance 1.1.0 (App Widget for habits)"],
        ["Docs/files", "PdfBox-Android 2.0.27.0 (used by the AI file-text extractor)"],
        ["Build", "AGP 8.4.2; KSP 1.9.24-1.0.20; Gradle KTS; version catalog (libs.versions.toml); multidex"],
        ["SDK levels", "compileSdk 36, targetSdk 36, minSdk 26 (Android 8.0+)"],
        ["App identity", "applicationId com.wildodds.gymtracker; versionName 2.0.0; versionCode 2"],
    ], [3.3*cm, 12.2*cm]),
    P("<b>Note:</b> AGP 8.4.2 is officially tested only to compileSdk 34, so the build emits a compileSdk-36 advisory warning (it compiles and packages fine). The project is a <b>single Gradle module</b> (<font face='Courier'>:app</font>).", 'small'),
]

# ─────────────── 4. ARCHITECTURE ───────────────
story += [H1("4.  Application architecture"),
    P("The app follows a conventional <b>MVVM + Repository</b> layering. Compose screens observe <font face='Courier'>StateFlow</font> "
      "exposed by <font face='Courier'>AndroidViewModel</font>s; view models call a single <font face='Courier'>GymRepository</font>; "
      "the repository is the only component that touches Room DAOs and orchestrates multi-table operations on the IO dispatcher."),
    code(
"UI (Jetpack Compose screens)\n"
"  observe StateFlow / collectAsStateWithLifecycle\n"
"        |\n"
"ViewModels (AndroidViewModel, one per screen area)\n"
"  HomeViewModel, SessionViewModel, SessionInfoViewModel, LibraryViewModel,\n"
"  SessionLibraryViewModel, CreateProgramViewModel, HistoryViewModel,\n"
"  HabitsViewModel, SettingsViewModel\n"
"        |\n"
"GymRepository  (single data choke point; withContext(Dispatchers.IO))\n"
"        |\n"
"Room DAOs  ->  AppDatabase (SQLite 'gym_tracker.db', schema v13)\n"
"\n"
"Cross-cutting: ThemePreferences (DataStore), SharedPreferences (gym_prefs,\n"
"exercise_notes, favourites), assets/ (xlsx catalogue + templates),\n"
"ClaudeApiClient (OkHttp -> Anthropic API)"),
    bullets([
      "<b>Single Activity</b>: <font face='Courier'>MainActivity</font> (ComponentActivity) sets the Compose content, enables edge-to-edge, and wires theme state from <font face='Courier'>ThemePreferences</font>. <font face='Courier'>GymApp</font> is the Application class.",
      "<b>Reactive home</b>: the Home screen derives entirely from Flows (<font face='Courier'>currentProgram</font>, all sessions, all completions, phases), so any data mutation (e.g. completing or resetting a day) refreshes the UI automatically.",
      "<b>Repository owns transactions</b>: operations that span tables (import, reorder, propagate-to-future-weeks, reset, restart) live in <font face='Courier'>GymRepository</font> and run off the main thread.",
      "<b>No DI framework</b>: dependencies are constructed directly (<font face='Courier'>AppDatabase.getInstance()</font> singleton, repository instantiated per view model).",
    ]),
]

# ─────────────── 5. PACKAGE MAP ───────────────
story += [H1("5.  Package / module map"),
    P("All code lives under <font face='Courier'>com.wildodds.gymtracker</font> in the single <font face='Courier'>:app</font> module."),
    table([
        ["Package", "Responsibility"],
        ["(root)", "MainActivity, GymApp (Application)"],
        ["data", "DefaultPrograms (in-code program defs, largely vestigial), ExerciseLibrary (muscle-group lookup)"],
        ["data.db / .db.entity / .db.dao", "AppDatabase + 11 entities + 9 DAOs"],
        ["data.repository", "GymRepository — the single data API"],
        ["data.parser / .parser.smart", "Excel import: Default, Bulk, Smart (7-method), Xlsx, Text parsers + ParsedModels"],
        ["data.ai", "ClaudeApiClient, AiFileExtractor, AiModels — AI-assisted import"],
        ["data.datastore", "ThemePreferences (DataStore-backed settings + feature flags)"],
        ["ui.home / .session / .library / .history / .habits / .create / .settings", "Screens + view models per feature area"],
        ["ui.navigation", "AppNavigation (NavHost + bottom-tab pager)"],
        ["ui.theme / .components", "Theme/Color/Typography; GlassCard, ProgressionBadge"],
        ["widget / receiver", "Glance habit widget + boot/midnight receivers + alarm scheduler"],
    ], [5.0*cm, 10.5*cm]),
]

# ─────────────── 6. DATA MODEL ───────────────
story += [H1("6.  Data model (Room schema v13)"),
    P("Room database <font face='Courier'>gym_tracker.db</font>, version <b>13</b>, <font face='Courier'>exportSchema = false</font>, "
      "with <b>12 hand-written migrations (1 -&gt; 13)</b> and <b>no destructive-migration fallback</b> (a missing migration would crash, "
      "preserving user data integrity). Eleven entities:"),
    table([
        ["Entity (table)", "Key fields", "Notes"],
        ["Program (programs)", "name, totalWeeks, category, isActive, isUserCreated, isPaused, isFlexible, trackRpe, trackOneRm, currentPhase, coverImage", "One row per program; exactly one isActive at a time"],
        ["ProgramPhase (program_phases)", "programId(FK), phaseNumber, name, durationWeeks, focus", "Only present when a program has &gt;1 phase"],
        ["Session (sessions)", "programId(FK), weekNumber, dayNumber, name, muscleGroups, phaseNumber", "One per (week, day); weeks are global/continuous across phases"],
        ["Exercise (exercises)", "sessionId(FK), name, sets, repsTarget, notes, orderIndex, rpeTarget, pct1rmTarget, supersetGroupId, unilateralMode", "The planned movement; position = (dayNumber, orderIndex)"],
        ["WorkoutLog (workout_logs)", "exerciseId(FK), sessionId(FK), weekNumber, completedAt, progressionChoice", "One per exercise per logged week"],
        ["SetLog (set_logs)", "workoutLogId(FK), setNumber, weightKg, reps, rpe, pct1rm, weightRightKg, repsRight", "Actual logged set values (per-side fields for unilateral)"],
        ["SessionCompletion (session_completions)", "sessionId(FK), weekNumber, completedAt", "Marks a day done; drives Home/History"],
        ["SessionTemplate (session_templates)", "name, muscleGroups, notes, source", "Reusable session in the Session Library"],
        ["SessionTemplateExercise (session_template_exercises)", "templateId(FK), name, sets, repsTarget, notes, orderIndex, rpeTarget, pct1rmTarget", "Exercises within a library session"],
        ["Habit (habits)", "name, emoji, createdAt", "Daily habit"],
        ["HabitCompletion (habit_completions)", "habitId, completedDate 'YYYY-MM-DD' (unique idx)", "One per habit per day"],
    ], [3.6*cm, 7.2*cm, 4.7*cm]),
    H2("Relationships &amp; cascade"),
    bullets([
      "Program 1—N Session 1—N Exercise 1—N WorkoutLog 1—N SetLog — all with <font face='Courier'>ON DELETE CASCADE</font>.",
      "Program 1—N ProgramPhase; Session 1—N SessionCompletion; SessionTemplate 1—N SessionTemplateExercise (cascade).",
      "<b>Cross-week carry-forward is positional</b>: prior-week prefill is looked up by (programId, dayNumber, orderIndex) rather than by exerciseId, so it survives exercise edits/reorders across weeks.",
    ]),
    H2("Migration history (additive)"),
    P("v1-&gt;2 coverImage; 2-&gt;3 habits + habit_completions; 3-&gt;4 isFlexible; 4-&gt;5 category; 5-&gt;6 isActive/isUserCreated; 6-&gt;7 session-name cleanup; "
      "7-&gt;8 trackRpe/trackOneRm + set rpe/pct1rm; 8-&gt;9 exercise rpeTarget/pct1rmTarget; 9-&gt;10 isPaused; 10-&gt;11 supersetGroupId; "
      "11-&gt;12 unilateralMode + per-side set columns; 12-&gt;13 phases (program_phases, phaseNumber, currentPhase) + session library tables.", 'small'),
]

# ─────────────── 7. REPOSITORY ───────────────
story += [H1("7.  Repository &amp; DAO layer"),
    P("<font face='Courier'>GymRepository</font> wraps nine DAOs and exposes the full data API. Representative operations:"),
    table([
        ["Group", "Operations"],
        ["Program lifecycle", "importProgram / addToLibrary, activateProgram, startProgramFromLibrary, pause / resume / restartPausedProgram, cancelProgram, deleteProgram, restartProgram, restartFromWeek2, startProgramAtPhase, setCurrentPhase"],
        ["Logging", "getOrCreateWorkoutLog, upsertSetLog, deleteSetLog, getSetLogsForWorkoutLog, getSessionSetLogs, updateProgressionChoice"],
        ["Carry-forward", "getPrevWeekSetLogsByPosition, getPrevWeekProgressionByPosition (positional, cross-week)"],
        ["Day state", "completeSession, isSessionCompleted, <b>resetSessionDay</b> (wipe a day's sets/logs/completion)"],
        ["Exercise editing", "add/delete/rename/reorder exercise, updateExerciseSets, link/unlink superset, setUnilateralMode, createExerciseAndPropagate (to future weeks)"],
        ["Analysis", "analyseProgram (sets/week per muscle via ExerciseLibrary), getAllWeekSetLogs (progression charts)"],
        ["Session library", "librarySessions, save/update/deleteSessionTemplate, syncLibraryFromPrograms"],
        ["Maintenance", "clearAllData"],
    ], [3.3*cm, 12.2*cm]),
]

# ─────────────── 8. NAVIGATION ───────────────
story += [H1("8.  Navigation map"),
    P("Single <font face='Courier'>NavHost</font>, start destination <font face='Courier'>main</font>. The <font face='Courier'>main</font> "
      "route hosts a 5-tab <font face='Courier'>HorizontalPager</font> (no per-tab back-stack); full-screen flows are separate routes."),
    table([
        ["Route", "Screen"],
        ["main", "MainPagerScreen -&gt; tabs: Home · Library · Habits · History · Settings"],
        ["session/{sessionId}/{weekNumber}", "SessionScreen — live set-logging view"],
        ["session_info/{sessionId}/{weekNumber}", "SessionInfoScreen — read-only view + share (NEW)"],
        ["create_program", "CreateProgramScreen — multi-step builder"],
        ["session_library", "SessionLibraryScreen — reusable session templates"],
    ], [6.2*cm, 9.3*cm]),
]

# ─────────────── 9. FEATURE CATALOGUE ───────────────
story += [H1("9.  Feature catalogue")]

story += [H2("9.1  Home (active program)"),
 bullets([
   "Banner/header with program name, current week, and completion progress (optional full-width image header via a feature flag).",
   "Week sections, each a grid of <b>day cards</b> showing day number, name, muscle groups, and Done / Next-up state.",
   "Per day card: tap to open the logging view; <b>info icon</b> to open the read-only session view without starting it (NEW).",
   "<b>Phase selector</b> appears only for multi-phase programs; it filters the week list to the selected phase's range.",
   "Program-level actions: Restart from Week 1, Restart using Week-2 weights as the new baseline, Cancel program; a completion dialog when every session is done.",
   "Entry points for importing/choosing programs when none is active.",
 ])]

story += [H2("9.2  Session logging (SessionScreen)"),
 bullets([
   "Horizontal pager of exercises (supersets render as a combined page); progress bar by exercises logged.",
   "Per set: weight x reps, plus optional <b>RPE</b> and <b>%1RM</b> columns (shown when the program tracks them), and <b>unilateral</b> modes (single weight + L/R reps, or full L/R weight+reps).",
   "<b>Carry-forward prefill</b>: last week's values appear in italic/accent as editable defaults; first-set weight auto-fills later sets until edited (toggleable).",
   "Add/delete sets (swipe to delete); add/rename/reorder/delete exercises; link supersets; per-exercise free-text notes (kept by exercise name).",
   "Optional rest timer / stopwatch with sound, and a 1RM (Epley) calculator.",
   "Per-exercise &lsquo;how to progress next week&rsquo; picker; <b>Complete Session</b> marks the day done.",
   "<b>Reset day</b> (NEW): overflow menu -&gt; wipes this day/week's logged sets and completion, reverting to carried-over prefill and unmarking it.",
 ])]

story += [H2("9.3  Session info &amp; sharing (NEW)"),
 bullets([
   "Read-only plan view of a session: each exercise with target sets x reps, RPE/%1RM targets, and notes — reachable without starting/logging.",
   "<b>Share as plain text</b> (formatted summary via the Android share sheet) and <b>Share as image</b> (a branded PNG rendered on an Android Canvas, shared via FileProvider).",
 ])]

story += [H2("9.4  Library, analysis &amp; program management"),
 bullets([
   "Browse the bundled default-program catalogue (grouped by category, searchable, filter by days/week) and a separate Browse-Defaults sheet.",
   "Your saved programs, favourites, paused programs; activate / start / restart-paused.",
   "<b>Program analysis dashboard</b>: weekly set volume per muscle group (primary 1.0x, secondary 0.5x via ExerciseLibrary), push/pull ratio, weakest/under-worked muscles vs MEV, balance verdict, and readable insight strings; per-exercise weekly progression snapshots.",
 ])]

story += [H2("9.5  Session Library, Program Builder, History &amp; Habits"),
 bullets([
   "<b>Session Library</b>: create/edit/delete reusable sessions; &lsquo;import days from my programs&rsquo; de-dupes program days into templates; load a template into a builder day.",
   "<b>Create Program</b>: multi-step builder (type, name, days/week, weeks, per-day exercises, RPE/%1RM tracking, cover image) producing a user program.",
   "<b>History</b>: list of completed sessions with computed total training volume (kg) per session.",
   "<b>Habits</b>: daily habit list with emoji, toggle-complete-today, streaks; a Glance <b>home-screen widget</b> with midnight reset.",
 ])]

story += [H2("9.6  Settings"),
 bullets([
   "Dark mode; accent colour via hue + saturation sliders (default hue 203, sat 0.72) with reset.",
   "Feature toggles (see §12); timer-sound selection; Claude API key entry; Clear-all-data.",
 ])]

# ─────────────── 10. IMPORT ───────────────
story += [H1("10.  Import &amp; parsing subsystem"),
 P("Importing converts an external source into the normalized in-memory model "
   "(<font face='Courier'>ParsedProgram -&gt; ParsedPhase / ParsedSession -&gt; ParsedExercise</font>), which the repository persists. "
   "Four ingestion paths exist:"),
 table([
   ["Path", "Source", "Parser / entry"],
   ["Smart Excel", "Any .xlsx (coach sheets, custom templates, official template)", "SmartXlsxParser — 7-method auto-detection; user reviews a preview before import"],
   ["Default catalogue", "Bundled assets/default_programs.xlsx", "DefaultProgramXlsxParser — one sheet per program + a Program Index sheet"],
   ["Bulk / phased", "assets/bulk_template.xlsx-shaped workbook", "BulkProgramXlsxParser — flat header-driven sheet, many programs, multi-phase; repeats one representative week per phase"],
   ["AI (any file)", "PDF / docs / text via the file extractor", "AiFileExtractor (PdfBox etc.) -&gt; ClaudeApiClient -&gt; review screen"],
 ], [2.7*cm, 5.6*cm, 7.2*cm]),
 P("A downloadable <font face='Courier'>template.xlsx</font> and <font face='Courier'>bulk_template.xlsx</font> ship as assets. "
   "The official single-program template has Program Info + per-day sheets with columns for Exercise, Sets, Reps, Weight, %1RM, RPE, Rest, Superset, Notes.", 'small'),
]

# ─────────────── 11. DEFAULT CATALOGUE ───────────────
story += [H1("11.  Default program catalogue (asset-driven)"),
 P("The browsable catalogue is generated <b>at runtime</b> from the bundled spreadsheet "
   "<font face='Courier'>app/src/main/assets/default_programs.xlsx</font> (parsed by <font face='Courier'>DefaultProgramXlsxParser</font>) — "
   "<b>not</b> from the Kotlin <font face='Courier'>ALL_DEFAULT_PROGRAMS</font> list, which is dead/vestigial."),
 bullets([
   "One worksheet per program named <font face='Courier'>N-Title</font>, plus a <font face='Courier'>Program Index</font> sheet (Goal drives category; Intensity drives RPE/%1RM tracking).",
   "Currently <b>61 programs</b> across Strength, Hypertrophy, Powerlifting, Powerbuilding, GPP, and Conditioning.",
   "Each program is a single representative week (the parser does not auto-repeat weeks); the Index &lsquo;Weeks&rsquo; column is informational.",
   "<b>Constraint:</b> the index parser stops after 60 entries — program #61+ falls back to its own sheet metadata for category/tracking.",
 ]),
]

# ─────────────── 12. CONFIG / FLAGS ───────────────
story += [H1("12.  Configuration, feature flags &amp; secrets"),
 H2("DataStore (ThemePreferences) — theming &amp; feature toggles"),
 table([
   ["Key", "Type", "Default"],
   ["dark_mode", "Bool", "false"],
   ["accent_hue / accent_sat", "Float", "203.0 / 0.72"],
   ["feat_session_timer", "Bool", "true"],
   ["feat_weight_autofill", "Bool", "true"],
   ["feat_add_exercise_mid_session", "Bool", "true"],
   ["feat_progression_picker", "Bool", "true"],
   ["feat_program_images", "Bool", "true"],
   ["feat_1rm_calculator", "Bool", "true"],
   ["timer_sound", "String", "\"ding\" (also \"ronnie\", \"none\")"],
 ], [7.4*cm, 2.6*cm, 5.5*cm]),
 H2("SharedPreferences"),
 bullets([
   "<font face='Courier'>gym_prefs</font>: <font face='Courier'>claude_api_key</font> (AI import), <font face='Courier'>favourites</font> (program names).",
   "<font face='Courier'>exercise_notes</font>: per-exercise user notes, keyed by exercise name (global across programs).",
 ]),
 H2("AI integration configuration"),
 table([
   ["Setting", "Value"],
   ["Endpoint", "https://api.anthropic.com/v1/messages (OkHttp)"],
   ["Model", "claude-haiku-4-5-20251001"],
   ["max_tokens", "4096"],
   ["anthropic-version", "2023-06-01"],
   ["Auth", "User-supplied key in SharedPreferences (x-api-key header)"],
 ], [4.2*cm, 11.3*cm]),
 P("<b>Secret handling:</b> the Claude API key is stored in plaintext SharedPreferences and sent directly to Anthropic from the device. "
   "There is no app backend and no secret beyond this user-provided key.", 'small'),
]

# ─────────────── 13. SHARING ───────────────
story += [H1("13.  Sharing &amp; FileProvider"),
 bullets([
   "A <font face='Courier'>FileProvider</font> is declared with authority <font face='Courier'>${applicationId}.fileprovider</font> and paths in <font face='Courier'>res/xml/file_paths.xml</font> (external-path, files-path, and a cache-path added for shared images).",
   "Used by: Excel template downloads (via the system document-create contract) and the new session image/text share (image PNG written to cache, shared with a content:// URI and a temporary read grant).",
   "Image rendering is done with <font face='Courier'>android.graphics.Canvas</font> because the project's Compose version (1.6.8) predates the graphicsLayer capture API.",
 ]),
]

# ─────────────── 14. BACKGROUND / WIDGET ───────────────
story += [H1("14.  Background work, receivers &amp; home-screen widget"),
 bullets([
   "<b>Habit widget</b> (Glance): HabitWidget / HabitWidgetProvider / HabitWidgetReceiver, with ToggleHabitCallback; companion dialog activities RenameHabitActivity and SetStreakActivity.",
   "<b>Midnight reset</b>: MidnightAlarmScheduler sets an exact alarm; MidnightResetReceiver refreshes the widget at day rollover; BootReceiver re-schedules on boot / app update.",
   "Permissions declared: INTERNET, RECEIVE_BOOT_COMPLETED, SCHEDULE_EXACT_ALARM, and legacy/scoped media-read permissions for older SDKs.",
 ]),
]

# ─────────────── 15. SECURITY / ABAC ───────────────
story += [H1("15.  Data &amp; security model (access control / ABAC)"),
 P("The app is <b>single-user and local</b>. There is intentionally <b>no authentication, no authorization layer, and no role/attribute-based "
   "access control</b> — every feature is available to whoever holds the unlocked device, and all data is the one user's own."),
 table([
   ["Concern", "Current state"],
   ["Identity / accounts", "None. No login, no user records, no multi-user separation."],
   ["Authorization", "None. No roles, permissions, or attribute checks gating any screen or operation."],
   ["Data at rest", "Room SQLite DB + DataStore + SharedPreferences in app-private storage, <b>unencrypted</b>. android:allowBackup=true (eligible for system/cloud backup)."],
   ["Data in transit", "Only the optional Claude API call (HTTPS, user's own key). No other network egress; no telemetry/analytics."],
   ["Secrets", "User's Claude API key in plaintext SharedPreferences."],
 ], [4.0*cm, 11.5*cm]),
 P("<b>Roadmap implication:</b> any feature involving accounts, cloud sync, sharing beyond the device, coach/athlete relationships, or "
   "multi-profile support would require introducing an identity + access-control layer (and likely at-rest encryption) that does not exist today. "
   "Treat that as a foundational workstream, not an add-on."),
]

# ─────────────── 16. BUILD / RELEASE ───────────────
story += [H1("16.  Build, signing &amp; release artifacts"),
 bullets([
   "Build via Gradle wrapper: set JAVA_HOME to Android Studio's JBR, then <font face='Courier'>:app:assembleDebug</font>. Room codegen via KSP.",
   "<b>Signing:</b> a single <font face='Courier'>debug</font> signing config (the standard debug keystore) is applied to <b>both</b> debug and release builds.",
   "<b>Release type:</b> <font face='Courier'>isMinifyEnabled = false</font> — no R8/shrinking/obfuscation despite proguard files being referenced.",
   "No CI/CD; APKs are produced locally and kept under <font face='Courier'>releases/</font>. Latest is a versioned debug APK; a pre-change backup is also stored there.",
 ]),
 P("<b>Implication:</b> the current release output is <b>not Play-Store-ready</b> (debug-signed, unminified). Production release would need a real "
   "keystore, R8 configuration, and likely an app-bundle pipeline.", 'small'),
]

# ─────────────── 17. QUALITY ───────────────
story += [H1("17.  Quality: tests &amp; static analysis"),
 bullets([
   "<b>Automated tests:</b> none — there are no <font face='Courier'>test/</font> or <font face='Courier'>androidTest/</font> sources. Verification today is build success + manual checks.",
   "<b>Lint:</b> last run reported 3 errors and 66 warnings. The 3 errors are <font face='Courier'>SuspiciousIndentation</font> false positives caused by the codebase's flat 2-space style (verified harmless). Warnings are routine (~39 outdated-dependency notices, unused resources, hardcoded strings, the compileSdk-36 advisory).",
 ]),
]

# ─────────────── 18. DEBT ───────────────
story += [H1("18.  Known constraints, tech debt &amp; risks"),
 bullets([
   "<b>No test suite</b> — highest-leverage gap for safe iteration; refactors and new features have no regression net.",
   "<b>Release not shippable as-is</b> — debug-signed and unminified (see §16).",
   "<b>Single module, no DI</b> — fine now, but will strain as features grow; the repository is becoming a large surface.",
   "<b>Dead code:</b> <font face='Courier'>ALL_DEFAULT_PROGRAMS</font> in DefaultPrograms.kt has no consumers (the live catalogue is the xlsx asset).",
   "<b>Catalogue parser cap</b> at 60 indexed programs (§11).",
   "<b>exportSchema = false</b> — no Room schema JSON history (harder to diff/migrate confidently).",
   "<b>Exercise notes are global by name</b> in SharedPreferences (not per-program/per-exercise-instance).",
   "<b>Toolchain drift:</b> AGP 8.4.2 vs compileSdk 36; Compose 1.6.8 lacks newer capture/APIs (worked around for image share).",
   "<b>Unencrypted local data + allowBackup=true</b> — acceptable for a personal tracker, a gap the moment data becomes sensitive or shared.",
 ]),
]

# ─────────────── 19. EXTENSION POINTS ───────────────
story += [H1("19.  Extension points (where new features plug in)"),
 P("The seams below are where most new capability naturally attaches. Anchor roadmap items to these to keep changes localized."),
 table([
   ["Seam", "What to do here"],
   ["GymRepository", "Add a new cross-table operation as a suspend function; it is the single data API every view model already uses."],
   ["Room: entity + migration", "Add/extend an entity, bump the DB version, and add a Migration(n, n+1) following the established additive pattern."],
   ["ParsedProgram / ParsedSession / ParsedExercise", "Normalize any new import source into these models and reuse importProgram — no persistence code needed."],
   ["AppNavigation routes", "Add a composable route (the session_info route is a recent worked example) and link it from the relevant screen."],
   ["ThemePreferences (DataStore)", "Add a new feature flag / setting key + default, and a Settings toggle."],
   ["ExerciseLibrary", "Extend muscle-group mappings to improve the analysis dashboard's coverage."],
   ["assets/default_programs.xlsx", "Add programs to the shipped catalogue by appending sheets + an Index row (no code change)."],
   ["ClaudeApiClient", "Adjust model/params or add new AI-assisted flows; the OkHttp call is isolated here."],
 ], [4.4*cm, 11.1*cm]),
 H2("Foundational workstreams a roadmap will likely need"),
 bullets([
   "<b>Identity &amp; access control</b> if accounts / sync / sharing / coach-athlete features appear (§15).",
   "<b>Test harness</b> (unit tests for the repository/parsers; instrumented tests for key flows) before large changes.",
   "<b>Release engineering</b> (real keystore, R8, app bundle, CI) before public distribution.",
   "<b>Backend / sync layer</b> if data must leave a single device — none exists today.",
 ]),
]

# ─────────────── 20. RECENT CHANGES ───────────────
story += [H1("20.  Recent changes (this work cycle)"),
 bullets([
   "Added 15 programs to the shipped catalogue across two batches (now 61 total) by appending sheets to <font face='Courier'>default_programs.xlsx</font>.",
   "<b>Reset day</b>: new DAO deletes + <font face='Courier'>GymRepository.resetSessionDay</font> + <font face='Courier'>SessionViewModel.resetDay</font>, surfaced via a SessionScreen overflow menu with confirm dialog.",
   "<b>Session info &amp; share</b>: new read-only <font face='Courier'>SessionInfoScreen</font> / <font face='Courier'>SessionInfoViewModel</font> (route session_info), an info button on Home day cards, and share-as-image (Canvas PNG via FileProvider) / share-as-text.",
   "All changes compile (<font face='Courier'>assembleDebug</font> succeeds); the rebuilt APK was verified to bundle the updated catalogue.",
 ]),
]

# ─────────────── 21. APPENDIX ───────────────
story += [H1("21.  Appendix — key source file index"),
 table([
   ["Concern", "File(s)"],
   ["Entry / app", "MainActivity.kt, GymApp.kt, ui/navigation/AppNavigation.kt"],
   ["Database", "data/db/AppDatabase.kt, data/db/entity/*.kt, data/db/dao/*.kt"],
   ["Repository", "data/repository/GymRepository.kt"],
   ["Home", "ui/home/HomeScreen.kt, ui/home/HomeViewModel.kt"],
   ["Logging", "ui/session/SessionScreen.kt, ui/session/SessionViewModel.kt"],
   ["Session info/share", "ui/session/SessionInfoScreen.kt"],
   ["Library/analysis", "ui/library/LibraryScreen.kt, ui/library/LibraryViewModel.kt"],
   ["Session library", "ui/library/SessionLibraryScreen.kt + ViewModel"],
   ["Builder", "ui/create/CreateProgramScreen.kt + ViewModel"],
   ["History / Habits", "ui/history/*, ui/habits/*"],
   ["Settings/theme", "ui/settings/*, data/datastore/ThemePreferences.kt, ui/theme/*"],
   ["Import/parse", "data/parser/*.kt, data/parser/smart/*.kt, data/parser/ParsedModels.kt"],
   ["AI", "data/ai/ClaudeApiClient.kt, AiFileExtractor.kt, AiModels.kt"],
   ["Widget/receivers", "widget/*.kt, receiver/*.kt"],
   ["Assets", "assets/default_programs.xlsx, template.xlsx, bulk_template.xlsx"],
 ], [4.0*cm, 11.5*cm]),
 SP(10),
 P("End of document — generated from a direct read of the codebase as it stands on 19 June 2026.", 'small'),
]

# ─────────────── BUILD ───────────────
def on_page(canvas, doc):
    canvas.saveState()
    # footer
    canvas.setStrokeColor(ACCENT); canvas.setLineWidth(0.5)
    canvas.line(2*cm, 1.4*cm, A4[0]-2*cm, 1.4*cm)
    canvas.setFont("Helvetica", 7.5); canvas.setFillColor(GREY)
    canvas.drawString(2*cm, 1.0*cm, "Wild Odds Gym Tracker — As-Built & Configured Reference")
    canvas.drawRightString(A4[0]-2*cm, 1.0*cm, "Page %d" % doc.page)
    canvas.restoreState()

doc = BaseDocTemplate(OUT, pagesize=A4,
                      leftMargin=2*cm, rightMargin=2*cm, topMargin=1.8*cm, bottomMargin=1.8*cm,
                      title="Wild Odds Gym Tracker — As-Built & Configured Reference",
                      author="Wild Odds")
frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id='main')
doc.addPageTemplates([PageTemplate(id='all', frames=[frame], onPage=on_page)])
doc.build(story)
print("WROTE", OUT)

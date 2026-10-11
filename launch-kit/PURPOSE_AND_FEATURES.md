# Wild Odds Gym Tracker — purpose and features

## Purpose

A gym tracker that does one job: get your program onto your phone and log your training against it,
with nothing in the way.

It is deliberately minimal:

- **No cloud.** The app has no internet permission. Android itself stops it, and every library in
  it, from reaching the network.
- **No tracking.** No analytics, no crash reporting, no advertising ID.
- **No account.** Nothing to sign up for. Open it and train.
- **No ads, no payments, no subscriptions.** Free, with nothing to buy.
- **No pop-ups.** Reminders and nudges are off until you turn them on. Nothing appears that you
  didn't ask for.
- **Open source** (MIT). Anyone can read exactly what it does.

The selling point is the importer: hand it a program in whatever form your coach sent it, and it
becomes a structured, trackable program in seconds, without the program ever leaving your phone.

## Features

### Import any program
- Paste text, or open a **screenshot, photo, PDF, Excel / Google Sheets / LibreOffice spreadsheet,
  Word document, CSV** or a chat export.
- Text in images is read on the phone (bundled ML Kit text recognition).
- Understands how lifters write programs: `3x8`, `4 sets of 6-8`, `10/8/6`, `@RPE8`, `@75%`,
  `1x5+ @85% TM`, tempo `31X0`, supersets (`A1/A2`), EMOM, timed and distance work, per-side reps,
  week and day headings, rest days.
- Reads English, German, Spanish, French, Portuguese and Italian.
- Fixes typos and gym shorthand (RDL, OHP, DB…) against an exercise library.
- A review step shows confidence and flags anything uncertain instead of guessing.
- Accuracy is measured on 77 documents with answer keys, including two batches written after tuning
  to test new styles: 100% of fields read correctly.

### Build and run programs
- Program builder: templates, days, supersets, set types, scheduling.
- 12-week linear progression (add sets → add reps → add weight → rebuild).
- A built-in catalogue of programs, filterable by days per week.
- A 3-week AMRAP cycle with training-max-driven weights.
- Move an exercise to another day; reorder days without losing logged progress.

### Log sessions
- Weight × reps per set, with weight auto-fill you can toggle per exercise.
- Coach notes (from the program, read-only) and your own athlete notes that carry over.
- Rest timer with sound options, plate calculator, 1RM / %1RM calculator.
- Left/right logging for unilateral work, supersets, add an exercise mid-session.
- Exercise swaps, easier/harder variations, travel mode for minimal equipment.

### See progress
- Charts with axes for PRs, volume and consistency; estimated 1RMs.
- Training history, favourite muscle group and split, your maxes.
- Optional achievements and streaks, and a habit tracker with a home-screen widget.

### Your data
- Export to CSV or JSON; restore from a JSON backup.
- One-tap **Delete all my data**.
- Optional Health Connect: read heart rate during a session; optionally write workouts back.

## What it deliberately does not do

No social feed, no cloud sync, no AI, no coaching upsell, no accounts, no ads, no analytics.

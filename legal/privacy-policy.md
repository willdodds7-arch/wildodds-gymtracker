# Privacy Policy

**Version 2.0 — Last updated 17 August 2026**

> Have this policy reviewed by a qualified lawyer before you publish to a store.

This Privacy Policy explains what Wild Odds Gym Tracker ("the app") does with your data. It
describes what the app actually does today — not aspirations.

## The short version

Wild Odds Gym Tracker is a **free, open-source, on-device** Android app for planning and logging
resistance-training programs. **There is no account, no sign-in, and no server run by us.** Your
programs, sessions, exercises and workout logs are stored **only on your device**. We do not
collect, receive, or have any way to see your data.

There is exactly **one** case where data leaves your device, and only if you choose it: **AI
Import** (see below). Everything else — logging, program building, analysis, the plate and 1RM
calculators, reminders — works fully offline.

## What we collect

**Nothing.** The app has no backend and no analytics uploader. We, the developers, receive no
personal data, no training data, and no identifiers from your use of the app.

**Your training data** (programs, sessions, exercises, weights, reps, RPE, %1RM, per-side values,
notes) is created and stored **locally** in an on-device database. It is never uploaded by the app.

**On-device usage events (optional, and local-only).** The app contains a "Share usage statistics"
setting, **off by default**. When on, it records coarse, non-identifying events (e.g. which screen
was opened) to a local table on your device. **There is no server to receive them** — they are
never transmitted and never leave your device. You can turn this off at any time in Settings, which
also clears anything queued locally.

## The one exception: AI Import (opt-in, your own AI provider)

The app includes an optional **AI Import** feature that can turn a coach's program (text, CSV, PDF,
or spreadsheet) into a structured program.

- The **on-device importer is the default** and does all parsing locally with no network access.
- The **AI-assisted path is used only if you add your own third-party AI API key** in Settings and
  choose to use it. When you do, the specific program content you are importing (and only that
  content) is sent over an encrypted connection to that AI provider's API —
  **Anthropic** (`api.anthropic.com`) — using your key, to structure it. Your key is stored only on
  your device.
- This is **your** direct arrangement with that provider. Their handling of that request is governed
  by **their** privacy policy and terms, not ours. If you never add a key, nothing is ever sent.

If you are importing a coach's or client's program and want to keep it entirely private, use the
on-device importer (the default) and do not add an AI key.

## Health & fitness data (Health Connect)

If you optionally connect Health Connect, the app reads the data you grant (e.g. heart rate) to show
it alongside your session, on your device. This is covered in the in-app **Health & Fitness Data
Notice**. Nothing read from Health Connect is uploaded by the app.

## What we do NOT do

We do **not**: run accounts or a backend; upload your training logs; use ads or advertising
identifiers; sell or share your data; collect precise location, contacts, photos, or biometric
identifiers; or track you across apps.

## Your controls

- **Export.** Settings → Export your training data as JSON/CSV, on-device.
- **Delete.** Uninstalling the app removes its on-device database. You can also clear data from
  Android Settings → Apps, or delete individual programs/logs in-app.
- **Turn off usage statistics** at any time (it is off by default and never transmitted regardless).

## Children

The app is a general-audience fitness tool and collects nothing. It is not directed at children
under 13.

## Changes

If this policy changes, we'll update the version and date above and publish the new version with the
app.

## Contact

Questions: **wildoddssupport@gmail.com** · Source code and issues:
<https://github.com/willdodds7-arch/wildodds-gymtracker>

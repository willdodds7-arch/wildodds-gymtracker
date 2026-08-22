# Wild Odds Gym Tracker

**A free, open-source, 100% on-device gym & strength-training tracker for Android.**

No account. No cloud. No ads. No tracking. Your programs and workout logs live only on your phone —
which makes it a tracker you can trust with a coach's or client's programming without handing that
programming to anyone else.

[![License: MIT](https://img.shields.io/badge/License-MIT-informational.svg)](LICENSE)
&nbsp;Platform: Android 8.0+ (API 26) &nbsp;·&nbsp; Kotlin · Jetpack Compose · Room

---

## Why it exists

Most workout apps want an account, sync your data to a server, and monetise your attention. Wild
Odds does the opposite: everything runs locally. A coach can build a client's block in it, or a
lifter can track a paid program, and that programming never leaves the device. It's free and the
source is open, so you can verify exactly what it does.

## Features

- **On-device by design** — all training data in a local Room database; works fully offline.
- **Universal Program Import** — paste text/CSV or pick a file (`.csv .txt .pdf .xlsx`) and it turns
  a written program into a structured, editable one, on-device, with a review-and-confirm step that
  flags anything low-confidence instead of guessing. Understands real notation: `3x8`, `8-12`,
  `5/3/1`, `10, 8, 6`, `@RPE8`, `@75%`, `1x5+ @85% TM`, tempo `31X0`, supersets, EMOM, and more.
- **Session logging** — per-set weight/reps, RPE and %1RM tracking, supersets, unilateral modes, a
  rest timer, plate calculator, and a 1RM calculator.
- **Programs & blocks** — multi-week, multi-phase programs; a curated built-in catalogue; a program
  builder; reorder days without losing progress.
- **Progress** — trends, estimated 1RMs, and a profile with your maxes and achievements.
- **Optional Health Connect** — read heart rate to show alongside a session (local only).
- **120 Hz-ready** — opts into the display's highest refresh rate for smooth scrolling.

## Privacy in one paragraph

The app collects **nothing** and has no backend. The only time anything leaves your device is if
**you** add your own AI provider key and use the optional AI-assisted import — then, and only then,
the specific program you're importing is sent to that provider (Anthropic) to structure it. The
default importer is fully on-device. An in-app "usage statistics" toggle is off by default and, even
when on, only records events locally (there is no server to receive them). See
[`legal/privacy-policy.md`](legal/privacy-policy.md).

## Building from source

Set `JAVA_HOME` to a JDK 17 (Android Studio's bundled JBR works), then use the Gradle wrapper:

```bash
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleOfflineDebug
```

The installable debug APK lands at
`app/build/outputs/apk/offline/debug/app-offline-debug.apk`.

### Release build

`assembleOfflineRelease` builds a minified (R8) release. Signing is read from a **gitignored**
`keystore.properties` (or CI env vars); without it the release falls back to debug signing so you
can smoke-test R8 locally (that artifact is **not** Play-uploadable). See
[`docs/launch-runbook.md`](docs/launch-runbook.md) and
[`docs/play-store.md`](docs/play-store.md) for signing and store submission.

## Tests

```bash
.\gradlew.bat :app:testOfflineDebugUnitTest
```

Local JVM/Robolectric suite covers the import engine, the set-notation parser, program compilation,
Room migrations, the Excel parser, and Compose settings. Instrumented smoke tests live in
`app/src/androidTest/`.

## Contributing

Issues and pull requests are welcome. The codebase is a single `:app` module: Kotlin + Compose,
MVVM with one `GymRepository`, Room for storage. Keep migrations additive (never destructive
fallback) and export the Room schema on every DB version bump.

## License

[MIT](LICENSE) © Wild Odds. You are free to use, modify, and distribute the app and its source.

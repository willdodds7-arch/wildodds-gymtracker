# Wild Odds Gym Tracker

Single-user, offline-first Android app (Kotlin + Jetpack Compose + Material 3, MVVM + a single
`GymRepository`, Room). See `WildOdds_GymTracker_AsBuilt.pdf` for the full as-built reference.

- **Package:** `com.wildodds.gymtracker`
- **Module:** single `:app`
- **DB:** Room `gym_tracker.db`, schema v13 (additive migrations only)

## Building

Set `JAVA_HOME` to Android Studio's bundled JBR, then use the Gradle wrapper:

```powershell
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

## Running the tests

The full local (JVM) test suite — repository/engine logic, the Room migration check, the
Excel parser, and the Compose Settings flow — runs on Robolectric with **one command**:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest
```

HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`.

What it covers (`app/src/test/`):

| Test | What it locks down |
|------|--------------------|
| `data/db/MigrationTest` | All migrations v1 → v13 apply cleanly; final schema matches the exported `13.json`. |
| `data/repository/GymRepositoryTest` | `getPrevWeekSetLogsByPosition` carry-forward by `(programId, dayNumber, orderIndex)`. |
| `data/parser/smart/SmartXlsxParserTest` | `SmartXlsxParser` classic-template parsing (fixture: `src/test/resources/fixtures/`). |
| `ui/settings/SettingsScreenTest` | Settings screen renders and the Dark Mode toggle flips (DataStore round-trip). |

### Instrumented tests (need a device/emulator)

A smoke test lives in `app/src/androidTest/`. Run it against a connected device/emulator:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Room schema export — REQUIRED for every migration

`AppDatabase` has `exportSchema = true`; KSP writes one JSON per DB version to
`app/schemas/com.wildodds.gymtracker.data.db.AppDatabase/<version>.json`. **These files are
checked in** and are the source of truth the migration test validates against. They are bundled
as debug-only assets (so Robolectric can read them) and are not included in release builds.

When you add a migration:

1. Add/extend the entity and write `Migration(n, n+1)` in the established additive style;
   add it to `AppDatabase.ALL_MIGRATIONS`.
2. Bump `@Database(version = …)` by exactly 1.
3. Build once so KSP regenerates the schema, and **commit the new `<version>.json`**.
4. Extend `MigrationTest` to cover the new step
   (`helper.createDatabase(name, n)` → `helper.runMigrationsAndValidate(name, n+1, …)`),
   then run `:app:testDebugUnitTest` and confirm green.

Never use destructive migration fallback — a missing migration must crash, preserving user data.

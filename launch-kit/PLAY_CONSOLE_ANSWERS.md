# Google Play — compliance worksheet

The master worksheet for the Play Console: the exact answers to give, and where each comes from.
**Accurate to the offline build (2026-10-11):** no accounts, no server, no internet permission, no
analytics, no ads, no in-app purchases. Health Connect is heart-rate read + optional workout write-back.

> **Ordered submission runbook lives in [`CHECKLIST.md`](CHECKLIST.md)** — this file is
> the reference answers; that file is the step-by-step you follow. Earlier versions of this worksheet
> described accounts, sync and Friends; those features were removed in the offline rebuild.

## App identity & SDK levels

| Field | Value | Notes |
|---|---|---|
| Package / applicationId | `com.wildodds.gymtracker` | permanent once uploaded |
| compileSdk / targetSdk | 36 (Android 16) | re-verify Play's minimum at submission time |
| minSdk | 26 (Android 8.0) | |
| versionCode scheme | monotonic integer, +1 per Play upload | `versionName` is the human string (e.g. `2.1.0`) |

## Required URLs

| Purpose | URL |
|---|---|
| Privacy Policy | https://willdodds7-arch.github.io/wildodds-gymtracker/privacy.html |
| Data deletion | https://willdodds7-arch.github.io/wildodds-gymtracker/account-deletion.html |
| Terms of Service | https://willdodds7-arch.github.io/wildodds-gymtracker/terms.html |
| Health data notice | https://willdodds7-arch.github.io/wildodds-gymtracker/health-data.html |
| Support | https://willdodds7-arch.github.io/wildodds-gymtracker/support.html |

Support email: **wildoddssupport@gmail.com**. Publisher: **Wild Odds**.

## Data Safety form

**Answer: "No, my app does not collect or share any of the required user data types."**

Why that is true, and checkable:
- The app has **no INTERNET permission** in the shipped (merged) manifest — `AndroidManifest.xml`
  removes the one a library requests, and `ReaderNoNetworkTest` fails the build if it ever comes
  back. Without it Android blocks every network call, from the app and from every library in it.
- No account, no backend, no analytics, no crash reporting, no ads, no advertising ID.
- Google ML Kit text recognition is the **bundled, on-device** model. Its library would normally send
  Google usage metrics, but with no internet permission it cannot. (If the INTERNET permission is
  ever added back, re-check developers.google.com/ml-kit/android-data-disclosure and declare it.)
- Health Connect data stays on the device.

Follow-up questions Play may show:
- **Encrypted in transit:** not applicable — nothing is transmitted.
- **Deletion:** Settings → **Delete all my data** (full wipe via `clearApplicationUserData`), or
  uninstall. No server copy exists. Web page: the Data deletion URL above.

## Health apps declaration

- Health Connect permissions declared: **READ_HEART_RATE**; optional write-back **WRITE_EXERCISE**,
  **WRITE_TOTAL_CALORIES_BURNED**. (Sleep / resting HR / HRV were removed 2026-10-05 — no feature
  used them.)
- Health/fitness data is used **only** to show the user their own training; never sold, shared or
  used for ads (Health & Fitness Data Notice).
- A Health Connect permissions-rationale activity is declared in the manifest.

## Content rating (IARC questionnaire) — draft answers

- Violence / sexual content / profanity / controlled substances / gambling: **None**.
- Users can interact or communicate: **No** (no social features).
- Shares user location: **No**. Digital purchases: **No**.
- Expected rating: **Everyone / PEGI 3**.

## Target audience & content

- **Target age: 13 and over** (stated in the Terms). Not directed at children; collects no personal
  data from anyone. Not a "Designed for Families" app.

## App access (for review)

- No sign-in: every feature is reachable without credentials. Answer "All functionality is
  available without special access".

## Store listing copy

See [`STORE_LISTING.md`](STORE_LISTING.md). Keep every claim checkable against the shipped app:
no testimonials, ratings or "proven" claims. "Nothing leaves your phone" is accurate: the app has no
internet permission.

## Keystore & signing

See `CHECKLIST.md` (step 1). The upload keystore is `wildodds-upload-2026.jks`; back it up, and store it + its
passwords as CI secrets (`keystore.properties` locally). Enrolling in Play App Signing is recommended.

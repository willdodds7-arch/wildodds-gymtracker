# Google Play — compliance worksheet

The master worksheet for the Play Console: the exact answers to give, and where each comes from.
Accurate to the current build (2026-07): accounts + sync, opt-in analytics, Health Connect (heart
rate + optional write-back), and the **Friends** social layer which is **ON by default**. The paid
Creator/Marketplace tier is **parked** behind `feat_creator_marketplace` (default OFF) and there is
no Google Play Billing / in-app purchase in this build.

> **Ordered submission runbook lives in [`PLAY_SUBMISSION.md`](PLAY_SUBMISSION.md)** — this file is
> the reference answers; that file is the step-by-step you follow.

## App identity & SDK levels

| Field | Value | Notes |
|---|---|---|
| Package / applicationId | `com.wildodds.gymtracker.offline` | the `offline` flavor suffixes `.offline` |
| compileSdk / targetSdk | 36 (Android 16) | **exceeds** Play's current minimum (targetSdk 35 / Android 15, required since Aug 2025) — re-verify at submission time in case the floor has risen again |
| minSdk | 26 (Android 8.0) | |
| versionCode scheme | monotonic integer, +1 per Play upload | currently `2`; bump before each release. Convention: keep it simple and monotonic; `versionName` is the human string (e.g. `2.1.0`) |

## Required URLs

| Purpose | URL |
|---|---|
| Privacy Policy | https://willdodds7-arch.github.io/wildodds-gymtracker/privacy.html |
| Account deletion | https://willdodds7-arch.github.io/wildodds-gymtracker/account-deletion.html |
| Terms of Service | https://willdodds7-arch.github.io/wildodds-gymtracker/terms.html |
| Health data notice | https://willdodds7-arch.github.io/wildodds-gymtracker/health-data.html |
| Support | https://willdodds7-arch.github.io/wildodds-gymtracker/support.html |

> Support email is wired to **wildoddssupport@gmail.com** across `/legal` and `/site` (flows into
> both the app and the website). Rebuild app assets so the bundled copies pick it up before submission.

## Data Safety form — datum-by-datum

Every datum the app actually collects. "Optional?" = whether the user can use the app without it.
Everything is encrypted in transit (HTTPS) and deletable (in-app + web deletion).

| Data type | Play category | Collected | Shared | Purpose | Optional? | Encrypted in transit | Deletable |
|---|---|---|---|---|---|---|---|
| Email address | Personal info → Email address | Yes (only if you create an account) | No | Account management (sign-in) | Yes — the app is fully usable with no account | Yes | Yes |
| User ID | Personal info → User IDs | Yes (only if you create an account) | No | Account management | Yes | Yes | Yes |
| Username (optional) | Personal info → Name | Yes (if set) | No | App functionality (profile) | Yes | Yes | Yes |
| Fitness/workout data (programs, sets, reps, weights, notes) | Health & fitness → Fitness info | Yes (only if you create an account — otherwise it stays on-device and isn't "collected") | No | App functionality (logging + sync) | Yes | Yes | Yes |
| Heart rate (via Health Connect) | Health & fitness → Health info | Yes (only if you connect a wearable) | No | App functionality (session summaries) | Yes | Yes (on device; not synced to our server) | Yes |
| App interactions (usage analytics) | App activity → App interactions | Yes (only if you opt in) | No | Analytics | Yes | Yes | Yes |
| Other diagnostic (app version, OS version, device class) | App info & performance → Diagnostics | Yes (only with analytics opt-in) | No | Analytics | Yes | Yes | Yes |

- **Data sold or "shared" with third parties (companies/advertisers): none.** No ad SDKs, no ad IDs.
- **Account deletion available:** Yes — in-app and via the web URL above.

### In-app social sharing — the Friends layer (ON by default)

Friends is a **user-initiated** social feature: nothing is shared until you add a specific person by
their friend code / invite link. Once you're friends, that person can see:

| What a friend can see | Source |
|---|---|
| Your username | the name you set on your profile |
| Programs you choose to share | explicit "share program" action |
| Training-session activity + achievement titles (for motivation) | published when you train, to friends only |

- Under Play's Data Safety rules this is a **user-initiated transfer the user reasonably expects**, so
  it is generally **not** third-party "sharing" — but it **does** mean the app has social interaction,
  which the **content-rating questionnaire must reflect** (see below), and which the listing discloses.
- Friend requests, unfriending, and request withdrawal are all supported; removing a friend stops all
  visibility. There is no public feed — visibility is strictly friend-to-friend.
- If you would rather launch without any social surface, set `feat_friends` default to `false` in
  `SettingsRegistry` and rebuild; then the rows above do not apply.

## Health apps declaration

- The app records resistance-training logs and, optionally, reads heart rate from **Health
  Connect** (permissions declared: READ_HEART_RATE, READ_SLEEP, READ_RESTING_HEART_RATE,
  READ_HEART_RATE_VARIABILITY; optional write-back: WRITE_EXERCISE, WRITE_TOTAL_CALORIES_BURNED).
- Health/fitness data is used **only** to provide the app to the user; it is **never** sold,
  shared, or used for ads (see the Health & Fitness Data Notice).
- A Health Connect permissions-rationale activity is declared in the manifest.

## Content rating (IARC questionnaire) — draft answers

- Violence / sexual content / profanity / controlled substances / gambling: **None**.
- **Does the app let users interact or communicate?** **Yes** — the Friends layer lets users add each
  other and share programs / training activity / motivation *(friend-to-friend only, no public feed,
  no free-text chat)*. Answer the "users can interact" / "shares user-provided content" questions
  **Yes**; there is **no** open/unmoderated messaging, so the follow-ups about unrestricted user
  communication are **No**.
- Shares user location: **No**.
- Digital purchases: **No** (the paid Creator tier is parked and, when live, is web-only — no in-app
  purchase in this build).
- Expected rating: **Everyone / PEGI 3** (a fitness utility with limited, user-initiated social
  sharing). The social-interaction answers may nudge some regional boards to a low teen rating; that
  is expected and fine.

## Target audience & content

- **Target age: 13 and over.** A 13+ age gate runs before any account is created.
- **Not a "Designed for Families" app:** it requires an account and stores personal fitness data,
  and is aimed at general (teen+/adult) users, not children — so it should not target the
  under-13 Families program.

## App access (for review)

- The main app is gated behind sign-in. Provide the reviewer a dedicated test account in the Play
  Console "App access" section (email + password). **Create this account yourself; never commit
  credentials to the repo.** If email confirmation is enforced, use a pre-confirmed account created
  from the Supabase dashboard (Auth → Users → Add user → Auto Confirm).

## Store listing copy (honest — no "no data collection" claims)

**Title:** Wild Odds Gym Tracker

**Short description (≤80 chars):**
> Plan programs, log every set, and sync your training across devices.

**Full description (draft):**
> Wild Odds Gym Tracker is a focused planner and logger for resistance training. Build or import
> programs, run them week by week, and log weight × reps (plus optional RPE and %1RM) with last
> week's numbers carried forward as editable prefill.
>
> Your account keeps everything backed up and in sync across your devices. The app works fully
> offline — log a whole session with no connection and it syncs automatically once you're back
> online.
>
> • Program builder, a curated program catalogue, and Excel/AI import
> • Fast set-logging with carry-forward, supersets, unilateral modes, rest timer, 1RM calculator
> • Program analysis: weekly set volume per muscle group, push/pull balance, insights
> • Add friends to share programs, compare achievements and send a little training motivation
> • Optional: connect a wearable (via Health Connect) to pull heart rate into your session summary
> • History, a daily habit tracker with a home-screen widget, dark mode and accent colour
> • Optional, opt-in usage statistics only — never your workout numbers, name or location
> • Export your data anytime; delete your account and all its data anytime
>
> Open source (GPLv3).

**What's new (v2.0.0):** accounts + cross-device sync, Friends, a login-first onboarding, refreshed
motion/skeleton loading, and two added programs (Aaron's 5-Day Oly Split, Ed Coen Program).

**Screenshot / feature-graphic shot list (capture on a device):**
1. Home — active program with week/day cards.
2. Session logging — set rows with carry-forward prefill.
3. Program analysis dashboard.
4. Library / program catalogue.
5. Profile — maxes + achievements.
6. Settings → Legal & privacy (shows transparency).
7. Feature graphic (1024×500): app name + a clean session-logging shot.

## Keystore & signing (you do this once)

See `docs/launch-runbook.md` for the full procedure. Summary: you generate an upload keystore,
back it up securely, and store it + its passwords as CI secrets. The app's release build signs
with it (locally via `keystore.properties`, in CI via `RELEASE_*` secrets). Enrolling in Play App
Signing is recommended (Google holds the app signing key; you keep the upload key).

## R8 / shrinking

Release builds run R8 (`isMinifyEnabled = true`) with `app/proguard-rules.pro` covering the
reflective seams (kotlinx.serialization, Gson models, ktor/supabase-kt, Credential Manager). The
signed AAB builds clean. **On-device smoke test is a release gate** (see runbook) — R8 can strip
reflectively-used code that only fails at runtime, so sign-in + a sync must be exercised on the
actual release build before promotion.

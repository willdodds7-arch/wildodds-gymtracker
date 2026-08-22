# Google Play submission — step-by-step runbook

The ordered path from "code is ready" to "live on Google Play." Reference answers (Data Safety,
content rating, listing copy) live in [`play-store.md`](play-store.md); this file is the sequence you
follow. Legend: ✅ = already done in the repo · 🔨 = needs you (account/console/device action).

> **Store = Google Play only.** This is a native Android/Kotlin app. There is no iOS build; an App
> Store release would be a separate port and is out of scope.

---

## 0. Readiness snapshot (already done ✅)

| Item | State |
|---|---|
| `applicationId` | `com.wildodds.gymtracker.offline` (permanent identity on Play) |
| `targetSdk` / `compileSdk` | 36 — exceeds Play's current floor (targetSdk 35, Aug 2025) |
| `minSdk` | 26 (Android 8.0) |
| `versionCode` / `versionName` | `2` / `2.0.0` (bump `versionCode` +1 before every upload) |
| R8 / shrinking | enabled, ProGuard rules cover reflective seams; AAB builds clean |
| Release AAB assembles | ✅ verified (`:app:bundleOfflineRelease` → `app-offline-release.aab`) |
| `SCHEDULE_EXACT_ALARM` | ✅ removed — midnight habit reset now uses an inexact alarm (no Play alarm-policy flag) |
| Hi-res icon (512×512) | ✅ `store/play/play_icon_512.png` |
| Feature graphic (1024×500) | ✅ `store/play/play_feature_graphic.png` |
| Screenshots (1080×2400 phone) | ✅ 6 captured on emulator in `store/play/screenshots/` (see Part 6) |
| Privacy policy / terms / deletion URLs | ✅ live (see `play-store.md` → Required URLs) |
| Data Safety / content rating answers | ✅ drafted in `play-store.md` (updated for Friends being ON) |
| Listing copy (title / short / full) | ✅ in `play-store.md` → Store listing copy |

**The only reason the built AAB is not yet uploadable:** it is debug-signed (there is no
`keystore.properties` on this machine). Part 2 fixes that.

---

## 1. 🔨 One-time account + signing setup

1. **Create a Google Play Developer account** — https://play.google.com/console/signup. USD $25 one
   time. **Identity verification now takes days** and, for a personal account, requires you to
   confirm a real name + address (and sometimes D-U-N-S for orgs). Start this first; everything else
   can proceed in parallel.

2. **Signing key.** A keystore already exists at repo root: `wildodds-upload.jks`.
   - If you **know its password** → use it (Part 2). 
   - If you **don't** → since the app has never been uploaded, just generate a fresh upload key (the
     old one isn't "burned" until a build is on Play):
     ```
     keytool -genkeypair -v -keystore wildodds-upload.jks -alias upload \
       -keyalg RSA -keysize 2048 -validity 10000
     ```
     Choose strong store + key passwords and **back the file up in two safe places** (a lost upload
     key = you can never update the app unless enrolled in Play App Signing — which you will be).
   - **Do not commit** the keystore or passwords (already gitignored: `*.jks`, `keystore.properties`).

3. **Enroll in Play App Signing** (done inside the console when you create the app) — Google holds the
   real app-signing key; your `wildodds-upload.jks` becomes just the *upload* key. Recommended.

---

## 2. 🔨 Produce the uploadable (signed) AAB

Two supported paths — pick one:

**A. Local build**
1. Create `keystore.properties` in the repo root (gitignored):
   ```
   storeFile=C:/Users/willd/WildOddsGymTracker-redesign-copy/wildodds-upload.jks
   storePassword=…
   keyAlias=upload
   keyPassword=…
   ```
2. Build:
   ```
   JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:bundleOfflineRelease
   ```
3. Upload `app/build/outputs/bundle/offlineRelease/app-offline-release.aab`.

**B. CI build (already wired — `.github/workflows/release.yml`)**
1. Add these GitHub repo secrets (Settings → Secrets → Actions):
   - `RELEASE_KEYSTORE_BASE64` = `base64 -w0 wildodds-upload.jks`
   - `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` (=`upload`), `RELEASE_KEY_PASSWORD`
   - (`SUPABASE_URL`, `SUPABASE_ANON_KEY` already set for CI; `GOOGLE_WEB_CLIENT_ID` only if Google
     sign-in is ever added — currently email/password only, so it can be blank.)
2. Push a version tag → the workflow builds and uploads the signed AAB as a run artifact:
   ```
   git tag v2.0.0 && git push origin v2.0.0
   ```
3. Download the `app-offline-release-aab` artifact and upload it to Play.

> ⚠️ **Release smoke test (do once per release):** install the *release* build on a real device and
> exercise sign-up → sign-in → log a set → confirm a sync. R8 can strip reflectively-used code that
> only fails at runtime. The debug APK on your Desktop is **not** a substitute (it isn't minified).

---

## 3. 🔨 Create the app + store listing in Play Console

1. **Create app**: name "Wild Odds Gym Tracker", default language English (US), app (not game), Free.
2. **Store listing** — paste from `play-store.md` → "Store listing copy":
   - App name (≤30): `Wild Odds Gym Tracker`
   - Short description (≤80): from the worksheet
   - Full description (≤4000): from the worksheet
3. **Graphics** — upload from `store/play/`:
   - App icon → `play_icon_512.png`
   - Feature graphic → `play_feature_graphic.png`
   - Phone screenshots → `store/play/screenshots/` (Part 6; min 2, 16:9 or 9:16, ≥320px)
4. **Category**: Health & Fitness. **Tags**: workout, fitness, strength. **Contact email**:
   `wildoddssupport@gmail.com`. **Privacy policy URL**: from the worksheet.

---

## 4. 🔨 Health Connect declaration (because HC is kept in v1)

The app reads/writes Health Connect, so Play requires a **Health apps declaration** *and* a
**demonstration video**. In Play Console → App content → Health, declare exactly these data types
(each is backed by real code in `RealHealthConnectGateway.kt`):

| Health Connect type | Access | Used for |
|---|---|---|
| Heart rate | Read | live/session heart-rate in the workout summary |
| Resting heart rate | Read | recovery/readiness context |
| Heart rate variability (RMSSD) | Read | recovery/readiness context |
| Sleep | Read | recovery/readiness context |
| Exercise session | Write | write completed strength sessions back (opt-in) |
| Total calories burned | Write | optional calorie estimate on written sessions |

- Purpose for every type: **App functionality** only — never sold, shared with third parties, or used
  for ads (matches the Health & Fitness Data Notice URL in the worksheet).
- **Demo video (unlisted YouTube link)** — record ~1–2 min showing: Settings → *Connect wearable
  (Health Connect)* → the HC permission grant screen → a logged session showing heart rate in the
  summary → (optional) the write-back toggle. Narrate that data stays on-device / in the user's
  account and is deletable. Paste the link into the declaration.
- The manifest already declares the matching `android.permission.health.*` permissions and a
  `HealthPermissionsRationaleActivity` (required rationale intent) — no code change needed.

> If review friction here stalls the launch, the fastest unblock is to ship v1 with Friends + logging
> only: set the wearable feature off and remove the `health.*` permissions, then add HC in a later
> update. (You chose to keep it in v1; this is the fallback if review drags.)

---

## 5. 🔨 Data Safety form

Fill from `play-store.md` → "Data Safety form — datum-by-datum" and the "In-app social sharing"
subsection. Key points: nothing sold/shared with third parties; email/user-id/fitness data collected
only with an account; analytics only on opt-in; all encrypted in transit and deletable. Disclose the
Friends user-to-user visibility as described (user-initiated).

## 6. 🔨 Screenshots  ·  content rating  ·  app access

- **Screenshots**: ✅ a captured set is in `store/play/screenshots/` (1080×2400, portrait — valid for
  Play). Recommended upload order (strongest first):
  1. `05_home_active.png` — active program (Ed Coen) with the week's day + exercises
  2. `06_session.png` — live set-logging (weight × reps, target, progression prompt)
  3. `04_catalogue.png` — curated program catalogue (Strength/Powerbuilding/Powerlifting)
  4. `01_onboarding.png` — clean login-first welcome
  (`02_home.png`, `03_library.png` are empty-state extras — optional.) Retake on a real device later
  if you want richer data / a specific device frame, but these satisfy Play's ≥2-screenshot minimum.
- **Content rating**: complete the IARC questionnaire using `play-store.md` → Content rating. Note the
  **"users can interact / share content" = Yes** (Friends), no open chat.
- **App access**: the app is gated behind sign-in for reviewers. Create a **pre-confirmed test
  account** (Supabase dashboard → Auth → Add user → Auto Confirm) and enter its email+password in
  App access. **Never commit these credentials.**

## 7. 🔨 Countries, pricing, testing → production

1. **Pricing**: Free. **Countries**: select your target markets.
2. **⚠️ Closed-testing requirement (personal accounts):** Google now requires developer accounts
   registered as an **individual** to run a **closed test with ≥12 testers for ≥14 continuous days**
   before you can promote to production. Plan for this:
   - Internal testing (instant, up to 100 testers) → verify the build.
   - Closed testing track: invite ≥12 people (email list / Google Group), keep them opted in 14 days.
   - Only then does the **Production** track unlock. (Organization accounts are exempt.)
3. Submit for review. First reviews commonly take a few days to ~2 weeks, longer with the Health
   Connect declaration.

---

## What I could not do for you (and why)

- **Sign the AAB** — requires the keystore password / creating signing credentials (yours to hold).
- **Anything inside the Play Console** — needs your developer account + login.
- **The Health Connect demo video** — needs a screen recording of the app on a device.
- **Recruit the 12 closed-test testers** — real people you invite.

Everything else — compliant manifest, clean signed-build pipeline, verified AAB, generated icon +
feature graphic, screenshots, and every listing/Data-Safety/content-rating answer — is prepared above.

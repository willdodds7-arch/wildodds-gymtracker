# Launch checklist — Google Play

Answers for every Play Console form are in [`PLAY_CONSOLE_ANSWERS.md`](PLAY_CONSOLE_ANSWERS.md);
listing text is in [`STORE_LISTING.md`](STORE_LISTING.md); images are in `graphics/` and
`screenshots/`; the file to upload is `build/app-offline-release.aab`.

## Done

- [x] Code, legal pages, website, graphics, screenshots, signed bundle.
- [x] Upload key backed up to the USB (`F:\WildOdds-SigningKey\`, 2026-10-12). Keep a second copy
      somewhere else — your password manager is ideal — because a lost key means you can't update
      the app for days while Google resets it.
- [x] App ID decided: **`com.wildodds.gymtracker`** (permanent once uploaded). The copy already on
      your phone is the old `.offline` app; the Play version installs as a separate app. To move
      your data: old app → Settings → Export data → Export as JSON; new app → Settings → Export
      data → Restore from backup.
- [x] Built-in programs carry no third-party names (renamed 2026-10-12: *12-Week Linear 6-Day
      Split*, *12-Week Linear 3x2 Split*, *5-Day Olympic Lifting Split*).

## Timeline at a glance

| When | What | Time |
|---|---|---|
| Day 1 | Create the developer account; Google verifies your identity | 30 min + 1–7 days wait |
| Day 1–2 | Set up the app, listing and forms; upload to closed testing | 1–2 hours |
| Day 2–16 | Closed test: 12+ testers opted in for 14 days in a row | 14 days |
| Day 16 | Apply for production access | 15 min + up to ~7 days review |
| After approval | Release to production; Google reviews the release | hours to a few days |

---

## Step 1 — Create the Play developer account

1. Go to <https://play.google.com/console/signup> and sign in with the Google account you want to own
   the app long-term (it can't easily be moved later).
2. Choose **Yourself** (personal account). Organisation accounts need a registered business with a
   D-U-N-S number.
3. Developer name shown on the store: **Wild Odds**. Contact email:
   `wildoddssupport@gmail.com`. Add a phone number.
4. Pay the one-off **US$25** fee.
5. Verify your identity: legal name, address and a photo ID. Approval usually takes 1–7 days.
   You can do steps 2–4 while waiting; publishing only unlocks after verification.
6. Google also asks you to verify your developer email and phone, and for a personal account, to
   confirm you have access to an Android phone (via the Play Console app). Do it from your phone.

## Step 2 — Create the app

Play Console → **Create app**:
- App name: `Wild Odds Gym Tracker`
- Default language: English (United States)
- App or game: **App** · Free or paid: **Free**
- Tick the declarations (Developer Program Policies, US export laws) → **Create app**.

## Step 3 — Fill in "Set up your app" (Dashboard → App content)

Each item opens a short form. Answers are in `PLAY_CONSOLE_ANSWERS.md`; the short version:

| Form | Answer |
|---|---|
| Privacy policy | `https://willdodds7-arch.github.io/wildodds-gymtracker/privacy.html` |
| App access | All functionality is available without any access restrictions |
| Ads | No, my app does not contain ads |
| Content rating | Fill the IARC questionnaire: category *Reference, News or Educational / Health & Fitness*; answer No to violence, sexual content, profanity, drugs, gambling, user interaction, location sharing, purchases → expect Everyone / PEGI 3 |
| Target audience | 13–15, 16–17, 18 and over. Not appealing to children: **No**, it isn't designed for children |
| News app | No |
| Data safety | "Does your app collect or share any of the required user data types?" → **No**. Encrypted in transit: not applicable. Data deletion: not applicable / no account |
| Government app | No |
| Financial features | My app doesn't provide any financial features |
| Health apps | Tick **Health Connect** (and *Fitness / activity tracking*). Permissions: heart rate (read), exercise (write), total calories burned (write). Purpose: app functionality. Not shared. Paste the video link from Step 6 |

## Step 4 — Store listing (Grow → Store presence → Main store listing)

From `STORE_LISTING.md` and the image folders:
- App name, short description, full description: copy exactly.
- App icon: `graphics/icon-512.png`.
- Feature graphic: `graphics/feature-graphic-1024x500.png`.
- Phone screenshots: all 7 from `screenshots/` (Play needs 2–8). Upload in numbered order.
- Category: **Health & Fitness**. Tags: pick the closest to workout, strength, fitness.
- Contact details: email `wildoddssupport@gmail.com`, website
  `https://willdodds7-arch.github.io/wildodds-gymtracker/`.
Save. (Store settings → App category can also be set here.)

## Step 5 — Closed test with 12+ testers for 14 days

New personal accounts must do this before they can publish to everyone.

1. **Testing → Closed testing → Create track** (or use the default "Closed testing - Alpha").
2. **Countries/regions**: add the countries your testers are in (or all).
3. **Testers**: create an email list and add 15–20 Gmail addresses (12 is the minimum that must stay
   opted in; extras cover people who drop out).
4. **Create new release** → accept **Play App Signing** when asked (Google keeps the real signing
   key; yours is the upload key) → upload `build/app-offline-release.aab` → release name
   `2.1.0 (3)` → release notes from `STORE_LISTING.md` → **Save → Review release → Start rollout**.
5. Google reviews the first release (hours to a few days). Then copy the **opt-in link** from the
   track's Testers tab and send it:

   > I'm launching my free gym tracker on Google Play and need testers for 2 weeks. Join here:
   > [opt-in link] → tap "Become a tester" → install from Play → keep it installed for 14 days.
   > Try importing a program from a screenshot and tell me what breaks.
6. Keep at least 12 testers opted in for **14 days in a row**. Check Dashboard → it shows the count
   and days remaining. Use the app yourself and push an update if testers find bugs (bump
   `versionCode` each time).

## Step 6 — Health Connect demo video (15 minutes, during the test)

Record your phone screen (1–2 minutes), upload to YouTube as **Unlisted**:
1. Settings → *Connect wearable (Health Connect)* → the permission screen (heart rate read; exercise
   and calories write).
2. Settings → *Health & fitness data notice*.
3. Log a set in a session; finish it and show heart rate in the summary.
4. The *Write workouts to Health Connect* toggle.
Paste the link into the Health apps form (Step 3).

## Step 7 — Apply for production (after 14 days)

Dashboard → **Apply for production**. Google asks short questions about the test, for example how
you recruited testers, what feedback you got and what you changed, and whether the app is ready.
Answer plainly. Review takes up to about 7 days.

## Step 8 — Release to production

**Production → Create new release** → add the same (or newer) bundle → release notes → choose
countries → **Start rollout**. A staged rollout (e.g. 20% → 100%) is optional. Google reviews the
release; once approved the app is live.

## Before every upload — 10-minute phone test

R8 shrinks the release build, which can break things the debug build hides. Install
`build/app-offline-release.apk` and check:
- [ ] Opens; start a catalogue program; log 3 sets; finish; it shows in history and charts.
- [ ] Import from a **screenshot**, a **PDF** and pasted text (Program Builder → Open a file).
- [ ] Settings → Export data, then Restore from backup.
- [ ] Rest timer plays Ding and Triple pulse.
- [ ] Health Connect connect flow (if you use a wearable).
- [ ] Settings → each legal document opens.
Then bump `versionCode` in `app/build.gradle.kts` by 1 and rebuild:
`./gradlew :app:bundleOfflineRelease`.

## Tidy-ups — not blocking

- **Supabase**: the app no longer uses it. Delete the project in the Supabase dashboard (export
  anything you want first), then delete the GitHub secrets `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
  `DATABASE_URL`, `BACKUP_PASSPHRASE`, `GOOGLE_WEB_CLIENT_ID`.
- **Old upload key**: `wildodds-upload.jks` (repo root) is unused; delete it once you're happy.
- **CI signing (optional)**: add `RELEASE_KEYSTORE_BASE64` (`base64 -w0 wildodds-upload-2026.jks`),
  `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`upload`), `RELEASE_KEY_PASSWORD` as GitHub secrets;
  pushing a `v*` tag then builds a signed bundle.
- **Old audio in git history**: the Ronnie Coleman clip is gone from the app and current source but
  still in older public commits. Ask Claude if you want history rewritten.
- **Legal review**: the Terms say they're governed by the law of wherever Wild Odds is based; a
  lawyer can name your exact jurisdiction.

# Launch checklist — the steps only you can do

The code, legal pages, website, graphics, screenshots and signed bundle are done. Everything below
needs your account, identity, phone or judgement. Answers for every Play Console form are in
[`PLAY_CONSOLE_ANSWERS.md`](PLAY_CONSOLE_ANSWERS.md); listing text is in
[`STORE_LISTING.md`](STORE_LISTING.md).

**Timeline:** the Play account identity check takes a few days, and a personal developer account
must run a **14-day closed test with at least 12 testers** before it can publish to everyone.
Do steps 1–3 today.

---

## 1. Back up the upload key — 10 minutes, first

A new upload key was made on 2026-10-11. The older `wildodds-upload.jks` has a password that isn't on
this PC, and the app has never been uploaded, so nothing depends on it.

- Key: `wildodds-upload-2026.jks` (repo root, gitignored)
- Passwords: `keystore.properties` (repo root, gitignored). Alias `upload`; store and key password are the same.
- SHA-256: `4E:FF:9F:E6:F9:62:F2:A8:F3:78:52:1D:85:4D:70:75:FA:62:68:AA:8F:45:D2:0C:47:A5:40:BA:7F:37:29:CB`

Copy **both files** to two places off this PC (password manager attachment + encrypted USB) and put
the password in your password manager. Then delete the old `wildodds-upload.jks` — or keep it and
point `keystore.properties` at it if you remember its password.

## 2. Create the Play developer account — 30 minutes + a few days' wait

1. <https://play.google.com/console/signup> — US$25 once.
2. **Personal** (your name, address, ID; the 12-tester rule applies) or **Organisation** (needs a
   D-U-N-S number for a registered business; no 12-tester rule). If Wild Odds isn't a registered
   business, choose Personal.
3. Contact email: `wildoddssupport@gmail.com`.

## 3. Two decisions before the first upload — permanent

1. **App ID.** Now `com.wildodds.gymtracker.offline`. It can never change after the first upload.
   Recommended: drop `.offline` → `com.wildodds.gymtracker` (delete the `applicationIdSuffix` line in
   `app/build.gradle.kts`, then rebuild). Cost: the copy on your phone becomes a separate app — move
   your data with Settings → Export data → back up, then restore in the new one.
2. **Permission for named programs.** The catalogue names real people in *JPShuru x V1NLAND* (two
   programs) and *Aaron's 5 Day Oly Split*. Get a written OK (a DM is fine; screenshot it):

   > Hey — I'm releasing a free, open-source gym tracker (Wild Odds Gym Tracker) on Google Play. It
   > includes a program called "JPShuru x V1NLAND" in its built-in catalogue, credited to you. No ads,
   > no purchases, nothing sold. OK to include it under that name? Happy to rename or remove it.

   No answer or a no → rename or remove them in `app/src/main/assets/blocks.json` (`name` and
   `author`) before uploading.

## 4. Create the app in Play Console — about an hour

1. **Create app**: `Wild Odds Gym Tracker`, English (US), App, Free.
2. **Play App Signing**: accept.
3. **App content** — copy from `PLAY_CONSOLE_ANSWERS.md`:
   - Privacy policy URL: `https://willdodds7-arch.github.io/wildodds-gymtracker/privacy.html`
   - App access: all functionality available without special access.
   - Ads: no.
   - Content rating: questionnaire → Everyone / PEGI 3.
   - Target audience: 13+ (13–15, 16–17, 18+). Not designed for children.
   - Data safety: **"No, my app does not collect or share any of the required user data types."**
   - Health apps declaration: Health Connect — heart rate (read), exercise and total calories
     burned (write); purpose app functionality; not shared. Needs the video in step 6.
   - Government / financial / news: no.
4. **Store listing**: text from `STORE_LISTING.md`; icon and feature graphic from `graphics/`;
   phone screenshots from `screenshots/`. Category Health & Fitness.

## 5. Closed test: 12+ testers for 14 days (personal accounts)

1. Upload `build/app-offline-release.aab` to **Testing → Closed testing** (rebuild first if you
   changed anything: `./gradlew :app:bundleOfflineRelease`, output in
   `app/build/outputs/bundle/offlineRelease/`).
2. Add **12+ testers** (recruit 15–20 to be safe). Each accepts the opt-in link, installs from Play
   and stays opted in **14 days in a row**. Message:

   > I'm launching my free gym tracker on Google Play and need testers for 2 weeks. Join here:
   > [opt-in link] → install from Play → keep it installed for 14 days. Try importing a program from
   > a screenshot and tell me what breaks.
3. After 14 days: **Apply for production** and answer Google's questions about the test.

## 6. Health Connect demo video — 15 minutes

Unlisted YouTube video, 1–2 minutes, screen-recorded on your phone:
1. Settings → *Connect wearable (Health Connect)*.
2. The Health Connect permission screen (heart rate read; exercise and calories write).
3. Settings → *Health & fitness data notice*.
4. Log a set in a session and show heart rate in the session summary.
5. The *Write workouts to Health Connect* toggle.
Paste the link into the Health apps declaration.

## 7. Before every upload — 10-minute phone test of the release build

R8 shrinks the release build, which can break things the debug build hides. Install
`build/app-offline-release.apk` and check:
- [ ] Opens; start a catalogue program; log 3 sets; finish; it appears in history and charts.
- [ ] Import from a **screenshot**, a **PDF** and pasted text (Program Builder → Open a file).
- [ ] Settings → Export data, then Restore from backup.
- [ ] Rest timer plays Ding and Triple pulse.
- [ ] Health Connect connect flow (if you use a wearable).
- [ ] Settings → each legal document opens.
Bump `versionCode` in `app/build.gradle.kts` by 1 for every upload after the first.

Already verified on an Android 15 emulator with the signed build (2026-10-11): launch, screenshot
import (real on-device OCR, no internet permission), program builder import, starting a program,
logging sets, Library and Profile.

## 8. Tidy-ups — not blocking

- **Supabase**: the app no longer uses it. Delete the project in the Supabase dashboard (export
  anything you want first) so no old account data lingers, then delete the GitHub secrets
  `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `DATABASE_URL`, `BACKUP_PASSPHRASE`, `GOOGLE_WEB_CLIENT_ID`.
  The keepalive and nightly backup workflows are already gone.
- **CI signing (optional)**: add `RELEASE_KEYSTORE_BASE64` (`base64 -w0 wildodds-upload-2026.jks`),
  `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`upload`), `RELEASE_KEY_PASSWORD`; pushing a `v*`
  tag then builds a signed bundle on GitHub.
- **Old audio in git history**: the Ronnie Coleman clip is gone from the app and current source but
  still in older public commits. Rewriting public history is disruptive; ask Claude if you want it.
- **Legal review**: the Terms are governed by "the law of wherever Wild Odds is based". A lawyer can
  name your exact jurisdiction and review both documents.

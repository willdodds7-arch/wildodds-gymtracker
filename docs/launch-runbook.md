# Launch runbook

The path from a green build to production on Google Play, with the gates that must pass at each
promotion. Do the one-time setup first, then follow the release loop.

## One-time setup (you)

### 1. Generate the upload keystore

```
keytool -genkeypair -v -keystore wildodds-upload.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

- Choose strong, distinct store and key passwords.
- **Back it up in at least two safe places.** If you lose the upload key and haven't enrolled in
  Play App Signing, you can never update the app. Do NOT commit it (it's gitignored: `*.jks`,
  `keystore.properties`).

### 2. Wire it into local + CI builds

- **Local:** create `keystore.properties` in the repo root (gitignored):
  ```
  storeFile=/absolute/path/to/wildodds-upload.jks
  storePassword=…
  keyAlias=upload
  keyPassword=…
  ```
  Then `./gradlew :app:bundleOfflineRelease` produces a signed AAB.
- **CI (release.yml, on a `v*` tag):** add repo secrets:
  - `RELEASE_KEYSTORE_BASE64` — `base64 -w0 wildodds-upload.jks` (the whole file, base64)
  - `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
  - (plus the already-set `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `GOOGLE_WEB_CLIENT_ID` once
    Google sign-in is configured)

### 3. Play Console

- Create the app, complete the Data Safety form, content rating, and target-audience sections
  using `docs/play-store.md`.
- Enrol in **Play App Signing** (recommended): upload the AAB; Google manages the app signing key,
  you keep the upload key.
- Set the Privacy Policy and account-deletion URLs (from `docs/play-store.md`).
- Create a reviewer test account (pre-confirmed) and enter it under **App access**.

### 4. Deploy the backend pieces the app needs

- Apply all `supabase/migrations/*.sql` (dashboard SQL Editor, in filename order).
- Deploy the deletion function: `npx supabase functions deploy delete-account`.
- Fix transactional email (custom SMTP) so sign-up confirmation + password reset deliver.
- Add `wildodds://auth` to Auth → URL Configuration redirect allow-list.
- Configure the Google OAuth client and set `GOOGLE_WEB_CLIENT_ID` (see `docs/backend.md`).

## Release loop

Cut a release by tagging: `git tag v2.1.0 && git push origin v2.1.0` → `release.yml` builds the
signed AAB artifact. Bump `versionCode` (+1) and `versionName` first.

Promote through tracks, with these gates at each step:

| Track | Gate before promoting |
|---|---|
| **Internal testing** | Signed AAB installs from Play on a real device; app opens. |
| → **Closed testing** | **R8 smoke test:** on the release build, sign in (email + Google), log a session, force a sync, sign out — all work. No crashes in the first sessions. |
| (closed, mandatory) | Google requires **≥12 testers opted in for ≥14 days** before a first production release. Recruit them; keep the closed test running the full window. |
| → **Production** | Crash-free sessions look healthy in the closed test. **RLS audit** (`docs/security-review.md`, Phase 8) shows no table without owner-only policies. **Link-check** on all public URLs passes (the Pages workflow does this on every deploy). All `docs/play-store.md` Console answers entered. |

## Rollback

If a production release regresses: halt the staged rollout in the Play Console, and promote the
previous known-good release. Because sync is last-write-wins on the client clock, a client-side
data bug can propagate — treat any data-corruption report as a halt-rollout trigger and
investigate before resuming.

## Manual smoke checklist (release build, on device)

- [ ] Fresh install → onboarding → age gate → create account (or sign in) → consent → username.
- [ ] Log a full session offline (airplane mode); re-enable network; confirm it syncs.
- [ ] Sign in on a second device with the same account; data appears.
- [ ] Google sign-in works (once configured).
- [ ] Settings → Legal & privacy: every doc renders.
- [ ] Settings → Account → Export my data writes a file; open it and eyeball the JSON.
- [ ] Settings → Account → Delete account (use a throwaway account) removes it; re-sign-in fails.

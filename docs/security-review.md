# Security review

**Reviewed 14 July 2026 — covers Phases 1–7.** Re-run this review whenever a table, Edge Function,
or dependency is added. The machine-checkable half lives in `supabase/audit/rls_audit.sql`.

## 1. Cross-table RLS review (as a set)

Reviewed as a set, not table-by-table: `rls_audit.sql` query 1 enumerates **every** table in
`public` and fails any that has RLS disabled or no policies — so a future table that someone
forgets to protect shows up without anyone remembering to look for it.

| Table | RLS | SELECT | INSERT | UPDATE | DELETE | Owner predicate |
|---|---|---|---|---|---|---|
| `profiles` | on | own | own | own | own | `auth.uid() = id` |
| `sync_rows` | on | own | own | own | own | `auth.uid() = user_id` |
| `analytics_events` | on | **none (deliberate)** | own | **none** | **none** | `auth.uid() = user_id` (insert only) |

**`analytics_events` has no SELECT policy on purpose.** Clients may write their own events and can
never read any — not even their own. RLS denies what no policy allows, so a stolen anon key still
reads nothing. Consequence, stated honestly: the in-app data export can't include analytics rows;
the Privacy Policy and export file both say so and point at the request path instead.

**Verification status:** all three tables' owner-only isolation is proven by live tests against the
real project (`ProfilesRlsIntegrationTest`, `SyncRlsIntegrationTest`,
`AnalyticsRlsIntegrationTest`) — cross-user reads return empty and cross-user writes fail. They are
`@Ignore`d by default (live network); re-enable to re-verify.

**Residual risks accepted:**
- The anon key ships in the APK by design. It is not a secret; RLS is the control. Anyone can
  extract it and hit the API *as an anonymous or their own user* — which is what the policies
  assume.
- `sync_rows` stores training payloads as JSONB. Row isolation is enforced, but the server can
  read the plaintext (no end-to-end encryption). The Privacy Policy says so.
- Last-write-wins sync means a malicious *own* client can clobber its own data. Not a
  cross-tenant issue.

## 2. Edge Functions

`delete-account` is the only function.

- **Authorisation:** derives the uid from the caller's own JWT via `auth.getUser(jwt)`. The uid is
  never read from the request body, so a caller cannot delete anyone else.
- **Service role:** used only inside the function (injected by the platform env); never shipped to
  clients. Verified: no `sb_secret_` / service-role string appears anywhere in the repo or app.
- **Rate limit:** per-uid, 3 calls/minute, in-memory. **Honest limitation:** this is per-instance
  and resets on cold start — a backstop against accidental double-taps, *not* a security control.
  It doesn't need to be one: the operation is idempotent and self-scoped (worst case you delete
  your own already-deleted account).
- **CORS:** `*` is acceptable here because authorisation is the bearer token, not the origin.

## 3. Auth abuse settings (dashboard — verify these are set)

| Setting | Target | Where |
|---|---|---|
| Sign-up / sign-in rate limit | 100 per 5 min per IP (raised from 30) | Auth → Rate Limits |
| Email send rate limit | governed by the custom SMTP provider (Resend free: 100/day) | Auth → Rate Limits / SMTP |
| Minimum password length | **raise 6 → 8 before launch** | Auth → Sign In / Providers → Email |
| Leaked-password protection | Pro-plan only — unavailable on free | Auth → Email |
| CAPTCHA (hCaptcha/Turnstile) | available on free tier — **enable before public launch** | Auth → Attack Protection |
| Confirm email | must be enforced + actually delivering before launch | Auth → Providers |

Two of these are **open actions**, not done: raise the minimum password length, and turn on CAPTCHA
before the app is public. The app's own sign-up form currently enforces ≥6 characters
(`OnboardingScreens.kt`) — raise both together so the client matches the server.

## 4. Secrets hygiene

- Only the anon/publishable key reaches `BuildConfig` (from gitignored `local.properties` or CI
  secrets). Verified by `SupabaseModuleTest`, which asserts the key does **not** start with
  `sb_secret_`.
- Keystore + passwords: gitignored (`*.jks`, `keystore.properties`), CI-secret-injected.
- No tokens or PII are logged. The analytics taxonomy has a unit test banning free text, health
  values, names/emails, and location from event properties.
- **Outstanding:** the `sb_secret_` service-role key was pasted into a chat transcript on
  2026-07-06 and should be rotated (Project Settings → API Keys) if that hasn't been done.

## 5. Dependency & licence audit

Refreshed from the generated report (`legal/open-source-licenses.md`, 203 components, produced by
the jaredsburrows Gradle plugin from the resolved graph).

| Component | Licence | GPLv3-compatible? |
|---|---|---|
| supabase-kt (supabase/gotrue/postgrest/functions 2.5.4) | MIT | Yes |
| ktor 2.3.12 | Apache-2.0 | Yes |
| androidx.credentials 1.2.2 | Apache-2.0 | Yes |
| AndroidX / Compose / Room / kotlinx (~168 artifacts) | Apache-2.0 | Yes |
| Bouncy Castle (3 artifacts) | Bouncy Castle Licence (MIT-style) | Yes |
| 1 artifact | BSD-3-Clause | Yes |

**Finding worth flagging:** 8 artifacts — including
`com.google.android.libraries.identity.googleid` (used for Google sign-in) and the Play Services
transitives — are under the **Android Software Development Kit License**, which is *proprietary*,
not open source. Shipping proprietary Google SDKs inside a GPLv3 app is the well-trodden pattern
for Play-distributed apps (usually reasoned through the GPL's system-library exception and the
fact these are Google-platform components), but it is a genuine legal nuance rather than a clean
"all dependencies are GPLv3-compatible" claim. **Flag it to the lawyer reviewing `/legal`.** If it
proves unacceptable, the mitigation is to drop Google sign-in and ship email/password only — the
Google button already hides itself when unconfigured, so that's a config change, not a rewrite.

## 6. Backups

Nightly schema dump + weekly encrypted data snapshot run in `backup.yml`, with a restore drill
that actually restores the dump into a throwaway Postgres in CI (see §7 and the workflow). This
proves the artifact is restorable rather than assuming it.

## 7. Open actions

1. Rotate the service-role key (§4).
2. Raise minimum password length to 8 (server + client) (§3).
3. Enable CAPTCHA / attack protection before public launch (§3).
4. Fix confirmation-email delivery (blocks sign-up end-to-end today).
5. Add `DATABASE_URL` + `BACKUP_PASSPHRASE` CI secrets so `backup.yml` can run (§6).
6. Run `rls_audit.sql` in the dashboard and confirm query 1 returns zero rows and query 3 shows the
   18-month analytics purge job as `active`.
7. Legal review of `/legal` by a qualified person, including the licence nuance in §5.

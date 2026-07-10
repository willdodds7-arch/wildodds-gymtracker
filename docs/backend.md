# Backend — Supabase setup & operations

Last updated: 2026-07-11 (Phase 1). The app is online-first as of v2: required account,
Supabase backend (Auth + Postgres/RLS + Edge Functions), offline-first sync on top of Room.

## Project facts

| Item | Value |
|---|---|
| Provider | Supabase (free tier) |
| Project URL | `https://rvacjiqncoyttovyebud.supabase.co` |
| Region | ap-south-1 (Mumbai) |
| Dashboard | https://supabase.com/dashboard (owner: willdodds7@gmail.com) |
| Client library | supabase-kt **2.5.4** (last 2.x line — 3.x requires Kotlin 2.x; this app is on Kotlin 1.9.24. Bump together.) |
| Installed plugins | Auth (`gotrue-kt`), Postgrest, Functions |
| Client entry point | `data/backend/SupabaseModule.kt` (lazy singleton, no DI framework) |
| Error handling | `data/backend/RemoteResult.kt` — every backend call site returns `RemoteResult`, checks `RemoteError.Offline` first |
| Connectivity | `data/backend/NetworkMonitor.kt` (validated-internet Flow + `isOnWifi()` for the future Wi-Fi-only sync toggle) |

## Keys & secrets

- **Anon / publishable key** (`sb_publishable_…`): ships in the app via `BuildConfig`. This is
  by design public; every table's RLS policy is the real access control.
  - Local dev: `local.properties` → `supabase.url` / `supabase.anonKey` (gitignored).
  - CI: repo Actions secrets `SUPABASE_URL` / `SUPABASE_ANON_KEY` (read as env-var fallback in
    `app/build.gradle.kts`).
- **Secret / service_role key** (`sb_secret_…`): bypasses RLS. Only ever used inside Edge
  Functions (Supabase injects it there) or, later, dedicated CI jobs. **Never** in app code,
  `BuildConfig`, or this repo. It was pasted into a chat once on 2026-07-06 — **rotate it**
  (Project Settings → API Keys → rotate) if that hasn't been done yet.
- RLS integration-test accounts: two pre-confirmed throwaway users, credentials in
  `local.properties` (`supabase.rlsTest*`), consumed only by `ProfilesRlsIntegrationTest`.

## Migrations

SQL migrations live in `/supabase/migrations/`, named `YYYYMMDDNNNNNN_description.sql`, written
idempotently (`create table if not exists`, `drop policy if exists` before `create policy` —
Postgres has no `CREATE POLICY IF NOT EXISTS`).

**Applying them:** this dev machine has no Docker, so there is no local `supabase start` stack
and no linked CLI. Current process is manual: open **SQL Editor** in the dashboard, paste the
migration, run, confirm "Success". Apply strictly in filename order. (If Docker ever becomes
available: `npx supabase link --project-ref rvacjiqncoyttovyebud` then `npx supabase db push`.)

Rules for every new table (non-negotiable, from the v2 spec):
1. `alter table … enable row level security;` in the same migration that creates it.
2. Owner-only policies for each of select/insert/update/delete (`auth.uid() = user_id`).
3. A test proving cross-user access fails (see `ProfilesRlsIntegrationTest` as the template).

## Auth configuration (dashboard state, as of 2026-07-11)

- Email/password provider: **enabled**. Minimum password length 6 (raise to 8+ before launch).
- Email confirmation: effectively **required** — sign-ups trigger a confirmation email.
- Custom SMTP: **enabled** — Resend (`smtp.resend.com`, sender `onboarding@resend.dev`,
  username `resend`, password = Resend API key). Free tier: 100 emails/day, 3,000/month.
  - **Known issue:** confirmation sends were still failing ("Error sending confirmation email")
    after SMTP setup on ports 465 and 587. Root cause not yet found (check Resend dashboard →
    Logs, and Supabase Auth logs). Must be fixed before Phase 2 ships real sign-up.
  - Built-in fallback sender is hard-capped at **2 emails/hour** — never rely on it.
- Rate limit for sign-ups/sign-ins: raised 30 → 100 per 5 min per IP.
- Test users (auto-confirmed, created via Auth → Users → Add user): `backupdodds2@gmail.com`,
  `lemon.rust6@gmail.com`, `willdoddsu@gmail.com`. Throwaway; delete before launch.
- Google sign-in: **not configured yet** (Phase 2 — needs a Google Cloud OAuth client; steps
  will be documented here when built).

## CI (GitHub Actions)

- `.github/workflows/ci.yml` — build + full unit-test suite on every push/PR to `main`.
  Uses the two repo secrets; no `local.properties` needed on runners.
- `.github/workflows/supabase-keepalive.yml` — every 3 days, an authenticated REST query
  against `profiles` (returns `[]` under RLS but counts as activity) so the free project never
  auto-pauses (~7 idle days triggers a pause).
  - GitHub disables **scheduled** workflows after ~60 days without repo activity. If the repo
    goes dormant, either push something occasionally or re-enable from the Actions tab.

## Dashboard usage alerts — set these up (manual, ~2 min)

Supabase emails the project owner as limits approach, but confirm under
**Project Settings → Usage** (or org **Billing → Usage**) that email notifications are on.
Check the usage page monthly. There is no free-tier overage billing — services degrade/stop
instead, which for this app means sync fails (local Room keeps working by design).

## Free-tier ceilings & upgrade triggers

Free-tier limits (verify current numbers at supabase.com/pricing — they shift):

| Resource | Free limit | At ~80% | Action |
|---|---|---|---|
| Database size | 500 MB | 400 MB | Purge analytics older than retention window; then consider Pro |
| Monthly active users (Auth) | 50,000 | 40,000 | Consider Pro ($25/mo) — at this scale you have real traction |
| Egress / bandwidth | 5 GB/mo | 4 GB/mo | Check sync batch sizes for waste; then consider Pro |
| Edge Function invocations | 500K/mo | 400K/mo | Audit for runaway retries; then consider Pro |
| Storage | 1 GB | 800 MB | (Unused so far — no file storage features) |
| Project pausing | pauses after ~7 idle days | n/a | Keep-alive workflow handles this |
| Resend (email) | 100/day, 3,000/mo | 80/day | Verify a custom domain on Resend / raise plan |

**The trigger rule:** when any row hits ~80% sustained (not a one-day spike), plan the Pro
upgrade ($25/mo) *before* hitting 100% — the free tier fails by stopping service, not by billing.

## Environment quirks (this dev machine)

- Build with `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`; flavor is `offline`
  (`:app:assembleOfflineDebug`, `:app:testOfflineDebugUnitTest`).
- No Docker → no local Supabase stack; integration tests hit the live project and are
  `@Ignore`d by default. Run deliberately: remove `@Ignore` locally, run the single class,
  restore it.
- supabase-kt Auth in JVM/Robolectric tests: default session storage needs a real Android
  context. Use `MemorySessionManager()` + `MemoryCodeVerifierCache()` and
  `Dispatchers.setMain(UnconfinedTestDispatcher())` (see `SupabaseModuleTest`).

# Analytics ("Diagnostics")

First-party, consent-gated product analytics in a table we own — **no third-party SDK**. Purpose:
understand which screens/features get used to guide what gets built next. It is deliberately
minimal and privacy-preserving.

## Hard rules (enforced, not aspirational)

- **Consent-gated.** Events are recorded only when the user has explicitly opted in
  (onboarding consent screen, or Settings → "Share usage statistics"). Pre-consent and after
  opt-out, events are **dropped entirely — never queued** (so nothing leaks on a later opt-in).
  Withdrawing consent purges anything still queued. Enforced by `AnalyticsGate.persistIfConsented`
  and asserted in `AnalyticsGateConsentTest`.
- **No PII, by construction.** Every property value is a coarse, code-defined enum/bucket/flag.
  No free text, no health/workout numbers, no names/emails, no precise location. The taxonomy is
  a sealed class (`AnalyticsEvent`) — the only loggable events — and `AnalyticsTaxonomyTest`
  scans every case's properties for anything PII-shaped (emails, long digit runs, decimals,
  whitespace, coordinates) and fails if found.
- **Write-only from the device.** RLS lets a client INSERT its own rows and grants **no** SELECT
  policy, so clients can never read analytics back (`AnalyticsRlsIntegrationTest`, live).
- **Anonymised session id.** A fresh random UUID per app run, not linked to the account or
  persisted across runs.

## Event taxonomy

| Event | Properties (all coarse) |
|---|---|
| `app_open` | — |
| `onboarding_step` | `step` (fixed step id, e.g. `age_gate`, `consent`) |
| `workout_started` | — |
| `workout_completed` | `exercise_count_bucket` (`1-3`/`4-6`/`7-9`/`10+` — never the raw count) |
| `program_created` | `source` (`builder`/`import`/`catalogue`/`ai`) |
| `feature_toggled` | `feature` (SettingsRegistry flag key), `enabled` (`true`/`false`) |
| `screen_view` | `screen` (fixed route name from `Screens`) |
| `sync_completed` | `outcome` (`success`/`failure`) |
| `settings_search` | `had_results` (`true`/`false` — never the query text) |

Adding an event = a new `AnalyticsEvent` case (the taxonomy test then polices its properties) +
a call site. No server change (the `properties` column is generic jsonb).

## Storage & pipeline

- **On device:** `analytics_outbox` Room table (local-only, never synced). `AnalyticsGate.log`
  is fire-and-forget: consent check → outbox insert → nudge the uploader. A 24-hour+ backstop
  purge caps a long-offline outbox.
- **Upload:** `AnalyticsWorker` (WorkManager, connected-network) drains the outbox in batches via
  `SupabaseAnalyticsUploader` → `analytics_events`. Re-checks consent at drain time (clears the
  outbox if revoked). Retries with backoff on failure, capped; rows are kept until a batch
  actually succeeds (`AnalyticsOutboxTest`).
- **Server:** `analytics_events` (id, user_id, session_id, event_name, screen, properties jsonb,
  app_version, os_version, device_class, created_at). RLS insert-own-only, no select.

## Retention

**18 months.** A `pg_cron` job (`analytics_retention_purge`, daily 04:17 UTC) deletes rows older
than 18 months. Enable the `pg_cron` extension once from the dashboard (Database → Extensions)
if the migration's `create extension` didn't (some projects require enabling it in the UI first).

## Consent auditability

The consent state is mirrored onto `profiles.analytics_consent` when the user accepts/declines
(best-effort; the on-device value is authoritative for gating). This gives a server-side audit
trail of who consented.

## Querying

Analytics can only be read with elevated (dashboard/service-role) access. Ready-made queries —
DAU/WAU, onboarding funnel, feature adoption — are in `docs/analytics-queries.sql`.

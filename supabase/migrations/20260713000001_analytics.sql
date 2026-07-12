-- Phase 4: analytics ("diagnostics") pipeline.
--
-- Consent-gated, PII-free product analytics in a table WE own (no third-party SDK). Clients may
-- only INSERT their own rows and can NEVER read the table back — analytics is write-only from the
-- device's perspective; querying is done from the dashboard/service role.

create table if not exists public.analytics_events (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  session_id text not null,            -- anonymised per-app-run id, not linkable across runs
  event_name text not null,
  screen text,
  properties jsonb not null default '{}'::jsonb,  -- coarse code-defined vocab only; no PII
  app_version text not null,
  os_version text not null,
  device_class text not null,          -- 'phone' | 'tablet'
  created_at timestamptz not null default now()
);

create index if not exists analytics_events_user_created on public.analytics_events (user_id, created_at);
create index if not exists analytics_events_name_created on public.analytics_events (event_name, created_at);

alter table public.analytics_events enable row level security;

-- INSERT own rows only. Note the DELIBERATE absence of any SELECT/UPDATE/DELETE policy: with RLS
-- on and no policy for those verbs, the anon/authenticated roles are denied them entirely. A
-- client literally cannot read analytics back — proven by Sync... err, AnalyticsRlsIntegrationTest.
drop policy if exists "analytics_insert_own" on public.analytics_events;
create policy "analytics_insert_own" on public.analytics_events
  for insert with check (auth.uid() = user_id);

-- Consent state on the profile, for auditability (Phase 4 requirement). Mirrors the on-device
-- ThemePreferences analytics_consent; the app updates it when the user accepts/declines.
alter table public.profiles add column if not exists analytics_consent text not null default 'unset';

-- 18-month retention purge via pg_cron. pg_cron is available on Supabase (enable the extension
-- once from the dashboard: Database → Extensions → pg_cron). Idempotent (re)schedule.
create extension if not exists pg_cron;

do $$
begin
  perform cron.unschedule('analytics_retention_purge');
exception when others then
  null; -- not scheduled yet
end $$;

select cron.schedule(
  'analytics_retention_purge',
  '17 4 * * *',  -- daily 04:17 UTC
  $$delete from public.analytics_events where created_at < now() - interval '18 months'$$
);

-- Analytics queries for Wild Odds Gym Tracker (Phase 4).
-- Run these in the Supabase SQL Editor (service-role context) — clients cannot read analytics.
-- All rely only on the PII-free analytics_events table; none can expose an individual's training.

-- ── DAU / WAU / MAU ──────────────────────────────────────────────────────────
-- Distinct active users per day (last 30 days).
select date_trunc('day', created_at)::date as day,
       count(distinct user_id)              as dau
from public.analytics_events
where created_at >= now() - interval '30 days'
group by 1
order by 1;

-- Rolling 7-day (WAU) and 30-day (MAU) active users as of today.
select
  (select count(distinct user_id) from public.analytics_events where created_at >= now() - interval '7 days')  as wau,
  (select count(distinct user_id) from public.analytics_events where created_at >= now() - interval '30 days') as mau;

-- Stickiness (DAU/MAU) — a rough "how often do actives come back" ratio.
with d as (select count(distinct user_id) n from public.analytics_events where created_at >= now() - interval '1 day'),
     m as (select count(distinct user_id) n from public.analytics_events where created_at >= now() - interval '30 days')
select round(100.0 * d.n / nullif(m.n, 0), 1) as dau_mau_pct from d, m;

-- ── Onboarding funnel ────────────────────────────────────────────────────────
-- How many distinct users reached each onboarding step (order matches the flow).
select properties->>'step' as step,
       count(distinct user_id) as users
from public.analytics_events
where event_name = 'onboarding_step'
group by 1
order by array_position(
  array['age_gate','consent','username','backup'], properties->>'step'
);

-- Step-to-step conversion: fraction of age-gate users who reached each later step.
with base as (
  select count(distinct user_id) n
  from public.analytics_events
  where event_name = 'onboarding_step' and properties->>'step' = 'age_gate'
)
select e.properties->>'step' as step,
       count(distinct e.user_id) as users,
       round(100.0 * count(distinct e.user_id) / nullif((select n from base), 0), 1) as pct_of_age_gate
from public.analytics_events e
where e.event_name = 'onboarding_step'
group by 1
order by 3 desc;

-- ── Feature adoption ─────────────────────────────────────────────────────────
-- Which features get toggled on, and by how many distinct users (last 90 days).
select properties->>'feature' as feature,
       count(*) filter (where properties->>'enabled' = 'true')  as turned_on,
       count(*) filter (where properties->>'enabled' = 'false') as turned_off,
       count(distinct user_id) as distinct_users
from public.analytics_events
where event_name = 'feature_toggled'
  and created_at >= now() - interval '90 days'
group by 1
order by distinct_users desc;

-- Screen popularity (views per screen, distinct users).
select screen,
       count(*) as views,
       count(distinct user_id) as distinct_users
from public.analytics_events
where event_name = 'screen_view'
group by 1
order by views desc;

-- Workout engagement: started vs completed, and the size distribution of completed sessions.
select
  count(*) filter (where event_name = 'workout_started')   as started,
  count(*) filter (where event_name = 'workout_completed') as completed;

select properties->>'exercise_count_bucket' as size_bucket,
       count(*) as completed_workouts
from public.analytics_events
where event_name = 'workout_completed'
group by 1
order by 1;

-- Sync health: success vs failure rate (last 7 days).
select properties->>'outcome' as outcome, count(*)
from public.analytics_events
where event_name = 'sync_completed' and created_at >= now() - interval '7 days'
group by 1;

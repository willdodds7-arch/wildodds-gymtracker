-- RLS audit (Phase 8). Run in the Supabase SQL Editor after ANY migration that adds a table.
-- It is a SET-level check: it looks at every table in `public`, not just the ones you remembered.
--
-- PASS = query 1 returns zero rows. Query 2 is the human-readable policy inventory.

-- 1) FAIL LIST: any public table without row-level security, or with RLS on but NO policies at all
--    (which silently denies everyone and is almost always a mistake).
select
  c.relname as table_name,
  c.relrowsecurity as rls_enabled,
  count(p.polname) as policy_count,
  case
    when not c.relrowsecurity then 'FAIL: RLS DISABLED — table is world-readable to any anon key'
    when count(p.polname) = 0 then 'CHECK: RLS on but no policies — denies all; intentional?'
  end as verdict
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
left join pg_policy p on p.polrelid = c.oid
where n.nspname = 'public'
  and c.relkind = 'r'
group by c.relname, c.relrowsecurity
having not c.relrowsecurity or count(p.polname) = 0
order by c.relname;

-- 2) INVENTORY: every policy on every public table, with its USING / WITH CHECK expressions.
--    Eyeball that every expression is owner-scoped (auth.uid() = user_id / = id) and that no
--    policy is `true` / unscoped.
select
  c.relname as table_name,
  p.polname as policy,
  case p.polcmd
    when 'r' then 'SELECT' when 'a' then 'INSERT' when 'w' then 'UPDATE'
    when 'd' then 'DELETE' when '*' then 'ALL' end as command,
  pg_get_expr(p.polqual, p.polrelid) as using_expr,
  pg_get_expr(p.polwithcheck, p.polrelid) as with_check_expr
from pg_policy p
join pg_class c on c.oid = p.polrelid
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
order by c.relname, command;

-- 3) Confirm the analytics retention job is actually scheduled (Phase 4 / Phase 8).
select jobid, schedule, command, active from cron.job;

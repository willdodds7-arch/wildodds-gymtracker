-- Phase 3: offline-first sync storage.
--
-- ONE generic table rather than seven typed mirrors: rows are keyed by
-- (user_id, entity_type, sync_id) and carry the entity as jsonb `payload`. The server never
-- inspects payloads (it's a sync/backup store, not a query API), additive Room migrations then
-- need no server migration, and there is exactly one RLS surface to secure and test.
-- Running/tracking data is excluded by construction — the app's sync engine only ever writes the
-- seven lifting-graph entity types.
--
-- Conflict resolution is LAST-WRITE-WINS on the client-generated `updated_at` (epoch millis).
-- Documented trade-offs for a small personal-training app:
--   * device clock skew can pick the "wrong" winner between two nearly-simultaneous edits;
--   * an update newer than a delete wins (rows can resurrect) and vice versa;
-- both are acceptable here — one user, occasional multi-device use, no collaborative editing.

create sequence if not exists public.sync_rows_seq;

create table if not exists public.sync_rows (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  entity_type text not null,
  sync_id text not null,
  updated_at bigint not null,          -- client epoch ms; the LWW key
  deleted_at bigint,                   -- soft delete (tombstone) marker, epoch ms
  payload jsonb not null default '{}'::jsonb,
  -- Monotonic pull cursor: bumped on every insert AND every conflict-update, so
  -- "give me everything with seq > my last cursor" always sees re-pushed rows.
  seq bigint not null default nextval('public.sync_rows_seq'),
  primary key (user_id, entity_type, sync_id)
);

create index if not exists sync_rows_user_seq on public.sync_rows (user_id, seq);

alter table public.sync_rows enable row level security;

drop policy if exists "sync_rows_select_own" on public.sync_rows;
create policy "sync_rows_select_own" on public.sync_rows
  for select using (auth.uid() = user_id);

drop policy if exists "sync_rows_insert_own" on public.sync_rows;
create policy "sync_rows_insert_own" on public.sync_rows
  for insert with check (auth.uid() = user_id);

drop policy if exists "sync_rows_update_own" on public.sync_rows;
create policy "sync_rows_update_own" on public.sync_rows
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "sync_rows_delete_own" on public.sync_rows;
create policy "sync_rows_delete_own" on public.sync_rows
  for delete using (auth.uid() = user_id);

-- Batched conditional upsert: a pushed row only replaces the stored one when strictly newer
-- (server-side LWW). SECURITY INVOKER: runs as the calling user, so RLS still applies — a
-- client can only ever write its own rows, and the user_id always comes from auth.uid().
create or replace function public.sync_push(rows jsonb)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
  r jsonb;
begin
  for r in select * from jsonb_array_elements(rows)
  loop
    insert into public.sync_rows (user_id, entity_type, sync_id, updated_at, deleted_at, payload)
    values (
      auth.uid(),
      r->>'entity_type',
      r->>'sync_id',
      (r->>'updated_at')::bigint,
      (r->>'deleted_at')::bigint,   -- null when absent
      coalesce(r->'payload', '{}'::jsonb)
    )
    on conflict (user_id, entity_type, sync_id) do update
      set updated_at = excluded.updated_at,
          deleted_at = excluded.deleted_at,
          payload    = excluded.payload,
          seq        = nextval('public.sync_rows_seq')
      where sync_rows.updated_at < excluded.updated_at;
  end loop;
end;
$$;

-- Phase 1: profiles table — one row per auth.users row, owner-only RLS.
-- This is the first backend table for the v2 online-first migration. Every table added after
-- this one must follow the same pattern: RLS enabled, owner-only policies, no exceptions.

create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  username text,
  created_at timestamptz not null default now()
);

alter table public.profiles enable row level security;

-- Owner-only: a user can only see/change their own profile row. No policy at all = no access,
-- so each operation (select/insert/update/delete) needs its own explicit policy.
create policy "profiles_select_own" on public.profiles
  for select using (auth.uid() = id);

create policy "profiles_insert_own" on public.profiles
  for insert with check (auth.uid() = id);

create policy "profiles_update_own" on public.profiles
  for update using (auth.uid() = id) with check (auth.uid() = id);

create policy "profiles_delete_own" on public.profiles
  for delete using (auth.uid() = id);

-- Insert-on-signup: every new auth.users row gets a matching profiles row automatically, so the
-- app never has to remember to create one after sign-up (and RLS on profiles never blocks the
-- very first row, since this runs as the trigger owner, not the new user).
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, username)
  values (new.id, null);
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- trips: one row per 대리운전 trip, owned by the app's anonymous user.
-- The app generates id and share_token locally (so the share link works before
-- the first upload); the defaults below are a server-side fallback.

create table public.trips (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- >= 128-bit random, url-safe base64 (22+ chars). Default: 18 random bytes -> 24 chars.
  share_token text not null unique
    default translate(encode(extensions.gen_random_bytes(18), 'base64'), '+/', '-_')
    check (share_token ~ '^[A-Za-z0-9_-]{22,}$'),
  status text not null default 'active'
    check (status in ('active', 'arriving', 'safe_arrival_check', 'ended')),
  destination_label text check (char_length(destination_label) <= 200),
  contact_label text check (char_length(contact_label) <= 100),
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  last_seen_at timestamptz not null default now()
);

comment on table public.trips is 'One 대리운전 trip. Owner = anonymous app user; contacts read via trip_viewers.';

create index trips_owner_idx on public.trips (owner);

alter table public.trips enable row level security;

-- Least privilege: no anon access at all; authenticated (incl. anonymous sign-ins)
-- may only touch the columns below, still subject to RLS.
revoke all on table public.trips from anon, authenticated;
grant select on table public.trips to authenticated;
grant insert (id, owner, share_token, status, destination_label, contact_label, started_at, ended_at, last_seen_at)
  on table public.trips to authenticated;
grant update (status, destination_label, contact_label, ended_at, last_seen_at)
  on table public.trips to authenticated;

create policy "owner selects own trips" on public.trips
  for select to authenticated
  using ((select auth.uid()) = owner);

create policy "owner inserts own trips" on public.trips
  for insert to authenticated
  with check ((select auth.uid()) = owner);

create policy "owner updates own trips" on public.trips
  for update to authenticated
  using ((select auth.uid()) = owner)
  with check ((select auth.uid()) = owner);

-- No delete policy: trips are kept for the journey report.

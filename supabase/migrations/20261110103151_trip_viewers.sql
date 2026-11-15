-- trip_viewers: trusted contacts who opened the share link (joined via join_trip).
-- Rows are only ever written by public.join_trip (security definer); clients get no insert grant.

create table public.trip_viewers (
  trip_id uuid not null references public.trips (id) on delete cascade,
  viewer uuid not null references auth.users (id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (trip_id, viewer)
);

comment on table public.trip_viewers is 'Anonymous users who joined a trip with its share token.';

create index trip_viewers_viewer_idx on public.trip_viewers (viewer);

alter table public.trip_viewers enable row level security;

revoke all on table public.trip_viewers from anon, authenticated;
grant select on table public.trip_viewers to authenticated;

-- Access helpers live in a schema the Data API does not expose. They are security
-- definer so RLS policies on trips / trip_viewers / trip_events can reference each
-- other without recursive policy evaluation. Each only answers a yes/no question
-- about the calling user.
create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to authenticated;

create function private.is_trip_owner(p_trip_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.trips t
    where t.id = p_trip_id and t.owner = (select auth.uid())
  );
$$;

create function private.is_trip_viewer(p_trip_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.trip_viewers v
    where v.trip_id = p_trip_id and v.viewer = (select auth.uid())
  );
$$;

revoke all on function private.is_trip_owner(uuid) from public;
revoke all on function private.is_trip_viewer(uuid) from public;
grant execute on function private.is_trip_owner(uuid) to authenticated;
grant execute on function private.is_trip_viewer(uuid) to authenticated;

create policy "viewer sees own memberships, owner sees viewers of own trip" on public.trip_viewers
  for select to authenticated
  using (viewer = (select auth.uid()) or (select private.is_trip_owner(trip_id)));

-- Viewers may read the trip they joined. Replaces the owner-only select policy from the
-- trips migration with one combined policy (lives here because it depends on this table).
drop policy "owner selects own trips" on public.trips;
create policy "owner and viewers select trips" on public.trips
  for select to authenticated
  using ((select auth.uid()) = owner or (select private.is_trip_viewer(id)));

-- trip_events: append-only event log uploaded by the app (mirror of the Room event log).
-- Idempotent upload: insert ... on conflict (trip_id, seq) do nothing.

create table public.trip_events (
  id bigint generated always as identity primary key,
  trip_id uuid not null references public.trips (id) on delete cascade,
  seq integer not null check (seq >= 0),
  type text not null check (type in (
    'TRIP_STARTED', 'ROUTE_DEVIATION', 'DEVIATION_CLEARED', 'LONG_STOP', 'LONG_STOP_CLEARED',
    'SOS', 'SOS_CANCELLED', 'MONITORING_INTERRUPTED', 'MONITORING_RESUMED',
    'NEAR_DESTINATION', 'ARRIVED', 'SAFE_ARRIVAL_CHECK_OPENED', 'SAFE_ARRIVAL_CONFIRMED',
    'CHECK_IN_PROMPTED', 'CHECK_IN_ANSWERED', 'TRIP_ENDED'
  )),
  occurred_at timestamptz not null,
  lat double precision check (lat between -90 and 90),
  lng double precision check (lng between -180 and 180),
  reason text check (char_length(reason) <= 500),
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  unique (trip_id, seq)
);

comment on table public.trip_events is 'Append-only trip events; type = TripEvent wire name (SCREAMING_SNAKE).';

alter table public.trip_events enable row level security;

revoke all on table public.trip_events from anon, authenticated;
grant select on table public.trip_events to authenticated;
grant insert (trip_id, seq, type, occurred_at, lat, lng, reason, payload)
  on table public.trip_events to authenticated;

create policy "owner and viewers select events" on public.trip_events
  for select to authenticated
  using ((select private.is_trip_owner(trip_id)) or (select private.is_trip_viewer(trip_id)));

create policy "owner inserts events" on public.trip_events
  for insert to authenticated
  with check ((select private.is_trip_owner(trip_id)));

-- No update/delete policies or grants: events are append-only (SOS_CANCELLED never deletes SOS).

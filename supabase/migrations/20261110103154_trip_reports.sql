-- trip_reports: AI summary written only by the summarize-trip Edge Function (service role).
-- Clients can read (owner + viewers) but never write, so a summary can't be forged.

create table public.trip_reports (
  trip_id uuid primary key references public.trips (id) on delete cascade,
  summary jsonb not null,
  model text not null,
  created_at timestamptz not null default now()
);

comment on table public.trip_reports is 'AI 해석 for a finished trip: {summary, notable_events[], caveats[]}.';

alter table public.trip_reports enable row level security;

revoke all on table public.trip_reports from anon, authenticated;
grant select on table public.trip_reports to authenticated;

create policy "owner and viewers select report" on public.trip_reports
  for select to authenticated
  using ((select private.is_trip_owner(trip_id)) or (select private.is_trip_viewer(trip_id)));

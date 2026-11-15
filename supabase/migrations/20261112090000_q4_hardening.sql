-- Q4 security pass (ADR 0011).
-- 1. Bound what one trip can push into the AI prompt: payload size and event count.
-- 2. Share links stop admitting new contacts 24 h after the trip ends (contacts who joined keep access).
-- 3. AI summary attempts are counted, so a failing or fake trip can't call Claude without limit.
-- 4. Trips (and their events, viewers, reports) are deleted 30 days after they end.

-- ── 1. Event bounds ────────────────────────────────────────────
-- App payloads are a few small numbers/strings (TripEventJson); 2 KB is ~20x the largest.
alter table public.trip_events
  add constraint trip_events_payload_size check (octet_length(payload::text) <= 2048);

-- A real trip logs tens of events (heartbeats are not events). 1000 stops a scripted flood.
create function private.enforce_trip_event_cap()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if (select count(*) from public.trip_events e where e.trip_id = new.trip_id) >= 1000 then
    raise exception 'trip event limit reached' using errcode = '54000';
  end if;
  return new;
end;
$$;

revoke all on function private.enforce_trip_event_cap() from public;

create trigger trip_events_cap
  before insert on public.trip_events
  for each row execute function private.enforce_trip_event_cap();

-- ── 2. Share-link join window ──────────────────────────────────
create or replace function public.join_trip(token text)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_trip_id uuid;
begin
  if v_uid is null then
    raise exception 'not authenticated' using errcode = '42501';
  end if;

  if token is null or token !~ '^[A-Za-z0-9_-]{22,}$' then
    return null;
  end if;

  -- Ended more than 24 h ago -> same answer as an unknown token.
  select t.id into v_trip_id
  from public.trips t
  where t.share_token = token
    and (t.status <> 'ended'
         or coalesce(t.ended_at, t.last_seen_at) > now() - interval '24 hours');

  if v_trip_id is null then
    return null;
  end if;

  insert into public.trip_viewers (trip_id, viewer)
  values (v_trip_id, v_uid)
  on conflict (trip_id, viewer) do nothing;

  return v_trip_id;
end;
$$;

revoke all on function public.join_trip(text) from public, anon;
grant execute on function public.join_trip(text) to authenticated;

-- ── 3. AI summary attempt budget ───────────────────────────────
-- Lives in the unexposed private schema; only the summarize-trip function (service role) claims.
create table private.summary_attempts (
  id bigint generated always as identity primary key,
  trip_id uuid not null references public.trips (id) on delete cascade,
  owner uuid not null,
  attempted_at timestamptz not null default now()
);

create index summary_attempts_trip_idx on private.summary_attempts (trip_id);
create index summary_attempts_owner_idx on private.summary_attempts (owner, attempted_at);

alter table private.summary_attempts enable row level security;
revoke all on table private.summary_attempts from public, anon, authenticated;

-- true = the caller may call Claude now (attempt recorded); false = budget used up.
-- Budget: 3 attempts per trip, 10 per owner in any 24 h.
create function public.claim_summary_attempt(p_trip_id uuid, p_owner uuid)
returns boolean
language plpgsql
volatile
set search_path = ''
as $$
begin
  -- Serialize per owner so concurrent calls can't both pass the check.
  perform pg_advisory_xact_lock(hashtextextended(p_owner::text, 0));

  if (select count(*) from private.summary_attempts a where a.trip_id = p_trip_id) >= 3
     or (select count(*) from private.summary_attempts a
         where a.owner = p_owner and a.attempted_at > now() - interval '24 hours') >= 10 then
    return false;
  end if;

  insert into private.summary_attempts (trip_id, owner) values (p_trip_id, p_owner);
  return true;
end;
$$;

revoke all on function public.claim_summary_attempt(uuid, uuid) from public, anon, authenticated;
grant usage on schema private to service_role;
grant select, insert on table private.summary_attempts to service_role;
grant execute on function public.claim_summary_attempt(uuid, uuid) to service_role;

-- ── 4. Retention: 30 days after the end ────────────────────────
-- Trips that never ended count from their last heartbeat.
create function private.purge_expired_trips()
returns integer
language sql
volatile
set search_path = ''
as $$
  with gone as (
    delete from public.trips t
    where coalesce(t.ended_at, t.last_seen_at) < now() - interval '30 days'
    returning 1
  )
  select count(*)::integer from gone;
$$;

revoke all on function private.purge_expired_trips() from public;

create extension if not exists pg_cron;

select cron.schedule('purge-expired-trips', '17 18 * * *', 'select private.purge_expired_trips()');  -- 03:17 KST

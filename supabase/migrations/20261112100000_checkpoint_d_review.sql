-- Checkpoint D review fixes (ADR 0011, "Checkpoint D review" section).
-- 1. Event cap: a re-sent (trip_id, seq) is a no-op again, and concurrent inserts can't pass 1000 together.
-- 2. join_trip: one window for every status: 24 h after the end, or after the last heartbeat for a trip that
--    went silent without ending.
-- 3. AI summary attempts: a project-wide ceiling of 200 per 24 h on top of the per-trip and per-owner budget.

-- ── 1. Event cap ───────────────────────────────────────────────
create or replace function private.enforce_trip_event_cap()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- Serialize inserts per trip so two requests can't both see 999.
  perform pg_advisory_xact_lock(hashtextextended(new.trip_id::text, 0));

  -- A row that is already there is skipped by ON CONFLICT DO NOTHING later; don't refuse the re-send.
  if exists (select 1 from public.trip_events e where e.trip_id = new.trip_id and e.seq = new.seq) then
    return new;
  end if;

  if (select count(*) from public.trip_events e where e.trip_id = new.trip_id) >= 1000 then
    raise exception 'trip event limit reached' using errcode = '54000';
  end if;
  return new;
end;
$$;

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

  -- A running trip sends a heartbeat every 30 s, so last_seen_at is fresh while it runs. Ended more than 24 h ago,
  -- or silent for 24 h without ending -> same answer as an unknown token.
  select t.id into v_trip_id
  from public.trips t
  where t.share_token = token
    and coalesce(t.ended_at, t.last_seen_at) > now() - interval '24 hours';

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

-- ── 3. Project-wide AI ceiling ─────────────────────────────────
create index summary_attempts_attempted_idx on private.summary_attempts (attempted_at);

-- true = the caller may call Claude now (attempt recorded); false = a budget is used up.
-- Budget: 3 attempts per trip, 10 per owner and 200 in the whole project in any 24 h.
create or replace function public.claim_summary_attempt(p_trip_id uuid, p_owner uuid)
returns boolean
language plpgsql
volatile
set search_path = ''
as $$
begin
  -- One claim at a time project-wide: the counts below are then exact. Claims are rare (one per finished trip).
  perform pg_advisory_xact_lock(hashtextextended('claim_summary_attempt', 0));

  if (select count(*) from private.summary_attempts a where a.trip_id = p_trip_id) >= 3
     or (select count(*) from private.summary_attempts a
         where a.owner = p_owner and a.attempted_at > now() - interval '24 hours') >= 10
     or (select count(*) from private.summary_attempts a
         where a.attempted_at > now() - interval '24 hours') >= 200 then
    return false;
  end if;

  insert into private.summary_attempts (trip_id, owner) values (p_trip_id, p_owner);
  return true;
end;
$$;

revoke all on function public.claim_summary_attempt(uuid, uuid) from public, anon, authenticated;
grant execute on function public.claim_summary_attempt(uuid, uuid) to service_role;

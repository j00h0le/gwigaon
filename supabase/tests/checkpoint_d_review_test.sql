-- Checkpoint D review fixes: event cap and re-sends, join window for silent trips, project-wide AI ceiling.
begin;
create extension if not exists pgtap with schema extensions;

select plan(9);

insert into auth.users (id, aud, role, is_anonymous, created_at, updated_at)
values
  ('51111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', true, now(), now()),
  ('52222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', true, now(), now()),
  ('53333333-3333-3333-3333-333333333333', 'authenticated', 'authenticated', true, now(), now());

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims',
    json_build_object('sub', uid, 'role', 'authenticated', 'is_anonymous', true)::text, true);
  select set_config('role', 'authenticated', true);
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated, anon;

insert into public.trips (id, owner, share_token, status, started_at, ended_at, last_seen_at) values
  -- running, heartbeat a minute ago
  ('cccccccc-0000-0000-0000-000000000001', '51111111-1111-1111-1111-111111111111',
   'cd_live_trip_0123456789abcdefgh', 'active', now() - interval '20 minutes', null, now() - interval '1 minute'),
  -- never ended, silent for 2 days (app died, battery gone)
  ('cccccccc-0000-0000-0000-000000000002', '51111111-1111-1111-1111-111111111111',
   'cd_silent_trip_0123456789abcdef', 'arriving', now() - interval '49 hours', null, now() - interval '48 hours'),
  -- never ended, silent for 23 h
  ('cccccccc-0000-0000-0000-000000000003', '51111111-1111-1111-1111-111111111111',
   'cd_quiet_trip_0123456789abcdefg', 'active', now() - interval '24 hours', null, now() - interval '23 hours'),
  -- ended trips used by the AI ceiling cases
  ('cccccccc-0000-0000-0000-000000000004', '52222222-2222-2222-2222-222222222222',
   'cd_ai_trip_a_0123456789abcdefgh', 'ended', now() - interval '1 hour', now(), now()),
  ('cccccccc-0000-0000-0000-000000000005', '53333333-3333-3333-3333-333333333333',
   'cd_ai_trip_b_0123456789abcdefgh', 'ended', now() - interval '1 hour', now(), now());

-- ── Join window ────────────────────────────────────────────────
select pg_temp.act_as('52222222-2222-2222-2222-222222222222');

select is(public.join_trip('cd_live_trip_0123456789abcdefgh'),
          'cccccccc-0000-0000-0000-000000000001'::uuid, 'running trip with a fresh heartbeat can be joined');
select is(public.join_trip('cd_silent_trip_0123456789abcdef'), null::uuid,
          'trip silent for 2 days without ending cannot be joined');
select is(public.join_trip('cd_quiet_trip_0123456789abcdefg'),
          'cccccccc-0000-0000-0000-000000000003'::uuid, 'trip silent for 23 h can still be joined');

-- ── Event cap and re-sends ─────────────────────────────────────
select pg_temp.act_as('51111111-1111-1111-1111-111111111111');

select lives_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  select 'cccccccc-0000-0000-0000-000000000001', s, 'LONG_STOP', now() from generate_series(1, 1000) s
$$, 'a trip takes 1000 events');

select lives_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  values ('cccccccc-0000-0000-0000-000000000001', 5, 'LONG_STOP', now())
  on conflict (trip_id, seq) do nothing
$$, 're-sending an event already stored is still a no-op at the limit');

select throws_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  values ('cccccccc-0000-0000-0000-000000000001', 1001, 'TRIP_ENDED', now())
  on conflict (trip_id, seq) do nothing
$$, '54000', null, 'a new event past the limit is still refused');

-- ── Project-wide AI ceiling ────────────────────────────────────
reset role;
-- Start from an empty budget whatever the database holds (rolled back at the end).
delete from private.summary_attempts;
-- 199 attempts in the last 24 h by other owners (direct rows; the budget function only counts them).
insert into private.summary_attempts (trip_id, owner, attempted_at)
select 'cccccccc-0000-0000-0000-000000000001', gen_random_uuid(), now() - interval '1 hour'
from generate_series(1, 199);
-- Older attempts don't count.
insert into private.summary_attempts (trip_id, owner, attempted_at)
select 'cccccccc-0000-0000-0000-000000000001', gen_random_uuid(), now() - interval '25 hours'
from generate_series(1, 50);

set local role service_role;
select is(public.claim_summary_attempt('cccccccc-0000-0000-0000-000000000004',
                                       '52222222-2222-2222-2222-222222222222'),
          true, 'the 200th attempt in 24 h is allowed');
select is(public.claim_summary_attempt('cccccccc-0000-0000-0000-000000000005',
                                       '53333333-3333-3333-3333-333333333333'),
          false, 'the 201st is refused, even for a fresh owner and trip');

reset role;
update private.summary_attempts set attempted_at = now() - interval '25 hours';
set local role service_role;
select is(public.claim_summary_attempt('cccccccc-0000-0000-0000-000000000005',
                                       '53333333-3333-3333-3333-333333333333'),
          true, 'the ceiling frees up after 24 h');

select * from finish();
rollback;

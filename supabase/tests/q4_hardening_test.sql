-- Q4 hardening (ADR 0011): event bounds, share-link join window, summary attempt budget, retention purge.
begin;
create extension if not exists pgtap with schema extensions;

select plan(17);

insert into auth.users (id, aud, role, is_anonymous, created_at, updated_at)
values
  ('41111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', true, now(), now()),
  ('42222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', true, now(), now());

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims',
    json_build_object('sub', uid, 'role', 'authenticated', 'is_anonymous', true)::text, true);
  select set_config('role', 'authenticated', true);
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated, anon;

-- Fixtures (as postgres): an active trip, one ended 2 h ago, one ended 2 days ago, one ended 31 days ago,
-- one never ended and silent for 31 days.
insert into public.trips (id, owner, share_token, status, started_at, ended_at, last_seen_at) values
  ('bbbbbbbb-0000-0000-0000-000000000001', '41111111-1111-1111-1111-111111111111',
   'q4_active_trip_0123456789abcdef', 'active', now() - interval '10 minutes', null, now()),
  ('bbbbbbbb-0000-0000-0000-000000000002', '41111111-1111-1111-1111-111111111111',
   'q4_recent_end_0123456789abcdef', 'ended', now() - interval '3 hours', now() - interval '2 hours', now() - interval '2 hours'),
  ('bbbbbbbb-0000-0000-0000-000000000003', '41111111-1111-1111-1111-111111111111',
   'q4_old_end_trip_0123456789abcdef', 'ended', now() - interval '49 hours', now() - interval '48 hours', now() - interval '48 hours'),
  ('bbbbbbbb-0000-0000-0000-000000000004', '41111111-1111-1111-1111-111111111111',
   'q4_expired_trip_0123456789abcdef', 'ended', now() - interval '32 days', now() - interval '31 days', now() - interval '31 days'),
  ('bbbbbbbb-0000-0000-0000-000000000005', '41111111-1111-1111-1111-111111111111',
   'q4_abandoned_trip_0123456789abcd', 'active', now() - interval '32 days', null, now() - interval '31 days');

insert into public.trip_events (trip_id, seq, type, occurred_at)
values ('bbbbbbbb-0000-0000-0000-000000000004', 1, 'TRIP_STARTED', now() - interval '32 days');

-- ── 1. Event bounds ────────────────────────────────────────────
select pg_temp.act_as('41111111-1111-1111-1111-111111111111');

select throws_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at, payload)
  values ('bbbbbbbb-0000-0000-0000-000000000001', 1, 'LONG_STOP', now(),
          jsonb_build_object('pad', repeat('x', 3000)))
$$, '23514', null, 'payload over 2 KB is refused');

select lives_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at, payload)
  values ('bbbbbbbb-0000-0000-0000-000000000001', 1, 'PIN_LOCKOUT', now(), '{"failures": 5, "locked_s": 30}')
$$, 'normal payload is accepted');

select lives_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  select 'bbbbbbbb-0000-0000-0000-000000000001', s, 'LONG_STOP', now() from generate_series(2, 1000) s
$$, 'a trip takes up to 1000 events');

select throws_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  values ('bbbbbbbb-0000-0000-0000-000000000001', 1001, 'LONG_STOP', now())
$$, '54000', null, 'event 1001 is refused');

-- ── 2. Join window ─────────────────────────────────────────────
select pg_temp.act_as('42222222-2222-2222-2222-222222222222');

select is(public.join_trip('q4_active_trip_0123456789abcdef'),
          'bbbbbbbb-0000-0000-0000-000000000001'::uuid, 'active trip can be joined');
select is(public.join_trip('q4_recent_end_0123456789abcdef'),
          'bbbbbbbb-0000-0000-0000-000000000002'::uuid, 'trip ended 2 h ago can still be joined');
select is(public.join_trip('q4_old_end_trip_0123456789abcdef'), null::uuid,
          'trip ended 48 h ago cannot be joined');
select is((select count(*)::int from public.trips where id = 'bbbbbbbb-0000-0000-0000-000000000003'), 0,
          'refused join grants no read access');

-- A contact who joined in time keeps access after the window closes.
reset role;
update public.trips set ended_at = now() - interval '3 days' where id = 'bbbbbbbb-0000-0000-0000-000000000002';
select pg_temp.act_as('42222222-2222-2222-2222-222222222222');
select is((select count(*)::int from public.trips where id = 'bbbbbbbb-0000-0000-0000-000000000002'), 1,
          'contact who joined in time keeps reading after the window');

-- ── 3. Summary attempt budget ──────────────────────────────────
select throws_ok($$ select public.claim_summary_attempt('bbbbbbbb-0000-0000-0000-000000000002',
                                                        '41111111-1111-1111-1111-111111111111') $$,
                 '42501', null, 'clients cannot claim summary attempts');

reset role;
set local role service_role;

select is(array(select public.claim_summary_attempt('bbbbbbbb-0000-0000-0000-000000000002',
                                                    '41111111-1111-1111-1111-111111111111')
                from generate_series(1, 4)),
          array[true, true, true, false], 'three attempts per trip, the fourth is refused');

select is(array(select public.claim_summary_attempt(t, '41111111-1111-1111-1111-111111111111')
                from unnest(array['bbbbbbbb-0000-0000-0000-000000000001',
                                  'bbbbbbbb-0000-0000-0000-000000000001',
                                  'bbbbbbbb-0000-0000-0000-000000000001',
                                  'bbbbbbbb-0000-0000-0000-000000000003',
                                  'bbbbbbbb-0000-0000-0000-000000000003',
                                  'bbbbbbbb-0000-0000-0000-000000000003',
                                  'bbbbbbbb-0000-0000-0000-000000000004',
                                  'bbbbbbbb-0000-0000-0000-000000000004']::uuid[]) t),
          array[true, true, true, true, true, true, true, false],
          'ten attempts per owner in 24 h across trips, the eleventh is refused');

select is(public.claim_summary_attempt('bbbbbbbb-0000-0000-0000-000000000001',
                                       '42222222-2222-2222-2222-222222222222'),
          false, 'per-trip budget holds whoever claims');

reset role;
update private.summary_attempts set attempted_at = now() - interval '25 hours';
set local role service_role;
select is(public.claim_summary_attempt('bbbbbbbb-0000-0000-0000-000000000005',
                                       '41111111-1111-1111-1111-111111111111'),
          true, 'owner budget frees up after 24 h');

-- ── 4. Retention ───────────────────────────────────────────────
reset role;
select is(private.purge_expired_trips(), 2, 'purge deletes the two trips silent for over 30 days');
select is(array(select id::text from public.trips where id::text like 'bbbbbbbb-%' order by id),
          array['bbbbbbbb-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002',
                'bbbbbbbb-0000-0000-0000-000000000003'],
          'recent trips stay');
select is((select count(*)::int from cron.job where jobname = 'purge-expired-trips'), 1,
          'purge is scheduled daily');

select * from finish();
rollback;

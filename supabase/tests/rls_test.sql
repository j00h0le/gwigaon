-- RLS and join_trip tests: owner / viewer / stranger / bad token.
begin;
create extension if not exists pgtap with schema extensions;

select plan(34);

-- Fixtures (as postgres): three anonymous users.
insert into auth.users (id, aud, role, is_anonymous, created_at, updated_at)
values
  ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', true, now(), now()),
  ('22222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', true, now(), now()),
  ('33333333-3333-3333-3333-333333333333', 'authenticated', 'authenticated', true, now(), now());

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims',
    json_build_object('sub', uid, 'role', 'authenticated', 'is_anonymous', true)::text, true);
  select set_config('role', 'authenticated', true);
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated, anon;

-- ── Schema sanity ──────────────────────────────────────────────
select ok((select bool_and(relrowsecurity) from pg_class
           where oid in ('public.trips'::regclass, 'public.trip_events'::regclass,
                         'public.trip_viewers'::regclass, 'public.trip_reports'::regclass)),
          'RLS enabled on all four tables');

-- ── Owner ──────────────────────────────────────────────────────
select pg_temp.act_as('11111111-1111-1111-1111-111111111111');

select lives_ok($$
  insert into public.trips (id, share_token, destination_label, contact_label)
  values ('aaaaaaaa-0000-0000-0000-000000000001', 'tok_owner_trip_0123456789abcdef', '집', '엄마')
$$, 'owner inserts own trip (owner defaults to auth.uid())');

select is((select owner from public.trips where id = 'aaaaaaaa-0000-0000-0000-000000000001'),
          '11111111-1111-1111-1111-111111111111'::uuid, 'owner column set to caller');

select throws_ok($$
  insert into public.trips (id, owner, share_token)
  values ('aaaaaaaa-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222',
          'tok_forged_owner_0123456789abcdef')
$$, '42501', null, 'cannot insert a trip owned by someone else');

select lives_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at, lat, lng, reason)
  values ('aaaaaaaa-0000-0000-0000-000000000001', 1, 'TRIP_STARTED', now(), 37.5, 127.0, null),
         ('aaaaaaaa-0000-0000-0000-000000000001', 2, 'ROUTE_DEVIATION', now(), 37.51, 127.01, '경로에서 420m 벗어남')
$$, 'owner inserts events');

select lives_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  values ('aaaaaaaa-0000-0000-0000-000000000001', 2, 'ROUTE_DEVIATION', now())
  on conflict (trip_id, seq) do nothing
$$, 'duplicate seq upload is a no-op with on conflict do nothing');

select is((select count(*)::int from public.trip_events
           where trip_id = 'aaaaaaaa-0000-0000-0000-000000000001'), 2, 'still 2 events after duplicate');

-- PIN_LOCKOUT (T16) is a valid type; an unknown type is still refused. Events can't be deleted, so this uses a
-- second trip of the same owner and the counts on the first trip below stay as they are.
select lives_ok($$
  insert into public.trips (id, share_token, status) values
    ('aaaaaaaa-0000-0000-0000-0000000000f1', 'tok_pin_lockout_trip_0123456789', 'active');
  insert into public.trip_events (trip_id, seq, type, occurred_at, reason, payload)
  values ('aaaaaaaa-0000-0000-0000-0000000000f1', 1, 'PIN_LOCKOUT', now(), 'PIN 5회 오입력 · 30초 잠금',
          '{"failures": 5, "locked_s": 30}')
$$, 'owner inserts a PIN_LOCKOUT event');

-- SETTINGS_CHANGED (T26, ADR 0015) is a valid type: what changed in 설정, never the values.
select lives_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at, reason, payload)
  values ('aaaaaaaa-0000-0000-0000-0000000000f1', 10, 'SETTINGS_CHANGED', now(), '설정 변경: 집 주소, PIN',
          '{"fields": ["home", "pin"]}')
$$, 'owner inserts a SETTINGS_CHANGED event');

select throws_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  values ('aaaaaaaa-0000-0000-0000-0000000000f1', 2, 'PIN_UNLOCKED', now())
$$, '23514', null, 'unknown event type is refused');

select throws_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  values ('aaaaaaaa-0000-0000-0000-000000000001', 3, 'NOT_A_TYPE', now())
$$, '23514', null, 'unknown event type rejected');

select lives_ok($$
  update public.trips set last_seen_at = now(), status = 'arriving'
  where id = 'aaaaaaaa-0000-0000-0000-000000000001'
$$, 'owner updates heartbeat and status');

select is((select status from public.trips where id = 'aaaaaaaa-0000-0000-0000-000000000001'),
          'arriving', 'status updated');

select throws_ok($$
  update public.trips set share_token = 'tok_changed_0123456789abcdefgh'
  where id = 'aaaaaaaa-0000-0000-0000-000000000001'
$$, '42501', null, 'share_token is not updatable');

select throws_ok($$
  update public.trip_events set reason = 'tampered' where trip_id = 'aaaaaaaa-0000-0000-0000-000000000001'
$$, '42501', null, 'events are append-only (no update)');

select throws_ok($$
  delete from public.trip_events where trip_id = 'aaaaaaaa-0000-0000-0000-000000000001'
$$, '42501', null, 'events are append-only (no delete)');

select throws_ok($$
  insert into public.trip_reports (trip_id, summary, model)
  values ('aaaaaaaa-0000-0000-0000-000000000001', '{}', 'x')
$$, '42501', null, 'owner cannot write a report (Edge Function only)');

-- ── Stranger, before and without joining ───────────────────────
select pg_temp.act_as('33333333-3333-3333-3333-333333333333');

select is_empty($$ select * from public.trips $$, 'stranger sees no trips');
select is_empty($$ select * from public.trip_events $$, 'stranger sees no events');
select is_empty($$ select * from public.trip_viewers $$, 'stranger sees no viewers');

select throws_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  values ('aaaaaaaa-0000-0000-0000-000000000001', 99, 'SOS', now())
$$, '42501', null, 'stranger cannot insert events into someone else''s trip');

update public.trips set status = 'ended' where id = 'aaaaaaaa-0000-0000-0000-000000000001';
select is((select count(*)::int from public.trips), 0, 'stranger update touches nothing (still invisible)');

select throws_ok($$
  insert into public.trip_viewers (trip_id, viewer)
  values ('aaaaaaaa-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333')
$$, '42501', null, 'stranger cannot add itself to trip_viewers directly');

-- ── Bad token ──────────────────────────────────────────────────
select is(public.join_trip('tok_does_not_exist_0123456789ab'), null, 'unknown token returns null');
select is(public.join_trip('short'), null, 'malformed token returns null');
select is_empty($$ select * from public.trips $$, 'bad token grants nothing');

-- ── Viewer ─────────────────────────────────────────────────────
select pg_temp.act_as('22222222-2222-2222-2222-222222222222');

select is_empty($$ select * from public.trip_events $$, 'viewer sees nothing before joining');
select is(public.join_trip('tok_owner_trip_0123456789abcdef'),
          'aaaaaaaa-0000-0000-0000-000000000001'::uuid, 'join_trip returns trip id');
select lives_ok($$ select public.join_trip('tok_owner_trip_0123456789abcdef') $$, 'joining twice is fine');
select is((select count(*)::int from public.trip_events), 2, 'viewer reads events after join');
select is((select destination_label from public.trips), '집', 'viewer reads the trip after join');

update public.trips set status = 'ended' where id = 'aaaaaaaa-0000-0000-0000-000000000001';
select throws_ok($$
  insert into public.trip_events (trip_id, seq, type, occurred_at)
  values ('aaaaaaaa-0000-0000-0000-000000000001', 50, 'SOS', now())
$$, '42501', null, 'viewer cannot insert events');

-- ── anon role (no sign-in) ─────────────────────────────────────
select set_config('role', 'anon', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok($$ select * from public.trips $$, '42501', null, 'anon role has no table access');

reset role;
select is((select status from public.trips where id = 'aaaaaaaa-0000-0000-0000-000000000001'),
          'arriving', 'viewer/stranger updates did not change the trip');

select * from finish();
rollback;

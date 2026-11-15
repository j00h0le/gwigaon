-- One standing link per account (ADR 0019): register, replace, join, the phone cap and what a link can see.
begin;
create extension if not exists pgtap with schema extensions;

select plan(25);

insert into auth.users (id, aud, role, is_anonymous, created_at, updated_at)
values
  ('61111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated', true, now(), now()), -- owner (car)
  ('62222222-2222-2222-2222-222222222222', 'authenticated', 'authenticated', true, now(), now()), -- contact phone
  ('63333333-3333-3333-3333-333333333333', 'authenticated', 'authenticated', true, now(), now()), -- stranger
  ('64444444-4444-4444-4444-444444444441', 'authenticated', 'authenticated', true, now(), now()),
  ('64444444-4444-4444-4444-444444444442', 'authenticated', 'authenticated', true, now(), now()),
  ('64444444-4444-4444-4444-444444444443', 'authenticated', 'authenticated', true, now(), now()),
  ('64444444-4444-4444-4444-444444444444', 'authenticated', 'authenticated', true, now(), now()),
  ('64444444-4444-4444-4444-444444444445', 'authenticated', 'authenticated', true, now(), now());

create function pg_temp.act_as(uid uuid) returns void language sql as $$
  select set_config('request.jwt.claims',
    json_build_object('sub', uid, 'role', 'authenticated', 'is_anonymous', true)::text, true);
  select set_config('role', 'authenticated', true);
$$;
grant execute on function pg_temp.act_as(uuid) to authenticated, anon;

-- ── Register ───────────────────────────────────────────────────
select pg_temp.act_as('61111111-1111-1111-1111-111111111111');
select ok(public.set_owner_link('ol_first_token_0123456789abc', now() - interval '1 hour'), 'owner registers a link');
select ok(public.set_owner_link('ol_first_token_0123456789abc', now()), 'registering the same token again is a no-op');
select is((select count(*)::int from public.owner_links), 1, 'still one row after the retry');
select ok((select created_at < now() - interval '50 minutes' from public.owner_links), 'created_at comes from the car');
select ok(not public.set_owner_link('short', now()), 'a malformed token is refused');
select throws_ok($$ insert into public.owner_links (token, owner) values ('ol_direct_insert_0123456789ab', '61111111-1111-1111-1111-111111111111') $$,
  '42501', null, 'clients cannot insert links directly');

-- Trips: one from before the link, one running, one ended 2 days ago, one of the stranger.
reset role;
insert into public.trips (id, owner, share_token, status, started_at, ended_at, last_seen_at) values
  ('dddddddd-0000-0000-0000-000000000001', '61111111-1111-1111-1111-111111111111',
   'ol_trip_before_0123456789abcd', 'ended', now() - interval '3 hours', now() - interval '2 hours', now() - interval '2 hours'),
  ('dddddddd-0000-0000-0000-000000000002', '61111111-1111-1111-1111-111111111111',
   'ol_trip_live_0123456789abcdef', 'active', now() - interval '10 minutes', null, now() - interval '30 seconds'),
  ('dddddddd-0000-0000-0000-000000000004', '63333333-3333-3333-3333-333333333333',
   'ol_trip_other_0123456789abcde', 'active', now() - interval '10 minutes', null, now());
insert into public.trip_events (trip_id, seq, type, occurred_at) values
  ('dddddddd-0000-0000-0000-000000000002', 1, 'TRIP_STARTED', now() - interval '10 minutes');

-- ── Join and see ───────────────────────────────────────────────
select pg_temp.act_as('62222222-2222-2222-2222-222222222222');
select is((select count(*)::int from public.trips), 0, 'before joining, the phone sees no trips');
select is(public.join_owner_link('ol_first_token_0123456789abc'), '61111111-1111-1111-1111-111111111111'::uuid,
  'joining returns the owner');
select is(public.join_owner_link('ol_first_token_0123456789abc'), '61111111-1111-1111-1111-111111111111'::uuid,
  'joining again is fine');
select is((select array_agg(id order by id) from public.trips),
  array['dddddddd-0000-0000-0000-000000000002'::uuid], 'the running trip shows; the one from before the link does not');
select is((select count(*)::int from public.trip_events), 1, 'its events show too');
select is(public.join_owner_link('ol_unknown_token_0123456789a'), null::uuid, 'an unknown token returns nothing');
select throws_ok($$ insert into public.owner_link_viewers (token, viewer) values ('ol_first_token_0123456789abc', '62222222-2222-2222-2222-222222222222') $$,
  '42501', null, 'clients cannot add themselves as viewers directly');

-- A new trip of the owner shows as soon as it starts.
reset role;
insert into public.trips (id, owner, share_token, status, started_at, ended_at, last_seen_at) values
  ('dddddddd-0000-0000-0000-000000000005', '61111111-1111-1111-1111-111111111111',
   'ol_trip_next_0123456789abcdef', 'active', now(), null, now());
select pg_temp.act_as('62222222-2222-2222-2222-222222222222');
select ok(exists (select 1 from public.trips where id = 'dddddddd-0000-0000-0000-000000000005'), 'a newly started trip appears on the link');

-- The 24 h window: an old ended trip of the owner (started after the link's created_at, backdated for the test).
reset role;
update public.owner_links set created_at = now() - interval '3 days' where token = 'ol_first_token_0123456789abc';
insert into public.trips (id, owner, share_token, status, started_at, ended_at, last_seen_at) values
  ('dddddddd-0000-0000-0000-000000000003', '61111111-1111-1111-1111-111111111111',
   'ol_trip_old_0123456789abcdefg', 'ended', now() - interval '50 hours', now() - interval '48 hours', now() - interval '48 hours');
select pg_temp.act_as('62222222-2222-2222-2222-222222222222');
select ok(not exists (select 1 from public.trips where id = 'dddddddd-0000-0000-0000-000000000003'),
  'a trip that ended more than 24 h ago is not visible');
select ok(exists (select 1 from public.trips where id = 'dddddddd-0000-0000-0000-000000000001'),
  'a trip that ended 2 h ago (after the link) is visible');

-- ── Who sees what ──────────────────────────────────────────────
select pg_temp.act_as('63333333-3333-3333-3333-333333333333');
select is((select count(*)::int from public.owner_links), 0, 'a stranger sees no links');
select is((select count(*)::int from public.owner_link_viewers), 0, 'a stranger sees no viewers');
select is((select count(*)::int from public.trips where owner = '61111111-1111-1111-1111-111111111111'), 0,
  'a stranger sees none of the owner''s trips');

select pg_temp.act_as('61111111-1111-1111-1111-111111111111');
select is((select count(*)::int from public.owner_link_viewers), 1, 'the owner sees who joined (연결됨)');

-- ── Phone cap ──────────────────────────────────────────────────
select pg_temp.act_as('64444444-4444-4444-4444-444444444441'); select public.join_owner_link('ol_first_token_0123456789abc');
select pg_temp.act_as('64444444-4444-4444-4444-444444444442'); select public.join_owner_link('ol_first_token_0123456789abc');
select pg_temp.act_as('64444444-4444-4444-4444-444444444443'); select public.join_owner_link('ol_first_token_0123456789abc');
select pg_temp.act_as('64444444-4444-4444-4444-444444444444');
select is(public.join_owner_link('ol_first_token_0123456789abc'), '61111111-1111-1111-1111-111111111111'::uuid, 'the 5th phone gets in');
select pg_temp.act_as('64444444-4444-4444-4444-444444444445');
select is(public.join_owner_link('ol_first_token_0123456789abc'), null::uuid, 'a 6th phone is answered like an unknown link');

-- ── Replace (QR 다시 만들기) ────────────────────────────────────
select pg_temp.act_as('61111111-1111-1111-1111-111111111111');
select ok(public.set_owner_link('ol_second_token_0123456789ab', now()), 'a new token replaces the link');
select pg_temp.act_as('62222222-2222-2222-2222-222222222222');
select is((select count(*)::int from public.trips), 0, 'phones on the old link lose access at once');
select pg_temp.act_as('63333333-3333-3333-3333-333333333333');
select ok(not public.set_owner_link('ol_second_token_0123456789ab', now()), 'nobody else can claim the owner''s token');

select * from finish();
rollback;

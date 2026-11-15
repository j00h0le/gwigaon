-- Local-only seed for the contact page and Edge Function (applied by `supabase db reset`).
-- Tokens here are public demo values for the LOCAL stack; never reuse them remotely.
--   Live trip:  http://localhost:8080/#t=seedLiveTrip_0123456789abcd
--   Ended trip: http://localhost:8080/#t=seedEndedTrip_0123456789abc

insert into auth.users (id, instance_id, aud, role, is_anonymous, created_at, updated_at)
values ('00000000-0000-4000-8000-0000000000a1', '00000000-0000-0000-0000-000000000000',
        'authenticated', 'authenticated', true, now(), now());

-- 1) Live trip: started 25 min ago, deviated, cleared, long stop in progress.
insert into public.trips (id, owner, share_token, status, destination_label, contact_label, started_at, last_seen_at)
values ('00000000-0000-4000-8000-00000000b001', '00000000-0000-4000-8000-0000000000a1',
        'seedLiveTrip_0123456789abcd', 'active', '집 (분당구 정자동)', '엄마',
        now() - interval '25 minutes', now());

insert into public.trip_events (trip_id, seq, type, occurred_at, lat, lng, reason, payload) values
  ('00000000-0000-4000-8000-00000000b001', 1, 'TRIP_STARTED',      now() - interval '25 minutes', 37.4979, 127.0276, null, '{}'),
  ('00000000-0000-4000-8000-00000000b001', 2, 'ROUTE_DEVIATION',   now() - interval '15 minutes', 37.4850, 127.0450, '목적지와 다른 방향으로 1.2km 이동', '{"distance_m": 1200}'),
  ('00000000-0000-4000-8000-00000000b001', 3, 'DEVIATION_CLEARED', now() - interval '11 minutes', 37.4700, 127.0600, '경로로 복귀', '{}'),
  ('00000000-0000-4000-8000-00000000b001', 4, 'LONG_STOP',         now() - interval '4 minutes',  37.4500, 127.0800, '목적지 외 장소에서 7분 정차', '{"duration_s": 420}');

-- 2) Ended trip with SOS (cancelled), safe arrival, and no report yet (for summarize-trip).
insert into public.trips (id, owner, share_token, status, destination_label, contact_label, started_at, ended_at, last_seen_at)
values ('00000000-0000-4000-8000-00000000b002', '00000000-0000-4000-8000-0000000000a1',
        'seedEndedTrip_0123456789abc', 'ended', '회사 (판교역)', '동생',
        now() - interval '3 hours', now() - interval '2 hours 20 minutes', now() - interval '2 hours 20 minutes');

insert into public.trip_events (trip_id, seq, type, occurred_at, lat, lng, reason, payload) values
  ('00000000-0000-4000-8000-00000000b002', 1, 'TRIP_STARTED',              now() - interval '3 hours',                37.5665, 126.9780, null, '{}'),
  ('00000000-0000-4000-8000-00000000b002', 2, 'MONITORING_INTERRUPTED',    now() - interval '2 hours 50 minutes',     37.5400, 127.0000, '앱 재시작으로 2분간 모니터링 중단', '{"gap_s": 120}'),
  ('00000000-0000-4000-8000-00000000b002', 3, 'MONITORING_RESUMED',        now() - interval '2 hours 48 minutes',     37.5350, 127.0050, null, '{}'),
  ('00000000-0000-4000-8000-00000000b002', 4, 'SOS',                       now() - interval '2 hours 40 minutes',     37.5100, 127.0500, '방향지시등 패턴으로 SOS', '{"source": "turn_signal"}'),
  ('00000000-0000-4000-8000-00000000b002', 5, 'SOS_CANCELLED',             now() - interval '2 hours 39 minutes',     37.5100, 127.0500, '오작동으로 취소', '{}'),
  ('00000000-0000-4000-8000-00000000b002', 6, 'NEAR_DESTINATION',          now() - interval '2 hours 30 minutes',     37.3960, 127.1100, '목적지 500m 이내', '{}'),
  ('00000000-0000-4000-8000-00000000b002', 7, 'ARRIVED',                   now() - interval '2 hours 27 minutes',     37.3947, 127.1112, null, '{}'),
  ('00000000-0000-4000-8000-00000000b002', 8, 'SAFE_ARRIVAL_CHECK_OPENED', now() - interval '2 hours 24 minutes',     37.3947, 127.1112, '도착 후 3분간 승객이 하차하지 않음', '{}'),
  ('00000000-0000-4000-8000-00000000b002', 9, 'SAFE_ARRIVAL_CONFIRMED',    now() - interval '2 hours 22 minutes',     37.3947, 127.1112, '승객 하차 확인', '{"closed_by": "seat_empty"}'),
  ('00000000-0000-4000-8000-00000000b002', 10, 'TRIP_ENDED',               now() - interval '2 hours 20 minutes',     37.3947, 127.1112, '안전 귀가', '{"reason": "safe_arrival"}');

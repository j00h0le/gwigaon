-- SETTINGS_CHANGED: 설정 changed since the last trip (ADR 0015, T26), logged right after TRIP_STARTED so the 보호자
-- sees it. Payload: {"fields": ["home"|"contacts"|"cues"|"arrival"|"pin", ...]}, never the values.
-- TRIP_STARTED gains payload {"source": "button"|"gear"}; payloads are not constrained, so that needs no change.
-- Same list as before plus SETTINGS_CHANGED; the app's EventType enum is the source of the wire names.

alter table public.trip_events drop constraint trip_events_type_check;

alter table public.trip_events add constraint trip_events_type_check check (type in (
  'TRIP_STARTED', 'ROUTE_DEVIATION', 'DEVIATION_CLEARED', 'LONG_STOP', 'LONG_STOP_CLEARED',
  'SOS', 'SOS_CANCELLED', 'MONITORING_INTERRUPTED', 'MONITORING_RESUMED',
  'NEAR_DESTINATION', 'ARRIVED', 'SAFE_ARRIVAL_CHECK_OPENED', 'SAFE_ARRIVAL_CONFIRMED',
  'CHECK_IN_PROMPTED', 'CHECK_IN_ANSWERED', 'TRIP_ENDED', 'PIN_LOCKOUT', 'SETTINGS_CHANGED'
));

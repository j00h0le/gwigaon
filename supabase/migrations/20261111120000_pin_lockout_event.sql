-- PIN_LOCKOUT: the app reports a PIN lockout (too many wrong PINs in the car) to the trusted contact
-- (owner decision, Checkpoint C; T16). Payload: {"failures": int, "locked_s": int}.
-- Same list as before plus PIN_LOCKOUT; the app's EventType enum is the source of the wire names.

alter table public.trip_events drop constraint trip_events_type_check;

alter table public.trip_events add constraint trip_events_type_check check (type in (
  'TRIP_STARTED', 'ROUTE_DEVIATION', 'DEVIATION_CLEARED', 'LONG_STOP', 'LONG_STOP_CLEARED',
  'SOS', 'SOS_CANCELLED', 'MONITORING_INTERRUPTED', 'MONITORING_RESUMED',
  'NEAR_DESTINATION', 'ARRIVED', 'SAFE_ARRIVAL_CHECK_OPENED', 'SAFE_ARRIVAL_CONFIRMED',
  'CHECK_IN_PROMPTED', 'CHECK_IN_ANSWERED', 'TRIP_ENDED', 'PIN_LOCKOUT'
));

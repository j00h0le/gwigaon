# ADR 0005: A confirmed safe arrival ends the trip

Date: 2026-11-11 · Status: accepted; driver-confirmation path superseded by [ADR 0007](0007-driver-confirmation-does-not-end-the-trip.md) (Checkpoint C)

## Context

SPEC arrival says the safe-arrival check closes in one of three ways: the passenger seat becomes empty, the passenger enters the PIN, or the driver confirms "승객 반응 있음". Each logs `SAFE_ARRIVAL_CONFIRMED` with `closed_by`. The SPEC didn't say what happens to the trip after that. The state machine allows `TRIP_ENDED {reason: safe_arrival}` only after a confirmation and with no open SOS, but nothing logged it. The contact page shows "안전 귀가" only for a `safe_arrival` end (or a confirmation), and the passenger is usually gone by then and can't end the trip with the PIN.

## Decision

`SafeArrivalMonitor` ends the trip as `safe_arrival` as soon as the trip state shows a confirmed arrival with no open SOS. It does this the same way for all three close paths, since it watches the trip state rather than the rule that confirmed it. If an SOS is open, the trip keeps running until the SOS is cancelled (then it ends) or the passenger ends it with the PIN.

## Alternatives

- **End only on an empty seat; after a PIN or driver confirmation, wait for the seat to empty.** That's closer to "the passenger got out", but the state machine allows only one confirmation, and a passenger who stays seated while fine (finishing a call) would hold the trip open with nothing left to close it.
- **Leave ending to the passenger's PIN.** The passenger has usually left, so the trip would sit open and the contact would never see "안전 귀가".

## Consequences

- A driver confirmation ends the trip while the passenger may still be in the car. The contact's timeline says so ("기사가 승객 반응 확인"), so the contact can tell it apart from an empty seat.
- The car screen goes back to setup right after the passenger gets out. The journey report (T19) opens from there.

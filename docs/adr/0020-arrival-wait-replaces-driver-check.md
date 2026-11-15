# ADR 0020: The arrival wait screen replaces the driver's safe-arrival check; 1 km end boundary

Date: 2026-11-14 · Status: accepted (owner, D3f feedback) · Amends ADR 0005, ADR 0007, ADR 0015 (ending without a PIN), SPEC arrival and trip-core

## Context

D5 (S16) asked the driver to check the passenger and tap 승객 반응 있음, and D7 (S18) then waited for the seat to empty. The owner's point: getting the passenger out is not the driver's job; the app's value is that it can tell whether the passenger got off. Separately, the end dialog used `TripStatus.ARRIVING` to decide plain vs press-and-hold, and that status never resets, so a car 1.6 km away still got the plain "도착했어요" end (S13).

## Decision

- **No driver check.** D5 and the 승객 반응 있음 action go. D4 (arrived) suggests, without requiring anything, "하차 전에 승객 상태를 한 번 확인해 주세요".
- **Wait screen.** If the passenger is still seated `safeArrivalDelay` after arrival in P (3 min, demo 30 s), the check opens as today (`SAFE_ARRIVAL_CHECK_OPENED`, the contact sees it) and the car shows a count-up wait screen: "N분째 하차하지 않음" and "비상 연락처에게 알렸어요". It isn't addressed to anyone and has no confirm button.
- **How it ends:** seat empty in P → `safe_arrival` (as today); on a PIN trip the passenger's PIN also ends it (PIN으로 종료하기). A no-PIN trip has no end button on the wait screen (ADR 0007's reasoning: one tap must not switch monitoring off).
- **1 km end boundary.** Parked within 1 km of the destination, a no-PIN 운행 종료 is plain ("운행을 종료할까요?", `ended_at_arrival`); beyond 1 km it is the press-and-hold ("목적지 밖이에요", "1.6km 남음", `ended_early`). The decision uses the current distance, not a status set once. The D4 arrival screen still needs the car within `arrivedMeters` (100 m).

## Consequences

- `SAFE_ARRIVAL_CONFIRMED {closed_by: driver_confirmed}` is no longer produced by the app; the wire value stays valid for stored trips and the contact page.
- `MonitoringConfig` gains `plainEndMeters = 1000`. `TripEndRule` decides on distance; `ArrivalRule` loses `CHECK`/`WAITING` in favour of one `WAITING` stage driven by the check being open.
- The status line and timeline keep "도착 후 N분째 내리지 않았어요" for the contact.

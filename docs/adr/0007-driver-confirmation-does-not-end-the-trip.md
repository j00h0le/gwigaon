# ADR 0007: A driver's confirmation closes the check but doesn't end the trip

Date: 2026-11-11 · Status: accepted (owner decision at Checkpoint C, option a) · Supersedes the end behaviour of [ADR 0005](0005-confirmed-arrival-ends-the-trip.md) for the driver path

## Context

ADR 0005 ended the trip as `safe_arrival` on any of the three confirmations. The Q3 security review (ADR 0006) names the driver as the most likely person to misuse the app, and "승객 반응 있음" is a single tap with no PIN. A driver could tap it and the contact would see "안전 귀가" while the passenger was still in the car.

## Decision

- The driver's "승객 반응 있음" still closes the safe-arrival check and logs `SAFE_ARRIVAL_CONFIRMED {closed_by: driver_confirmed}`. The trip keeps running. The trip state remembers who closed the check (`safeArrivalClosedBy`).
- After a driver confirmation the trip ends only when the passenger seat is empty with the gear in P (`TRIP_ENDED {reason: safe_arrival}`, "승객 하차 후 안전 귀가") or when the passenger ends it with the PIN (`ended_by_passenger`).
- Seat-empty and passenger-PIN confirmations still end the trip right away, as in ADR 0005.
- Car screen status after a driver confirmation: "승객 하차를 기다리는 중". Contact page: "기사가 승객 반응 확인 · 하차 대기 중" (warn tone); "안전 귀가" only after the trip ends.

## Alternatives

- **Keep ADR 0005, but log the car leaving P after a driver confirmation.** Still shows "안전 귀가" first and corrects it later; the contact may already have stopped watching.
- **Require the passenger PIN for the driver path.** The driver path exists for a passenger who can't enter it.

## Consequences

- A passenger who stays seated and fine after a driver confirmation keeps the trip open until they get out or enter the PIN. Accepted: that's the case the check is for.
- An unknown seat or gear never ends the trip after a driver confirmation.

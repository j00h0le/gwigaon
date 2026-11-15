# ADR 0004: Route deviation measured from best progress, not a route line

Date: 2026-11-11 · Status: accepted (built in T12; the owner reviews it at Checkpoint C)

## Context

The plan for T12 said "deviation against the route polyline, with a distance-increasing fallback". The polyline doesn't exist: NaviHelper exposes no route geometry, and the emulator's navigation is a mock that never follows the car (T3, `research/findings/spike-navi.md`). That leaves only the fallback. SPEC worded it as "distance to destination increasing by > 300 m over 60 s while DRIVING", which can be read two ways:

1. **Window:** the distance now minus the distance 60 s ago is more than 300 m. This only fires while the car is still moving away. A car that drives off and then stops on the detour would "un-deviate" while standing there.
2. **Best progress:** the car's closest approach to the destination so far is its progress. The car is deviating when it has been more than 300 m further away than that, continuously for 60 s.

## Decision

Use best progress (2). `DeviationRule` keeps the closest distance seen in the trip. It opens when the excess has been over `deviationMeters` (300 m) for `deviationDuration` (60 s) while DRIVING. It clears once the excess is back under `deviationClearMeters` (150 m, new in `MonitoringConfig`). The gap between 300 and 150 keeps GPS noise from flapping it open and shut. Clearing doesn't depend on gear. `ROUTE_DEVIATION.distance_m` is the excess at the moment it opens.

Long stop: the rule only starts counting after the car has first moved more than 30 m, so waiting at the pickup isn't a stop. A 5 s timer re-checks it, because a standing car may send no GPS fixes.

## Alternatives

- **Window (1).** Simpler, but it loses the deviation exactly when it matters most: a car stopped somewhere off the way.
- **Straight-line bearing to the destination.** Real roads bend, so it gives too many false alarms in a city grid.
- **Wait for a route line.** Not available on Pleos Connect today. On a real car with route geometry, the rule could measure distance to the route instead; that's in the proposal roadmap.

## Consequences

- A legitimate route that first leads more than 300 m away for over a minute (a U-turn far ahead, a highway entrance) is reported as a deviation. The reason stays calm ("목적지와 다른 방향으로 450m 이동"), and the contact sees it clear soon after.
- A trip that starts with the passenger already moving away gets no grace period. The first fix is the starting progress, so the first minute of driving away counts.

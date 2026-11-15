# ADR 0018: Arrival time from the navigation, with an estimate for the emulator demo

Date: 2026-11-13 · Status: accepted (owner, D3b) · Amends SPEC trip-core (`DrivingScreen`)

## Context

The driving panel (`design/d2/proto/driving.html`) puts the arrival time as the large numeral, with the remaining distance beside it and a progress bar under it. The app wrapper only used NaviHelper to hand over the destination, so it had no arrival time. The SDK has one: `NaviHelper.getDestinationInfo()` answers through `onDestinationInfo(DestinationInfo)` with `remainDistance` (m) and `remainTime` (s), and `onRouteStarted(RouteStartInfo)` gives the route's total `distance` once. Nothing pushes updates, so the app has to poll.

The emulator's navigation is a mock (`research/findings/spike-navi.md`). It stays at a fixed position, places the destination about 50 m away and never moves, so on the emulator it always answers about 50 m and 1 minute. The demo drive feeds the app its own GPS (강남 → 양재) instead.

## Decision

- **On a car:** `EtaTracker` polls the navigation every 10 s while a trip runs. The numeral is the clock time `now + remainTime`, the detail is `도착 · {remainDistance}`, and the bar fill is `1 − remainDistance / route distance`. An answer older than 60 s is dropped rather than shown.
- **On the emulator (demo):** the same tracker estimates instead of asking the mock. The remaining distance is the straight line from the app's last GPS fix to the destination, the time is that distance at 30 km/h, and the total is the distance at the trip's first fix. It runs only when `SeatRule.isEmulator` is true (the "ranchu" hardware), which a car never is.
- Caution marks on the bar are placed from the logged events' own positions (straight line to the destination, over the same total). With no navigation answer yet, the numeral shows `--:--` and the bar is empty, so the glance zone doesn't move.

## Consequences

- On a car, the marks use straight-line distances over the route's road distance, so they sit slightly early on the bar. Good enough for "where it happened, roughly"; the contact page keeps the exact times.
- The demo's arrival time is an estimate. It never leaves the emulator, but it isn't measured either, so it stays out of the report and the contact page.
- Two more listener callbacks and a poll in `PleosVehicleSignals`; the remaining time's unit (seconds) is checked on the emulator, where the route start reports `duration=60` for 50 m.

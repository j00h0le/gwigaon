# ADR 0001: Flip seat occupancy on the Pleos emulator

Date: 2026-11-10 · Status: accepted (project owner)

## Context

On Pleos Connect Emulator 2.1.0 the Vehicle SDK's `SeatOccupancyListener` reports the opposite of what the Virtual Console sets: Console "occupied" arrives as `false`, "empty" as `true`. The seat mapping itself is right (passenger = `ROW_1_RIGHT`, rear left/right = `ROW_2_LEFT/RIGHT`). The Console has no rear-center seat, so `ROW_2_CENTER` always arrives as `true`, which would keep "a passenger is in the car" true forever and break the safe-arrival check (T14). Checked in T5 by toggling one seat at a time (`research/findings/spike-vehicle.md`).

## Decision

`PleosVehicleSignals` flips seat values when it runs on the emulator (`SeatRule.isEmulator`: hardware `ranchu` or product `*_avd*`), through `SeatRule.normalize`. On other hardware the values pass through unchanged.

## Alternatives

- Send inverted values from `scripts/demo-route.sh`: keeps app code literal, but the Console UI then shows the wrong seats on camera, and `ROW_2_CENTER` still needs special-casing.
- Flip unconditionally: simplest, since every target is the emulator, but wrong on a real car.

## Consequences

One place to change if Pleos fixes the emulator. If a real car ever reported inverted values, detection would not catch it; the T14 on-car check would.

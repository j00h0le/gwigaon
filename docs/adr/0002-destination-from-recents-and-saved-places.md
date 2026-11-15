# ADR 0002: Destination from recents and saved places, not search

Date: 2026-11-11 · Status: accepted (project owner)

## Context

SPEC trip-core said the passenger enters the destination by "search via NaviHelper or a recent". Building T9 showed that search isn't available:
- NaviHelper 3.0.5 has no place-search API, only `requestRoute` with coordinates and the route/destination queries (T3, `research/findings/spike-navi.md`).
- The Pleos Connect Emulator has no Google services and no geocoder backend (`dumpsys location` shows none), so typed text can't be turned into coordinates.
- Route monitoring needs coordinates, because it measures our own GPS distance to the destination.

## Decision

The setup screen offers destinations as chips: recent destinations from past trips (Room), plus a short built-in list of saved places with coordinates (`SavedPlaces`: 양재역 and 강남역, which match `scripts/demo-route.sh`, plus 서울역 and 판교역). The chosen place is sent to the car's navigation with `requestRoute`, as before.

## Alternatives

- **Also import the destination already set in the Pleos navigation** (`getDestinationInfo`). Possible later, but the emulator's navigation is a mock fixed at 수정구, so on the demo it would not match the GPS route.
- **Pick a point on a map.** Needs a map library, and this image has no Google Maps. That is a new dependency and too much work before 11-15.

## Consequences

- The demo works offline and deterministically.
- A real passenger can't yet reach a new address on the first trip. That gap goes in the proposal's roadmap: navi-destination import on real cars, and Pleos POI search if the SDK adds it.
- Saved places are fixed in code for now; letting the passenger edit them ("집", "회사") would need a geocoder or a map pick.

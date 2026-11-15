# ADR 0003: Main layout is a half panel beside the navigation

Date: 2026-11-11 · Status: accepted (project owner)

## Context

SPEC required every screen to fit down to a 628 dp portrait window, but never said how the app is meant to be used. Every emulator check up to Checkpoint B had the app filling the whole app area (1682 dp, "landscape" in Pleos terms). The owner expects the app to run next to the car's navigation during a trip.

Pleos Connect's screen model ([App structure](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), read 2026-11-11):
- The app area holds one or two apps. **Portrait** = one window; **landscape** = one app spanning both.
- Without `screenOrientation` an Activity runs portrait in one window; `landscape` lets it span both.
- A newly launched app takes the orientation of what is showing: with a landscape app on screen (the navigation is one), the new app opens landscape too. The user splits the area with the window handle (⋮) or gestures.
- Portrait should carry the core functions; landscape may add extra information.

On the emulator (2026-11-11), dragging the handle from the right edge put 귀가온 in the right window (about 840 dp) next to the navigation in the left one. Setup, the parked trip view and the driving screen all fit, and gear D switched screens there.

## Decision

- The main layout is the **portrait half panel next to the navigation**: the driver follows the route on the left and glances at 귀가온 on the right. The demo is recorded this way.
- `screenOrientation` stays unset (portrait, one window). Landscape (both windows) still works; it shows the same content, with setup in two columns.
- The driving screen is designed for the panel: status line at Headline size (48) **pinned to the top** so the glance always lands in the same place, destination under it, and the lower part kept free for the SOS button (T11) and the arrival card (T13).
- Every screen keeps fitting 628 dp (Connect-L half panel) through 1700 dp.

## Alternatives

- **Landscape only (`screenOrientation="landscape"`).** More room, but it would hide the navigation during a trip, which is the opposite of what a driver needs.
- **Leave the size open.** That is how it was; it left the design aimed at a wide panel that won't be used much.

## Consequences

- Getting into the half panel is the user's step (window handle), because a new app follows the orientation on screen. The demo script and the README say so.
- The right window is farthest from the driver and closest to the front passenger. That suits on-screen SOS, which either person can press.
- The destination line repeats what the navigation shows, but it stays: it confirms which trip is being monitored, and our destination can differ from the navigation's (the emulator's navigation is a mock).

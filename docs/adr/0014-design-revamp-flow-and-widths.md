# ADR 0014: Design revamp — who owns which screen, and at what width

Date: 2026-11-12 · Status: accepted (owner, D1) · Amends ADR 0003

## Context

D1 planned the visual and UX revamp (impeccable skill, `/design` canvas, then Compose in D3). Two facts about a 대리운전 trip had not been written down, and the owner asked for setup at full width, which ADR 0003 did not plan for.

- In Korea the car owner always rides in the **back seat** during 대리운전. The screen is out of their reach for the whole trip.
- Passengers attacking the driver while the car is moving is a common pattern in 대리운전 incidents. The driver can't defend themselves safely mid-drive.

## Decision

**Ownership.**
- **Passenger (car owner): setup only.** Setup is the passenger's yes: destination, contact, arrival notes, PIN, conversation-detection opt-in. It ends with an explicit handoff to the driver.
- **Driver (대리기사): every screen after setup.** That covers the briefing, the driving panel, the arrival card, on-screen SOS, the "괜찮으신가요?" check-in, the safe-arrival check, the PIN prompts and the end of the trip.
- **The check-in is the driver's protection.** It asks the driver whether they are safe after conflict cues, and its SOS answer is how a driver who is being attacked asks for help without taking their eyes off the road for long.
- **The PIN is asked of the passenger, by the driver.** At the safe-arrival check and the end of the trip, the driver asks the passenger for their PIN and enters it. Being able to say the PIN is proof that the passenger is awake and responsive, so the prompt is worded for the driver ("승객에게 PIN을 물어보세요"), not the passenger.
- **The journey report is a saved log** for three readers: the contact page, the driver at the end of the trip, and the passenger later.

**Widths.**
- **Setup is designed for the full app area (1700 × 1184 dp)** as a multi-step flow. It keeps a single-column fallback that works at 840 and 628 dp, because a new app can open in portrait.
- **The driver's screens stay on the half panel (840 dp) beside the navigation**, as ADR 0003 decided, and still fit 628 dp. They stay in the **right** window (owner, 2026-11-12), even though the front-passenger reason in ADR 0003 no longer applies.

## Alternatives

- **Setup in the half panel only (ADR 0003 as written).** Rejected: setup happens parked, before the trip, often while the navigation isn't yet needed, and the owner wants it to feel like onboarding, not a long form.
- **The passenger types the PIN.** Rejected: they're in the back seat, and asking them out loud is itself the responsiveness check.

## Consequences

- SPEC's users section stays true. The trip-core and arrival texts aimed at the passenger get reworded for the driver in D2/D3, with the owner's sign-off on any factual change.
- D3 adds a setup layout for wide windows. The 628 dp check stays in the verification list.

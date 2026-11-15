# ADR 0006: The PIN lockout escalates

Date: 2026-11-11 · Status: accepted (Q3 security pass; the owner reviews it at Checkpoint C)

## Context

The passenger's 4-digit PIN guards ending the trip, closing the safe-arrival check and (later) turning monitoring off. The person most likely to guess it is the driver, who sits next to the screen for the whole trip. SPEC said "5 wrong tries → 30 s lockout", and the count started over after each lockout. That allows 5 guesses every 30 s: all 10 000 PINs in about 17 hours, half of them in about 8. A long night trip or a parked car left with the driver gets close to that.

## Decision

Wrong tries keep counting across lockouts and reset only on the correct PIN. Every 5th wrong try locks: 30 s the first time, then twice as long each time (60 s, 2, 4, 8 min), capped at 15 min. The first lockout is unchanged, so a passenger who fumbles still waits only 30 s. The count was already stored with the trip, so no schema change.

Trying every PIN now takes over 400 hours; reaching half of them takes over 200.

## Alternatives

- **Longer fixed lockout (e.g. 5 min).** Punishes a passenger who mistyped five times as hard as an attacker.
- **Longer PIN.** Better against guessing, but SPEC and the setup screen are built around 4 digits that a tired passenger remembers; reconsider after the demo.
- **Log each lockout as an event the contact sees.** A good tamper signal, but a new event type touches the cloud schema and contact page; left as an open question for the owner.

## Consequences

- The lockout runs on the wall clock (it must survive a restart). Someone who can move the car's clock forward skips the waiting; AAOS normally keeps network time and blocks settings while driving. Accepted for now.
- After many wrong tries the real passenger may wait up to 15 min. The trip keeps monitoring meanwhile, and SOS never needs the PIN.

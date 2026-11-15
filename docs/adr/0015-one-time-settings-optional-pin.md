# ADR 0015: Settings are a one-time preference, the end-of-trip PIN is optional

Date: 2026-11-12 · Status: accepted (owner, D2) · Amends ADR 0014 and SPEC trip-core (setup, PIN); SPEC updated 2026-11-12

## Context

D2 designed setup as a per-trip passenger flow ending in 운행 시작 (brief S0–S7). Reviewing the driver's side, the owner found that model wrong: the car owner should not fill in a form every time they call a 대리기사, and not every owner wants the trip locked behind a PIN.

## Decision

**설정 is a one-time preference, editable later. It holds five things:**
1. **집 주소 등록** — the home address; every trip goes there by default.
2. **보호자 등록** — one or more trusted contacts.
3. **대화 감지** — the on/off toggle, as the app has it now.
4. **도착 안내** — arrival notes for the driver (주차 위치, 출입구, 차 키).
5. **PIN으로 운행 종료** (optional) — when on, the owner sets a PIN, and trip monitoring cannot end until the owner is awake and has told the driver the PIN. For owners who want certainty that the trip only ends once they themselves have spoken.

**The trip starts on the driver's side.** The driver's start screen shows the destination filled in from 집 주소 and a **운행 시작** button. 운행 시작 starts the trip and sends the destination to the Pleos navigation app (NaviHelper `requestRoute`). The passenger no longer goes through a setup flow before each trip.

**Navigation beside 귀가온.** After 운행 시작 the navigation should open in the other half window, beside 귀가온, not over it. Checked on the emulator (2026-11-12, `research/findings/spike-split-window.md`): apps can't pick a window; the home screen opens navigation in the left half and moves 귀가온 to the right half only if 귀가온 is already in a half window when 운행 시작 is pressed. If 귀가온 fills the app area, navigation covers it. The driver start screen has to handle that case (design, D2).

**Check-in wording.** The check-in card may name what was detected ("폭언 감지", "다툼 감지"). The owner judges this won't escalate a passenger. This replaces the D2 suggestion to keep the card neutral.

**대화 감지 consent.** A driver-side opt-in step is out of scope at this stage. The passenger's 대화 감지 toggle in 설정 stays as it is.

## Alternatives

- **Per-trip setup (D2 brief S0–S7).** Rejected: repeats the same answers every trip, and puts a form between a tired owner and the ride.
- **PIN always required (SPEC today, ADR 0006).** Rejected as the only mode: kept as an opt-in for owners who want it.

## Consequences

- **SPEC trip-core** must change (owner sign-off): "passenger starts a trip by choosing a destination, contact and PIN" becomes settings + driver start; "ending the trip, changing settings, or turning off monitoring needs the PIN" holds only when PIN으로 운행 종료 is on.
- **Without a PIN**, the safe-arrival check closes by seat empty or the driver's "승객 반응 있음" only; the "승객에게 PIN을 물어보세요" path appears only when the PIN is on. Decided (owner, 2026-11-12): without a PIN, 운행 종료 follows the arrival rule (plain once arriving or after the check; elsewhere a hold, logged `ended_early` and shown to the 보호자), and 설정 opens only parked with no trip running, its changes logged as `SETTINGS_CHANGED` at the next trip start.
- **Contact page:** needs a state for "trip not started yet" if a contact can see anything before 운행 시작; and with several contacts, each gets the link.
- **Destination:** only home by default. Decided (owner, 2026-11-12): home only, read-only for the driver; no one-off destination.
- **Trip start without the button (owner, 2026-11-12, after the start-screen critique):** shifting out of P with 설정 complete starts the trip like 운행 시작 (`TRIP_STARTED {source: gear}`). A driver who never presses the button is still covered; the button stays for starting early and says "기어를 P에서 빼도 시작돼요".
- **D2 brief:** setup screens S0–S7 become the 설정 screens (first run plus edit), and the driver start screen replaces D0 + D1.

## Amendment (owner, 2026-11-13): home screen

The app opens on a home screen with two doors, 설정 for the car owner and 운행 시작 for the driver (`design/d2/proto/home.html`). Until 설정 is complete, only 설정 is open. Gear out of P on the home screen starts the trip, the same as on the start screen, so a driver who drives off from home is still covered.

## Amendment (owner, 2026-11-13): reason for a plain end without a PIN

A plain 운행 종료 without a PIN (trip arriving, or the safe-arrival check closed) logs `TRIP_ENDED {reason: ended_at_arrival}`, a new wire value, rather than reusing `ended_by_passenger`: the driver ends it, and nobody confirmed the passenger. The contact page shows it as a neutral "도착 후 운행 종료"; `ended_early` stays the warning. Payloads are not constrained in the database, so no migration is needed. While the check is open, a trip without a PIN can't end: it waits for seat empty or 승객 반응 있음 (T27).

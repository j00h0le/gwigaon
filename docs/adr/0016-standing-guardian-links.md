# ADR 0016: Each 보호자 keeps a standing link

Date: 2026-11-13 · Status: superseded by ADR 0019 (2026-11-14); was accepted (owner, D2 설정 review) · Amends ADR 0015 (보호자 등록), ADR 0011 (link window), SPEC cloud-sync and contact-page

## Context

ADR 0015 made 설정 a one-time preference with one or more 보호자, but the share link is still made per trip (`trips.share_token`, a QR on the parked trip view). Two questions were left open:
1. How does each 보호자 get the link when the passenger is tired or asleep at 운행 시작? Today the passenger would have to scan or send a new QR every trip.
2. What does the contact page show before 운행 시작?

The D2 설정 prototype (`design/d2/proto/settings.html`, step 2) proposed an answer, and the owner accepted it.

## Decision

**One link per 보호자, made once in 설정.**
- Registering a 보호자 creates a contact link on the car: a 128-bit+ url-safe token, shown as a QR (and a share action) in that 보호자's row. The 보호자 scans it once with their phone. From then on, every trip the owner starts appears on that link with nothing more to do at trip time.
- 설정 shows each 보호자's state: **연결 전** (nobody has opened the link yet) or **연결됨** (opened, with when).
- Removing a 보호자 revokes the link at once. **QR 다시 만들기** replaces the token; phones that joined with the old one lose access.

**What a contact link can see.** Only trips of the same owner that started after the 보호자 was registered, while the link isn't revoked, and only while the trip runs or up to 24 h after it ends or goes silent (the same window as ADR 0011). Older trips aren't readable through the link even though they stay stored for 30 days.

**Contact page states.** **운행 전** when no visible trip is running or ended within 24 h ("아직 운행이 없어요. 운행이 시작되면 여기에 바로 떠요."), the live trip as today, the ended trip with its report, and **연결이 끊겼어요** for a revoked or replaced link.

**Per-trip links stay.** `trips.share_token` and `join_trip` are kept unchanged for a one-off share, so nothing that works today breaks.

## Alternatives

- **Per-trip QR only (today).** Rejected: the passenger repeats a step every trip at the moment they are least able to, and a 보호자 who isn't in the car never gets it.
- **Send the link by SMS or KakaoTalk at trip start.** Rejected for now: needs phone numbers (PII the SPEC avoids, "no PII beyond labels") and a messaging provider, and the car may have no share target.
- **A 보호자 account or app.** Rejected: SPEC promises "no app, no account" for contacts.

## Consequences

- **Data model (new migration, RLS in the same file):** `contacts(id uuid pk, owner → auth.users, label text, link_token text unique, created_at, revoked_at)` and `contact_viewers(contact_id → contacts, viewer → auth.users, joined_at, pk(contact_id, viewer))`. RPC `join_contact(token)` (security definer, fixed search_path) adds the caller and returns the contact id and owner; a revoked or unknown token returns nothing. A link admits at most 3 phones; a 4th answers like an unknown token. Trips, events and reports gain a select policy for contact viewers under the visibility rule above. Owners read their own contacts and viewer counts (for 연결됨). Clients never insert viewers directly.
- **Security trade-off:** a standing token is a long-lived bearer secret. A leaked link shows future trips until the owner removes or replaces it, against today's 24 h per-trip window. Mitigations: token in the URL fragment only, revoke and replace in 설정, the 3-phone cap, and the 24 h window on each trip. The next security pass covers this.
- **Sync:** contact rows go up through the existing queue. A QR works only once its row is uploaded, so 설정 shows **연결 준비 중** until then (unlike trip links, which work before the first upload).
- **App:** QR and share move from the parked trip view to 설정 → 보호자; the trip view may keep the per-trip QR as a secondary share. `SETTINGS_CHANGED {fields: [contacts]}` covers add, remove and replace.
- **Contact page:** accepts `#c=<link_token>` besides `#t=<share_token>`, follows the newest visible trip of that owner, and switches to a new trip when one starts (Realtime on `trips`, filtered by owner).
- **Out of scope:** notifying a 보호자 (push or SMS) when a trip starts; the page shows it when opened.

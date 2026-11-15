# ADR 0019: One standing link per account, shown as a QR

Date: 2026-11-14 · Status: accepted (owner, D3f feedback) · Supersedes ADR 0016 · Amends ADR 0015 (보호자 등록), SPEC cloud-sync, app 설정 and contact-page

## Context

ADR 0016 gave each 보호자 their own standing link, made per 보호자 row in 설정. The owner designed the 보호자 list when the plan was to text each person. With the contact page as it is now, nobody needs to be named: one link that follows the car's trips is enough, and the per-trip QR on the parked trip view (S05-1) becomes unnecessary.

## Decision

- **One link per account.** 설정 makes a standing link once (128-bit+ url-safe token, made on the car). It is shown as a QR with the instruction to photograph it and send it to whoever should follow the trips (partner, parent…). Everyone who opens it sees every later trip of this account.
- **Where the QR shows:** the 비상 연락처 step of first-run 설정, the 설정 summary (right panel at full width), and a small "QR 확인하기" link on home after 설정 is done. **QR 다시 만들기** replaces the token; phones that joined with the old one lose access.
- **No 보호자 names.** 설정 no longer collects labels; the UI word is 비상 연락처. 설정 is complete once 주소 is set and the 비상 연락처 (QR) step has been passed once (`Settings.linkShown`; nothing can check that the QR was sent). 설정 saved with 보호자 names before this ADR stays complete. 설정 shows 연결 전 / 연결됨 (someone opened it, with when) and 연결 준비 중 until the link is uploaded.
- **Visibility** is ADR 0016's rule with the account in place of the contact: trips that started after the link was made, while it isn't replaced, running or up to 24 h after they end or go silent. At most 5 phones per link; a 6th is answered like an unknown token.
- **The per-trip QR leaves the trip view.** `trips.share_token` and `join_trip` stay in the database for now (nothing that works breaks), but the app no longer shows a per-trip QR.

## Alternatives

- **ADR 0016 (one link per named 보호자).** Rejected: more setup for no gain once nothing is sent to named people; per-person revoke is the only loss, and QR 다시 만들기 covers a leak.
- **Keep the per-trip QR as well.** Rejected by the owner: it asks the passenger to act on every trip.

## Consequences

- **Data model:** `owner_links(owner uuid pk → auth.users, link_token text unique, created_at, replaced_at)` (or a new row per token with `revoked_at`), `owner_link_viewers(link_token → owner_links, viewer → auth.users, joined_at)`. RPC `join_owner_link(token)` (security definer, fixed search_path). Select policies for viewers on trips, events and reports under the rule above. RLS and pgTAP in the same task. Replaces the `contacts` / `contact_viewers` design (never built).
- **Security trade-off** (same as ADR 0016): a long-lived bearer secret; leaked = future trips visible until QR 다시 만들기. Token in the URL fragment only, the phone cap, the 24 h window per trip. A security pass covers it before remote deploy.
- **Events:** `SETTINGS_CHANGED {fields: [contacts]}` now means the link was replaced.
- **Contact page:** `#c=<link_token>` follows the newest visible trip and switches when a new one starts; states 운행 전, live, ended, 연결이 끊겼어요. `#t=` keeps working.
- **Plan:** T28–T30 and Q8 (cuttable) become D3f batch 5, not cuttable.

## Built (D3f batch 5, 2026-11-14)

- `owner_links` holds one row per token with `revoked_at` (the second option above); `set_owner_link(token, created_at)` registers or replaces it, made on the car so the QR shows at once, even offline. `join_owner_link(token)` returns the owner, or null.
- App: `OwnerLinkRepository` (Room `owner_link`, schema 6) uploads the link whenever 설정 shows it and at app start, and polls 연결됨 every 5 s while it is on screen. Home's QR 확인하기 opens only the 비상 연락처 step, behind the same gate as 설정 (PIN when on).
- Contact page: `#c=` → 운행 전 / the newest visible trip (switching on a new trip's INSERT) / 연결이 끊겼어요 (checked every 30 s).
- Not built: `SETTINGS_CHANGED {contacts}` on QR 다시 만들기 (follow-up). The security pass is ADR 0022.

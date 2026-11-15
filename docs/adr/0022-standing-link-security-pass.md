# ADR 0022: Security pass on the standing contact link (D3f Q4)

Date: 2026-11-14 · Status: accepted (pending owner review) · Covers ADR 0019 · Same form as ADR 0011 (Q4 cloud) and ADR 0013 (Q7 audio)

## Scope

`supabase/migrations/20261114120000_owner_links.sql` (tables, `set_owner_link`, `join_owner_link`, the widened `private.is_trip_viewer`), the app's `OwnerLinkRepository` / `SupabaseClient` link calls / Room `owner_link`, the QR on 설정 and behind home's QR 확인하기, and the contact page's `#c=` flow.

## Threat model

- **Asset:** the owner's future trips: route, stops, SOS, arrival. The link token is a long-lived bearer secret for them.
- **Trust boundaries:** the token in a QR and URL fragment (anyone who sees it); the two RPCs (any signed-in anonymous user); RLS on trips, trip_events and trip_reports; Realtime (same policies); the car screen (driver and passenger can see it).

## Checked and holding

| Threat | Control | Evidence |
|---|---|---|
| Guessing a token | 18 random bytes (144 bits), url-safe; the DB rejects anything under 22 chars | `ShareToken`, check constraint, pgTAP "malformed token is refused" |
| Token leaking via servers or logs | Only in the URL fragment (`#c=`), never sent by browsers; `OwnerLink.toString()` hides it; `HttpRequest.toString()` drops the query string; the car reads its viewers without the token in the URL and picks its link from the answer (T22, Checkpoint F review) | `OwnerLinkTest.theUrlCarriesTheTokenOnlyInTheFragment…` |
| Writing the tables directly | Clients get select only; inserts go through the two security-definer functions, which act only for `auth.uid()` with `search_path = ''` | pgTAP "clients cannot insert links / add themselves as viewers" |
| Claiming someone else's token | `set_owner_link` returns false for a token owned by someone else or revoked | pgTAP "nobody else can claim the owner's token" |
| Telling tokens apart (unknown / revoked / full) | `join_owner_link` answers null for all three; the page shows one "연결이 끊겼어요" text | pgTAP; `web/contact/index.html#revoked` |
| Too many phones | Cap of 5 per link, serialized by an advisory lock | pgTAP "a 6th phone is answered like an unknown link" |
| A stranger reading trips, viewers or links | RLS: viewers see only trips started after the link was made, while it isn't replaced, up to 24 h after they end or go silent; the owner sees their own viewers | pgTAP (25 checks), all 85 db tests pass |
| Old trips through a new link | `t.started_at >= l.created_at`; the car's `created_at` is clamped to at most 7 days back (the owner's own data either way) | migration |
| Replacing the QR | Revokes the old token in the same transaction; viewers on it lose every trip at once; the page notices on its next 30 s resync | pgTAP "phones on the old link lose access at once" |
| XSS on the contact page | All text goes through `textContent`; no `innerHTML` sinks | grep |
| Token from the query string | `linkFromHash` / `tokenFromHash` read only a real `#` fragment | `state_test.js` |
| Service role key | Not used: the app and the page use the publishable key and the anonymous user's JWT | unchanged from ADR 0011 |

## Accepted risks (owner to confirm)

1. **Anyone in the car with 설정 open can photograph the QR** (driver included), and so follow the owner's later trips. 설정 opens only parked with no trip running; with PIN on, both 설정 and QR 확인하기 need the PIN (UI test `withThePinOnTheHomeQrNeedsThePinToo`). With PIN off, anyone who can open 설정 could already change everything. Mitigations: 설정 shows "연결됨 · 휴대폰 N대", so a phone the owner didn't expect is visible; QR 다시 만들기 cuts it off; the cap is 5. The design (ADR 0019) puts the QR on home on purpose.
2. **A link viewer can read `trips.share_token`** (RLS is per row, and the column is still selectable) and call `join_trip` with it. That gives a per-trip viewer row for that one trip, which outlives a QR replacement within the per-trip window (ADR 0011: up to 24 h after the trip). The viewer could see that trip anyway. Removing it needs column grants on `trips`, and Realtime has to keep working. Follow-up when `join_trip` is retired (ADR 0019 keeps it for `#t=` links for now).
3. **No rate limit on `set_owner_link`.** One signed-in anonymous user can create many revoked rows. There is one active link per owner, and rows are tiny. The same platform-level auth rate limits apply as in ADR 0011.
4. **Replacing a link isn't logged as `SETTINGS_CHANGED {contacts}` yet** (ADR 0019 Consequences). The trip log never showed it to anyone; this is a follow-up, not a control.

## Remote

Applied to the remote project 2026-11-14 (owner OK; connector `apply_migration`, name `owner_links`). Security advisors after apply, all by design and the same kinds ADR 0011 accepted:
- WARN `authenticated_security_definer_function_executable`: `set_owner_link`, `join_owner_link` (plus the existing `join_trip`). Signed-in anonymous users must call them; both act only for `auth.uid()` with a fixed `search_path`.
- WARN `auth_allow_anonymous_sign_ins` on `owner_links` and `owner_link_viewers` (plus the existing trip tables and pg_cron's). Every user is anonymous by design (ADR 0010); the policies scope rows to `auth.uid()`.
- INFO `rls_enabled_no_policy` on `private.summary_attempts` (existing, ADR 0011).
Performance: 2 INFO unused indexes, both new (`owner_link_viewers_viewer_idx`, `trips_owner_started_idx`); nothing has used them yet.

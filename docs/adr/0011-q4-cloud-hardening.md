# ADR 0011: Q4 cloud hardening: AI cost bound, link expiry, retention, accepted advisor warnings

Date: 2026-11-12 · Status: accepted (Q4)

## Context

The Q4 security pass covered anonymous auth, RLS, `join_trip`, the public contact page, and the Edge Function with its Claude key. The remote project showed three security advisor warnings, which ADR 0010 handed to Q4 to confirm or fix. The pass also turned up three gaps:

- **Claude cost abuse.** Anyone holding the publishable key (it ships in every APK and on the contact page) can sign in anonymously, create a trip, end it and call `summarize-trip`. When the model's output is rejected, nothing is stored, so the same trip could be retried without limit, and event count and `payload` size had no cap, so one call could carry a very large prompt.
- **Share links never expired.** A link that leaked weeks later still admitted a new viewer to the whole route.
- **No retention.** Trips and their location history were kept forever.

## Decision

Migration `20261112090000_q4_hardening.sql`, Edge Function change, tests in `supabase/tests/q4_hardening_test.sql` and `index_test.ts`:

- **AI cost bound.** `public.claim_summary_attempt(trip, owner)` (security invoker, execute for `service_role` only, per-owner advisory lock) records attempts in `private.summary_attempts`: 3 per trip, 10 per owner in any 24 h. The function claims one before every Claude call, so failures count too, and answers 429 `too_many_attempts` once the budget is used up. A prompt over 40 000 characters is refused with 413 `trip_too_large` before any claim. A cached report costs nothing. The database caps `payload` at 2 KB and a trip at 1000 events (a real trip has tens).
- **Link expiry (owner: 24 h).** `join_trip` admits new contacts while the trip runs and until 24 h after it ends (`coalesce(ended_at, last_seen_at)`). After that it returns null, the same answer as an unknown token. Contacts who joined in time keep access. The contact page's invalid-link text now says links expire a day after the trip.
- **Retention (owner: 30 days).** `private.purge_expired_trips()` deletes trips whose `coalesce(ended_at, last_seen_at)` is over 30 days old. Events, viewers, reports and attempts go with them by cascade. `pg_cron` job `purge-expired-trips` runs it daily at 03:17 KST.

Advisor warnings, accepted with reasons rather than silenced:

- **0029 `join_trip` is SECURITY DEFINER and executable by `authenticated`.** Intended. The contact page can't see the trip before joining, so the RPC must read past RLS. It only acts for `auth.uid()`, needs a token of 128 bits or more matching the format, refuses `anon`, and fixes `search_path`. Moving the body into `private` behind an invoker wrapper would clear the lint while leaving exactly the same callable surface, so we didn't.
- **0012 RLS policies apply to anonymous users** (all four tables). Intended. The car and the contact are both anonymous users by design (no accounts, SPEC). Every policy checks owner or `trip_viewers` membership, so being anonymous grants nothing by itself.
- **Leaked-password protection off.** The app uses no passwords. Email/password sign-up stays enabled on the remote project (platform default, owner's choice). An email account can do nothing an anonymous one can't.

Advisor items that appeared with this migration (remote, 2026-11-12), also accepted:

- **0012 on `cron.job` and `cron.job_run_details`.** These are pg_cron's own row policies. `anon` and `authenticated` have no usage on the `cron` schema, and the Data API doesn't expose it, so the policies can't be reached.
- **0008 (INFO) `private.summary_attempts` has RLS on and no policy.** Intended: no client may read or write it, and only `service_role` (which bypasses RLS) uses it, through `claim_summary_attempt`.

## Checkpoint D review (2026-11-12)

The `/review` at Checkpoint D found four more gaps here and one in the secrets gate; migration `20261112100000_checkpoint_d_review.sql`, tests in `checkpoint_d_review_test.sql`:

- **Join window for silent trips.** `status <> 'ended' or …` kept a trip that went silent without ending (app crash, flat battery) joinable until the purge. Now one rule for every status: `coalesce(ended_at, last_seen_at)` within 24 h. A running trip's heartbeat (30 s) keeps it fresh; a car offline for 24 h mid-trip also stops admitting new contacts, which we accept.
- **Project-wide AI ceiling.** The per-owner budget didn't bound the total, since anonymous users are free (about 300 calls an hour per IP). `claim_summary_attempt` now also refuses past 200 attempts in 24 h, under one project-wide advisory lock so the counts are exact. Someone who uses up the ceiling blocks AI summaries for a day; the measured timeline still shows.
- **Event cap and re-sends.** The trigger fired before `ON CONFLICT DO NOTHING`, so at 1000 events a re-sent seq failed with 54000 (HTTP 500) instead of being skipped; parallel inserts could also pass the cap. It now skips existing seqs and locks per trip. The app stops retrying refusals for good (SPEC cloud-sync).
- **Ended before the last events.** The app could send `ended` while TRIP_ENDED was still queued, and the function accepted `status = ended` alone, so a first (cached for good) summary could miss the end. The app now sends `ended` only once the trip's queue is empty, under one lock with the heartbeat, and the function needs a stored TRIP_ENDED. A trip closed because its local log was unreadable never logs TRIP_ENDED and so never gets an AI summary; its timeline still shows.
- Rolled out 2026-11-12 (owner OK): migration applied through the connector as `checkpoint_d_review`, `summarize-trip` redeployed. Remote check with throwaway trips (deleted with their three anonymous users): "ended" without a stored TRIP_ENDED → 409 and no attempt spent; a trip silent for 2 days refuses a new contact; the stored functions carry the re-send skip, the 200 ceiling and the single window. Advisors unchanged.
- **Secrets gate.** gitleaks' default rules caught `sb_secret_…` only in `KEY=value` form. `.gitleaks.toml` adds rules for `sb_secret_…` anywhere and for legacy service-role JWTs, checked on planted keys.

## Consequences

- An abuser can spend at most 10 Claude calls per anonymous user and 200 in total per day (anonymous sign-ins are also rate-limited per IP by Supabase Auth, 30 per hour by default). A monthly spend limit on the Anthropic key would be the real backstop; the owner chose no limit until the demo (2026-11-12), so revisit before the exhibition APK goes public.
- A passenger who retries a failing summary sees a retry button that eventually answers 429. The app already shows the timeline alone with a retry for any non-403/409 status, so this needs no app change.
- Unused anonymous users in `auth.users` are not purged. Deleting the car's user would cascade to trips it still needs, so a safe cleanup (no trips and no sign-in for 30 days) is left for after the competition.
- The contact page keeps its anonymous session in `localStorage` (supabase-js default). A stolen session reads only the trips that viewer joined; the strict CSP, SRI on the CDN script and no `innerHTML` make theft through XSS unlikely.
- Rolled out 2026-11-12: migration applied through the connector as `q4_hardening` (ADR 0010), `summarize-trip` redeployed (CLI, verify_jwt on), contact page republished. Remote check with a throwaway trip (since deleted with its three anonymous users): oversized payload 400, client claim 42501, join 2 min after the end OK, after 2 days null while the early viewer still reads, deployed function 429 with a used-up budget, cron job scheduled.

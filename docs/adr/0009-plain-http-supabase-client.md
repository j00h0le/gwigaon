# ADR 0009: The app talks to Supabase over plain HTTP, not supabase-kt

Date: 2026-11-11 · Status: accepted (T16)

## Context

The plan approved `supabase-kt` (with a Ktor client) for T16. What the car actually needs from Supabase turned out to be small, and all of it was checked against the local stack before writing code:

- Auth: anonymous sign-up (`POST /auth/v1/signup` with `{}`) and refresh (`POST /auth/v1/token?grant_type=refresh_token`).
- PostgREST: insert a trip, insert events, update the trip's status / end / last-seen time. Inserting with `Prefer: resolution=ignore-duplicates` (events with `on_conflict=trip_id,seq`) returns 201 for a resend and stores nothing twice, under RLS.
- No Realtime, no Storage, no reads: the contact page is the only reader.

## Decision

Use a ~150-line `SupabaseClient` over `HttpURLConnection` (`sync/SupabaseClient.kt`, `sync/HttpTransport.kt`) with kotlinx-serialization's `JsonObject` (already a dependency). The session is kept in Room (`cloud_session`, schema 3) next to the queue, so the car stays the owner of its trips across restarts. The only new dependency in T16 is ZXing core 3.5.4 for the QR code (approved with the plan).

## Consequences

- No Ktor / supabase-kt / multiplatform-settings in the APK or in osv-scanner's view, and every request and error path has a JVM unit test (fake transport, plus the transport against the JDK's HTTP server).
- PATCH works on Android's `HttpURLConnection` but not on the JVM's, so the PATCH call is checked on the emulator (T16 run), not in unit tests.
- If the app later needs Realtime or Storage, revisit: that is when `supabase-kt` earns its size.
- The approval for `supabase-kt` stays unused; adding it later still goes through the dependency rule (version catalog, locks, osv).

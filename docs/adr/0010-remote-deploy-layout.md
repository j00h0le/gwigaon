# ADR 0010: Remote deploy: project gwigaon, contact page in its own public repo

Date: 2026-11-11 · Status: accepted (T20)

## Context

T20 puts the cloud track online. Three things went differently from the plan:

- The plan said "publish the contact page on GitHub Pages", but `j00h0le/hyundai` is private, and Pages for a private repo needs a paid GitHub plan. The public submission repo (T24) doesn't exist yet.
- `supabase db push` needs the database password of the new project. Nobody has it: the project was created through the Supabase connector.
- The release build has no cloud settings unless `local.properties` sets them.

## Decision

- **Supabase project `gwigaon`** (ref `femehqwacxcoeevbzvtd`, ap-northeast-2, free plan, $0 confirmed by the owner). Anonymous sign-ins were turned on by the owner in the dashboard; the rest of the auth config is the platform default (no `supabase config push`, which would have sent the local site URL and rate limits too).
- **Migrations applied through the connector**, one per file, in file order, with the file's name. The remote migration history therefore carries the connector's timestamps, not our file versions. Before the first `supabase db push` against this project, link it and run `supabase migration repair` to mark the six files as applied, or keep applying new migrations through the connector.
- **Contact page in its own public repo** `j00h0le/gwigaon-contact`, served at https://j00h0le.github.io/gwigaon-contact/. `scripts/publish-contact.sh` copies only `index.html`, `app.js`, `state.js` and `style.css` and generates `config.js` with the remote URL and publishable key; it refuses any other key type. `web/contact/config.js` in this repo keeps pointing at the local stack.
- **Release settings in `local.properties`** (`gwigaon.release.supabaseUrl / .supabaseKey / .contactPageUrl`), not committed: gitleaks flags the publishable key as `generic-api-key`, and adding a new flagged line or an allowlist needs the owner. The release APK (T23) is built on this machine, so it carries them; a build from a fresh clone stays offline-only until they're set.

## Consequences

- The publishable key is public by design (it ships in every APK and on the contact page); RLS is what protects the data.
- The submission repo (T24) can link to the hosted page as it is; it doesn't need Pages of its own.
- Every contact-page change needs `scripts/publish-contact.sh` after the commit, or the hosted page lags behind.
- Remote security advisors show warnings that the design accepts on purpose and that Q4 must confirm or fix, never silence: `join_trip` is a SECURITY DEFINER RPC that signed-in users call (the contact page's only way in), RLS policies apply to anonymous users (both the car and the contact are anonymous users), and leaked-password protection is off (no passwords exist in this app).

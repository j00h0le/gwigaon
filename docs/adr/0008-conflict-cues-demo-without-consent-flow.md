# ADR 0008: Conflict cues ship as a demo feature without a consent flow

Date: 2026-11-11 · Status: accepted (owner decision at Checkpoint C)

## Context

SPEC conflict-cues was a spike with a go/no-go. The spike passed: a third-party app gets `RECORD_AUDIO` and a `microphone` foreground service on the emulator (research/findings/spike-*.md). The SPEC asked for both passenger and driver to opt in on screen, because cabin audio analysis by a third-party app is a live risk under 통신비밀보호법 제3조 (research/findings/audio-legal-korea.md, medium-low confidence).

## Decision

Go for the 11-15 submission, as a demo: one on/off switch at setup, off by default, a mic indicator while on, and a one-tap off switch. No two-party consent screens, no terms text, no legal handling. Everything else in the SPEC stays: on-device RMS loudness only, no transcript, 30 s in-memory buffer saved only on SOS and never uploaded, cues never alert the contact on their own.

## Consequences

- The demo build is not fit for real riders as is. The proposal and README say production needs both parties' prior consent and notice, a privacy notice and a lawyer's check, and list that on the roadmap.
- T21 is no longer conditional; its consent screen work is dropped.

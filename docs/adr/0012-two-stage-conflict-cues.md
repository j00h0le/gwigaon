# ADR 0012: Conflict cues listen in two stages, with Pleos SpeechToText on the device

Date: 2026-11-12 · Status: accepted (owner, with the SPEC change) · Amends ADR 0008

## Context

ADR 0008 put conflict cues in the submission as a demo: one switch at setup, loudness only, keyword detection a stretch goal. The owner then asked for word detection and proposed listening "10 seconds every minute". A mic that is only open 10 s out of 60 misses short incidents (one shout, a shove) and leaves the 30 s SOS clip empty most of the time, so the design splits listening into two stages.

The owner also asked to set the audio-law question (통신비밀보호법, research/findings/audio-legal-korea.md) aside for the demo. ADR 0008 already dropped the consent screens; this goes one step further and adds speech recognition, which the legal finding rates as riskier than loudness alone.

Research and an emulator spike (research/findings/stt-stage2-pleos.md, kws-stage2-offline.md, spike-stt-stage2.md) showed the Pleos SpeechToText SDK 3.3.1 in `ON_DEVICE` mode: initialises without registration, accepts PCM from the app through `sendAudio` (so our recorder stays the only mic user), recognises Korean live with partials about every 0.4 s, and coexists with our `AudioRecord` and Gleo's hotword capture. With the owner's real voice, 6 of 7 test lines were word-perfect, swear words included; 씨발 was missed with both a real and a synthetic voice.

## Decision

- **Stage 1, always on while the feature is on:** our `AudioRecord` (16 kHz mono) in the trip's foreground service, typed `microphone` as well as `location`. Loudness per 1 s window, and a 30 s in-memory ring buffer written to app-private storage only when SOS fires. Never uploaded.
- **Stage 2, word analysis:** the Pleos SpeechToText SDK, `ON_DEVICE`, ko-KR, fed by stage 1's stream at real time through `sendAudio`. It runs a 10 s sample every 60 s, starts at once on a loudness spike, and keeps streaming while it finds hits, until a calm period passes. The word list is matched on partial and final results; no transcript is stored or uploaded, only which cue fired.
- **Legal:** not handled in the demo, by the owner's decision. ADR 0008's consequence stands and grows: production needs both parties' consent and notice, a privacy notice and a lawyer's check, now covering speech recognition too.

## Alternatives considered

- **Duty-cycle the mic itself (10 s per minute):** misses incidents that fall in the 50 s gap and breaks the SOS clip. Rejected; the duty cycle moved to stage 2, where it saves compute without losing coverage.
- **sherpa-onnx with its Korean streaming model:** offline and open source, but adds about 132 MB to the APK, has no Korean keyword-spotting model and its licence isn't stated. Kept as the fallback; not needed after the real-voice test.
- **Vosk small Korean model (82 MB):** weaker accuracy (WER 28 on its own benchmark). Second fallback.
- **Picovoice Porcupine:** needs an online-validated access key, and free-tier keys reportedly stopped working after 2026-06-30. Rejected.
- **Whisper (whisper.cpp):** not streaming, slow on phone-class CPUs. Rejected.
- **The SDK opening the mic itself (`request()`):** would need a mic hand-over between our recorder and the SDK. Rejected for `sendAudio`.

## Consequences

- One more Pleos SDK in the app (`ai.pleos.playground:SpeechToText:3.3.1`, permission `pleos.car.permission.STT_SERVICE`), counted under 기술 활용성. STT is listed as 준비 중 on the project page; if a later Pleos image refuses `ON_DEVICE` without registration, stage 2 falls back to loudness only.
- Accuracy is measured for one speaker in a quiet room. Overlapping voices, road noise and music are untested; 씨발 is unreliable. The proposal claims distress phrases and most profanity, not every word.
- The first session after start-up takes about 2.5 s, so the engine warms up when the feature is switched on.
- `RECORD_AUDIO` and the `microphone` foreground-service type join the release manifest; T21's security check covers them.
- The submission materials must say plainly that the demo listens to cabin speech on the device without consent screens, and why production can't.

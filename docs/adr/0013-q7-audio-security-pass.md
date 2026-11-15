# ADR 0013: Q7 audio security pass — what was fixed, what is accepted

Date: 2026-11-12 · Status: accepted (owner, with the SPEC change) · Follows ADR 0008, 0011, 0012

## Context

Q7 reviewed the conflict-cues audio path after T21a/T21b: `RECORD_AUDIO`, the `microphone` foreground service, the 30 s ring buffer, the SOS clip, the Pleos SpeechToText SDK, logs and events, and the merged release manifest. Checked on the Pleos Connect emulator (API 34).

## Checked clean

- Merged release manifest: exported are only `MainActivity` (launcher) and AndroidX's `ProfileInstallReceiver` (guarded by `DUMP`). `DebugTripActivity` is debug-only. The STT SDK adds only a `<queries>` entry for `SPEECH_START`, no components.
- Mic opens only while a trip runs with the switch on and `RECORD_AUDIO` granted; turning off, a trip ending or a new trip clears the ring buffer. The permission is re-checked when the recorder opens.
- The microphone FGS type is added only while listening and dropped after; refused (background start, Android 14) it falls back to location only.
- The SOS clip is written only on SOS while listening, to `files/sos-clips` (`drwx------`, excluded from backup and device transfer, `allowBackup=false`), with a checked file name; no code reads it back, uploads or shares it.
- Our code never logs, stores or uploads recognised text: `WordMatcher` returns only a `CueKind`; events and logs carry the cue name only (`stage 2 word cue distress`); dBFS numbers are logged in debug builds only.
- `request()` is never called, so the STT SDK never opens the mic itself; it gets audio only during a stage-2 sample or stream.

## Fixed

1. **Release build was broken.** T21b moved the STT SDK from `debugImplementation` to `implementation` without rewriting the lock state, so `releaseRuntimeClasspath` refused it; no gate builds release. Locks rewritten, and `check-full.sh` now builds `assembleRelease` too.
2. **SOS clips were kept forever.** The legal finding asks for a short retention period. Clips are now deleted 30 days after they were written (`CueConfig.clipRetention`, the same span as the cloud purge in ADR 0011), at every app start. Unit-tested and checked on the emulator.

## Accepted, with reasons

- **Recognised text reaches logcat, outside our code.** Seen on the emulator: the SDK in our process logs every partial and final (`SttViewModel: notifySttChanged 살려주세요, false`, `SttCallback: onUpdated …`) through an unconditional `android.util.Log` wrapper, and the Pleos system service (`[GAAS]OnDeviceSttManagerImpl`, another process) logs the same text. Stripping the SDK's calls would need R8 in release (`isMinifyEnabled` is false) and still leave the system service's copy, so it isn't done for the demo. Logcat is readable only by the system and adb (`READ_LOGS` is not grantable to third-party apps). Production needs a log-free SDK/service from Pleos; it goes on the roadmap next to consent (ADR 0008).
- **`pleos.car.permission.STT_SERVICE` is protection level `normal`** (owned by `ai.pleos.playground.caas`): any app may use the on-device STT service. Platform design, not ours to change; our audio goes only to that service.
- **No settings shortcut after a permanent mic denial.** The setup screen (parked only) starts the trip with cues off and says why (`MIC_DENIED`); a detour to app settings is left to the design revamp.
- **Sticky restart in the background:** the microphone type is refused, the service keeps location only, and Android 14 then gives the recorder silence while the indicator still shows. Fails safe (records less); a functional follow-up, not a privacy risk.

## Consequences

SPEC conflict-cues: "No transcript is stored, logged or uploaded" now names the SDK/system-service logcat exception, and the clip line gains the 30-day retention.

# ADR 0017: Report labels 운행 로그 / 귀가온 리포트, and a short log

Date: 2026-11-13 · Status: accepted (owner, D2 report review) · Amends SPEC journey-report and principles

## Context

The report screen labelled its two parts "측정된 사실" and "AI 해석", and listed every logged event. Reviewing the D8 prototype (`design/d2/proto/report.html`), the owner found the labels technical and the full list too long for the half panel.

## Decision

- The event list is titled **운행 로그**. The AI summary section is titled **귀가온 리포트**, with a small **귀가온 AI** tag above the text, the same shape as the time chips under it and without an icon. It still sits in a dashed outline with the caveat "AI가 쓴 해석이라 틀릴 수 있어요. 판단은 아래 운행 로그로 해 주세요."
- 귀가온 리포트 comes first, 운행 로그 under it.
- **운행 로그 on the report screen shows only:** 운행 시작 (`TRIP_STARTED`), 목적지 도착 (`ARRIVED`), 안전 귀가 (`TRIP_ENDED {safe_arrival}`), and the caution (amber) events: `ROUTE_DEVIATION`, `LONG_STOP`, `SAFE_ARRIVAL_CHECK_OPENED`, `MONITORING_INTERRUPTED`, `PIN_LOCKOUT`, `CHECK_IN_ANSWERED {no_response}`, `TRIP_ENDED {ended_early}`. SOS and its cancel are always shown too, so the log never hides an SOS (owner confirmed, 2026-11-13).
- Not shown on the report: near destination, check-ins answered 괜찮아요, the driver's 승객 반응 있음, cleared events and settings changes. They stay in the stored log, the AI's input and the contact page.

## Consequences

- The AI may cite an event the short log doesn't show; its time chip then has nothing to jump to. The prompt rules don't change (every claim maps to a stored `seq`).
- App copy (`ReportTexts`, `ReportScreen`) and the contact page's "AI 해석" eyebrow change in the Compose port; the contact page keeps the full timeline.

## Amendment (2026-11-14, owner, D3f feedback)

- 운행 로그 comes first; at full width it sits on the left and 귀가온 리포트 on the right.
- 귀가온 리포트 is the heading plus the AI's summary text only: no 귀가온 AI tag, no dashed outline, no time chips. `summarize-trip` returns one plain summary instead of timed lines.
- A long stop reads "N분 동안 정차했어요" in the log.
- The report still shows once when the trip ends, and home gets a 운행 기록 link that reopens the last trip's report.

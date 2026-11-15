# ADR 0021: Spoken arrival notes are written by Claude when 설정 is saved

Date: 2026-11-14 · Status: accepted (owner, D3f feedback) · Amends SPEC arrival (arrival instructions + TTS)

## Context

Near the destination the app reads the passenger's notes (출입문, 주차, 차키) aloud by joining the raw fields. With free text the result reads like a list, not like a person asking. The owner wants a well-spoken request ("출입문은 101동 정문으로 해주시고, 주차는 지하 2층 B구역에 해주세요. 차키는 … 감사합니다.").

## Decision

- When 설정 is saved with arrival notes, the app asks an edge function (`phrase-arrival`, Claude, same key handling as `summarize-trip`) for one polite spoken sentence in the order 출입문, 주차, 차키, and stores it with 설정.
- If the call fails (offline, timeout), or the notes change while offline, the app speaks a fixed template built on the car. The text is never fetched while driving.
- The card on screen still shows the three notes as written; only the speech uses the sentence.

## Consequences

- New edge function with Deno tests (prompt rules: keep every fact, add none, ≤ 2 sentences, polite 해요/하십시오 register, ends with 감사합니다).
- Room 설정 row gains the stored sentence and the notes it was made from (to detect staleness); schema bump.
- Notes go to the Claude API: they are the passenger's own instructions, no names or phone numbers; noted in the privacy line of 설정 → 도착 안내.

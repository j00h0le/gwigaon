// deno test web/contact/
import { assertEquals } from "jsr:@std/assert@1.0.13";
import { deriveView, eventMeta, linkFromHash, mergeEvents, newerTrip, tokenFromHash } from "./state.js";

const NOW = Date.parse("2026-11-10T12:00:00Z");
const trip = (over = {}) => ({ status: "active", last_seen_at: "2026-11-10T11:59:50Z", ...over });
const ev = (seq, type, extra = {}) => ({ seq, type, occurred_at: "2026-11-10T11:50:00Z", lat: 37.5, lng: 127, ...extra });

Deno.test("token only from fragment, must look like a share token", () => {
  assertEquals(tokenFromHash("#t=seedLiveTrip_0123456789abcd"), "seedLiveTrip_0123456789abcd");
  assertEquals(tokenFromHash("#t=short"), null);
  assertEquals(tokenFromHash(""), null);
});

Deno.test("mergeEvents dedupes by seq and sorts", () => {
  const merged = mergeEvents([ev(2, "LONG_STOP"), ev(1, "TRIP_STARTED")], [ev(2, "LONG_STOP"), ev(3, "SOS")]);
  assertEquals(merged.map((e) => e.seq), [1, 2, 3]);
});

Deno.test("normal trip", () => {
  const v = deriveView(trip(), [ev(1, "TRIP_STARTED")], NOW);
  assertEquals(v.status.text, "정상 운행 중");
  assertEquals(v.banners, []);
});

Deno.test("open SOS shows SOS banner; cancelled SOS does not", () => {
  assertEquals(deriveView(trip(), [ev(1, "TRIP_STARTED"), ev(2, "SOS")], NOW).banners[0].kind, "sos");
  assertEquals(deriveView(trip(), [ev(1, "TRIP_STARTED"), ev(2, "SOS"), ev(3, "SOS_CANCELLED")], NOW).banners, []);
});

Deno.test("interrupted after 90 s of silence, not at 80 s, not when ended", () => {
  assertEquals(deriveView(trip({ last_seen_at: "2026-11-10T11:58:40Z" }), [], NOW).status.text, "정상 운행 중");
  assertEquals(deriveView(trip({ last_seen_at: "2026-11-10T11:58:29Z" }), [], NOW).status.text, "모니터링 중단됨");
  assertEquals(deriveView(trip({ status: "ended", last_seen_at: "2026-11-10T08:00:00Z" }), [], NOW).banners, []);
});

Deno.test("MONITORING_INTERRUPTED event shows interrupted until resumed", () => {
  assertEquals(deriveView(trip(), [ev(1, "MONITORING_INTERRUPTED")], NOW).banners[0].kind, "interrupted");
  assertEquals(deriveView(trip(), [ev(1, "MONITORING_INTERRUPTED"), ev(2, "MONITORING_RESUMED")], NOW).banners, []);
});

Deno.test("deviation and long stop status clear with their _CLEARED events", () => {
  assertEquals(deriveView(trip(), [ev(1, "ROUTE_DEVIATION")], NOW).status.text, "경로 이탈 중");
  assertEquals(deriveView(trip(), [ev(1, "ROUTE_DEVIATION"), ev(2, "DEVIATION_CLEARED")], NOW).status.text, "정상 운행 중");
  assertEquals(deriveView(trip(), [ev(1, "LONG_STOP")], NOW).status.text, "장시간 정차 중");
});

Deno.test("safe-arrival check banner, then safe arrival on confirm + end", () => {
  assertEquals(deriveView(trip(), [ev(1, "ARRIVED"), ev(2, "SAFE_ARRIVAL_CHECK_OPENED")], NOW).banners[0].kind, "safe_check");
  const done = deriveView(trip({ status: "ended" }),
    [ev(1, "ARRIVED"), ev(2, "SAFE_ARRIVAL_CHECK_OPENED"), ev(3, "SAFE_ARRIVAL_CONFIRMED"), ev(4, "TRIP_ENDED")], NOW);
  assertEquals(done.status.text, "안전하게 도착했어요");
  assertEquals(done.banners.map((b) => b.kind), ["arrived"]);
});

Deno.test("a driver's confirmation is not a safe arrival until the passenger gets out (ADR 0007)", () => {
  const confirmed = ev(3, "SAFE_ARRIVAL_CONFIRMED", { payload: { closed_by: "driver_confirmed" } });
  const before = [ev(1, "ARRIVED"), ev(2, "SAFE_ARRIVAL_CHECK_OPENED"), confirmed];
  const waiting = deriveView(trip({ status: "arriving" }), before, NOW);
  assertEquals(waiting.status, { text: "기사가 승객 반응 확인 · 하차 대기 중", tone: "warn" });
  assertEquals(waiting.banners, []);
  assertEquals(eventMeta("SAFE_ARRIVAL_CONFIRMED", confirmed.payload), { label: "기사 확인 (하차 전)", tone: "warn" });

  const out = deriveView(trip({ status: "ended" }),
    [...before, ev(4, "TRIP_ENDED", { payload: { reason: "safe_arrival" } })], NOW);
  assertEquals(out.status.text, "안전하게 도착했어요");

  const byPin = deriveView(trip({ status: "ended" }),
    [...before, ev(4, "TRIP_ENDED", { payload: { reason: "ended_by_passenger" } })], NOW);
  assertEquals(byPin.status, { text: "운행 종료", tone: "neutral" });
  assertEquals(eventMeta("SAFE_ARRIVAL_CONFIRMED", { closed_by: "seat_empty" }).label, "안전 하차 확인");
});

Deno.test("location is the latest event with coordinates", () => {
  const v = deriveView(trip(), [ev(1, "TRIP_STARTED", { lat: 1, lng: 2 }), ev(2, "SOS_CANCELLED", { lat: null, lng: null })], NOW);
  assertEquals([v.location.lat, v.location.lng], [1, 2]);
});

Deno.test("PIN lockout shows on the timeline as a warning without a banner", () => {
  assertEquals(eventMeta("PIN_LOCKOUT", { failures: 5, locked_s: 30 }), { label: "PIN 잠김 (여러 번 틀림)", tone: "warn" });
  assertEquals(deriveView(trip(), [ev(1, "TRIP_STARTED"), ev(2, "PIN_LOCKOUT")], NOW).banners, []);
});

Deno.test("check-ins stay on the timeline: answers labelled, no banner, status unchanged", () => {
  assertEquals(eventMeta("CHECK_IN_ANSWERED", { answer: "no_response" }), { label: "상태 확인: 응답 없음", tone: "warn" });
  assertEquals(eventMeta("CHECK_IN_ANSWERED", { answer: "ok" }).label, "상태 확인: 괜찮음");
  assertEquals(eventMeta("CHECK_IN_ANSWERED", { answer: "sos" }).tone, "danger");
  assertEquals(eventMeta("CHECK_IN_ANSWERED", {}).label, "상태 확인 응답");
  assertEquals(eventMeta("CHECK_IN_PROMPTED", { level: 2, cue: "loud" }).label, "상태 확인 요청");
  const events = [
    ev(1, "TRIP_STARTED"),
    { ...ev(2, "CHECK_IN_PROMPTED"), payload: { level: 1, cue: "loud" } },
    { ...ev(3, "CHECK_IN_ANSWERED"), payload: { answer: "no_response" } },
  ];
  const view = deriveView(trip(), events, NOW);
  assertEquals(view.banners, []);
  assertEquals(view.status.text, "정상 운행 중");
});

Deno.test("settings changes show on the timeline as plain text, no banner, status unchanged", () => {
  assertEquals(eventMeta("SETTINGS_CHANGED", { fields: ["home", "pin"] }), { label: "설정 변경: 집 주소, PIN", tone: "neutral" });
  assertEquals(eventMeta("SETTINGS_CHANGED", { fields: ["contacts", "cues", "arrival"] }).label, "설정 변경: 비상 연락처, 대화 감지, 도착 안내");
  assertEquals(eventMeta("SETTINGS_CHANGED", { fields: ["wifi"] }).label, "설정 변경");
  assertEquals(eventMeta("SETTINGS_CHANGED", {}).label, "설정 변경");
  const events = [
    { ...ev(1, "TRIP_STARTED"), payload: { source: "gear" } },
    { ...ev(2, "SETTINGS_CHANGED"), payload: { fields: ["home"] } },
  ];
  const view = deriveView(trip(), events, NOW);
  assertEquals(view.banners, []);
  assertEquals(view.status.text, "정상 운행 중");
  assertEquals(eventMeta("TRIP_STARTED", { source: "gear" }).label, "운행 시작");
});

Deno.test("an early end without a PIN is a warning banner; a plain end at arrival is not", () => {
  const early = { ...ev(2, "TRIP_ENDED"), reason: "목적지 밖에서 운행 종료 · 1.2km 남음", payload: { reason: "ended_early" } };
  const v = deriveView(trip({ status: "ended" }), [ev(1, "TRIP_STARTED"), early], NOW);
  assertEquals(v.banners, [{ kind: "ended_early", event: early }]);
  assertEquals(v.status, { text: "목적지 밖에서 운행 종료", tone: "warn" });
  assertEquals(eventMeta("TRIP_ENDED", { reason: "ended_early" }), { label: "목적지 밖에서 운행 종료", tone: "warn" });

  const plain = { ...ev(3, "TRIP_ENDED"), payload: { reason: "ended_at_arrival" } };
  const p = deriveView(trip({ status: "ended" }), [ev(1, "TRIP_STARTED"), ev(2, "ARRIVED"), plain], NOW);
  assertEquals(p.banners, []);
  assertEquals(p.status, { text: "운행 종료", tone: "neutral" });
  assertEquals(eventMeta("TRIP_ENDED", { reason: "ended_at_arrival" }).label, "도착 후 운행 종료");
  assertEquals(eventMeta("TRIP_ENDED", { reason: "ended_by_passenger" }).label, "운행 종료");
});

Deno.test("standing link from #c=, trip link from #t=, nothing else", () => {
  assertEquals(linkFromHash("#c=ownerLinkToken_0123456789ab"), { kind: "owner", token: "ownerLinkToken_0123456789ab" });
  assertEquals(linkFromHash("#t=seedLiveTrip_0123456789abcd"), { kind: "trip", token: "seedLiveTrip_0123456789abcd" });
  assertEquals(linkFromHash("#c=short"), null);
  assertEquals(linkFromHash("?c=ownerLinkToken_0123456789ab"), null);
  assertEquals(linkFromHash(""), null);
});

Deno.test("a standing link shows the newest trip that started", () => {
  const a = { id: "a", started_at: "2026-11-14T10:00:00Z" };
  const b = { id: "b", started_at: "2026-11-14T12:00:00Z" };
  assertEquals(newerTrip(null, null), null);
  assertEquals(newerTrip(null, a), a);
  assertEquals(newerTrip(a, b), b);
  assertEquals(newerTrip(b, a), b);
  assertEquals(newerTrip(b, null), b);
});

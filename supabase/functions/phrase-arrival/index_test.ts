// deno test supabase/functions/
// phrase-arrival handler tests with mocked auth, budget and Claude.
import { assert, assertEquals } from "jsr:@std/assert@1.0.13";
import { type Deps, handle, type ModelResult } from "./handler.ts";
import { buildUserPrompt, parseNotes, validateSentence } from "./prompt.ts";

const OWNER = "11111111-1111-4111-8111-111111111111";
const NOTES = { entrance: "101동 정문", parking: "지하 2층", key: "승객에게 직접" };
const GOOD = JSON.stringify({
  sentence: "101동 정문으로 들어가 지하 2층에 주차해 주시고, 차 키는 승객에게 직접 전해 주세요. 감사합니다.",
});

function setup(opts: { uid?: string | null; budget?: boolean; model?: () => Promise<ModelResult> } = {}) {
  const calls: { system: string; user: string }[] = [];
  const claims: string[] = [];
  const logs: string[] = [];
  const deps: Deps = {
    authenticate: () => Promise.resolve(opts.uid === undefined ? OWNER : opts.uid),
    claimAttempt: (o) => {
      claims.push(o);
      return Promise.resolve(opts.budget ?? true);
    },
    model: {
      generate(system, user) {
        calls.push({ system, user });
        return opts.model ? opts.model() : Promise.resolve({ text: GOOD, stopReason: "end_turn", model: "claude-opus-5-5" });
      },
    },
    log: (m) => logs.push(m),
  };
  return { deps, calls, claims, logs };
}

const post = (body: unknown) =>
  new Request("http://x/phrase-arrival", { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body) });

Deno.test("returns the sentence and the model", async () => {
  const { deps, calls, claims } = setup();
  const res = await handle(post(NOTES), deps);
  assertEquals(res.status, 200);
  const body = await res.json();
  assert(body.sentence.includes("101동 정문"));
  assertEquals(body.model, "claude-opus-5-5");
  assertEquals(claims, [OWNER]);
  assertEquals(calls[0].user, "출입문: 101동 정문\n주차: 지하 2층\n차 키: 승객에게 직접");
});

Deno.test("no JWT is 401 and nothing is claimed or called", async () => {
  const { deps, calls, claims } = setup({ uid: null });
  assertEquals((await handle(post(NOTES), deps)).status, 401);
  assertEquals(calls.length, 0);
  assertEquals(claims.length, 0);
});

Deno.test("only POST", async () => {
  const { deps } = setup();
  assertEquals((await handle(new Request("http://x", { method: "GET" }), deps)).status, 405);
});

Deno.test("bad notes are 400 before the budget is touched", async () => {
  for (const body of ["not json", { entrance: 3 }, { entrance: "", parking: "", key: "" }, { key: "가".repeat(31) }, { key: "a\nb" }]) {
    const { deps, claims } = setup();
    assertEquals((await handle(post(body), deps)).status, 400, JSON.stringify(body));
    assertEquals(claims.length, 0);
  }
});

Deno.test("an empty budget is 429 and Claude is not called", async () => {
  const { deps, calls } = setup({ budget: false });
  assertEquals((await handle(post(NOTES), deps)).status, 429);
  assertEquals(calls.length, 0);
});

Deno.test("Claude failing, stopping early or rewording a note is 502", async () => {
  const cases: (() => Promise<ModelResult>)[] = [
    () => Promise.reject(new Error("down")),
    () => Promise.resolve({ text: GOOD, stopReason: "max_tokens", model: "m" }),
    () => Promise.resolve({ text: null, stopReason: "refusal", model: "m" }),
    () => Promise.resolve({ text: JSON.stringify({ sentence: "101동 정문, 지하 2층, 키는 승객께. 감사합니다." }), stopReason: "end_turn", model: "m" }),
  ];
  for (const model of cases) {
    const { deps } = setup({ model });
    assertEquals((await handle(post(NOTES), deps)).status, 502);
  }
});

Deno.test("empty notes are marked so the model leaves them out", () => {
  assertEquals(buildUserPrompt(parseNotes({ parking: " 지하 2층 " })), "출입문: (없음)\n주차: 지하 2층\n차 키: (없음)");
});

Deno.test("validation keeps notes verbatim, ends with 감사합니다, bounds length", () => {
  const notes = { entrance: "", parking: "지하 2층", key: "" };
  assertEquals(validateSentence(JSON.stringify({ sentence: "지하 2층에 주차해 주세요.  감사합니다." }), notes), "지하 2층에 주차해 주세요. 감사합니다.");
  const bad = [
    "{}",
    JSON.stringify({ sentence: "지하 2층에 주차해 주세요." }),
    JSON.stringify({ sentence: "지하 이층에 주차해 주세요. 감사합니다." }),
    JSON.stringify({ sentence: "지하 2층 ".repeat(30) + "감사합니다." }),
  ];
  for (const raw of bad) {
    let threw = false;
    try {
      validateSentence(raw, notes);
    } catch {
      threw = true;
    }
    assert(threw, raw);
  }
});

Deno.test("a note typed with repeated spaces is still found verbatim; a dropped space is not", () => {
  // Checkpoint F review R3: the sentence's spaces are collapsed, so the notes' are too.
  const notes = parseNotes({ parking: " 지하  2층 " });
  assertEquals(notes.parking, "지하 2층");
  assertEquals(
    validateSentence(JSON.stringify({ sentence: "지하  2층에 주차해 주세요. 감사합니다." }), notes),
    "지하 2층에 주차해 주세요. 감사합니다.",
  );
  let threw = false;
  try {
    validateSentence(JSON.stringify({ sentence: "지하2층에 주차해 주세요. 감사합니다." }), notes);
  } catch {
    threw = true;
  }
  assert(threw, "a reworded note (space dropped) is rejected");
});

Deno.test("a line break inside a note is still refused", () => {
  let threw = false;
  try {
    parseNotes({ parking: "지하\n2층" });
  } catch {
    threw = true;
  }
  assert(threw);
});

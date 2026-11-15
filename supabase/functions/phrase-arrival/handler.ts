// Request handling for phrase-arrival, with all I/O injected so it can be tested without Supabase or Claude.
import {
  type ArrivalNotes,
  buildUserPrompt,
  InvalidNotesError,
  InvalidSentenceError,
  parseNotes,
  SYSTEM_PROMPT,
  validateSentence,
} from "./prompt.ts";

export interface ModelResult {
  text: string | null;
  stopReason: string | null;
  model: string;
}

export interface PhraseModel {
  generate(system: string, user: string): Promise<ModelResult>;
}

export interface Deps {
  /** The caller's user id from the request's JWT, or null if missing/invalid. */
  authenticate(req: Request): Promise<string | null>;
  /** Records one Claude attempt for this owner; false = budget used up (10 per owner, 300 in all, per 24 h). */
  claimAttempt(owner: string): Promise<boolean>;
  model: PhraseModel;
  log?: (msg: string, extra?: Record<string, unknown>) => void;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

const error = (status: number, code: string) => json(status, { error: code });

export async function handle(req: Request, deps: Deps): Promise<Response> {
  // Notes are never logged: they are the passenger's own words about their home.
  const log = deps.log ?? ((m, e) => console.log(JSON.stringify({ msg: m, ...e })));

  if (req.method !== "POST") return error(405, "method_not_allowed");

  const uid = await deps.authenticate(req);
  if (!uid) return error(401, "unauthorized");

  let notes: ArrivalNotes;
  try {
    notes = parseNotes(await req.json());
  } catch (e) {
    if (e instanceof InvalidNotesError || e instanceof SyntaxError) return error(400, "invalid_notes");
    throw e;
  }

  if (!(await deps.claimAttempt(uid))) {
    log("phrase attempt budget used up", {});
    return error(429, "too_many_attempts");
  }

  let result: ModelResult;
  try {
    result = await deps.model.generate(SYSTEM_PROMPT, buildUserPrompt(notes));
  } catch (e) {
    log("claude call failed", { error: e instanceof Error ? e.name : String(e) });
    return error(502, "model_unavailable");
  }
  if (result.stopReason !== "end_turn" || result.text === null) {
    log("claude stopped early", { stop_reason: result.stopReason });
    return error(502, "model_incomplete");
  }

  try {
    return json(200, { sentence: validateSentence(result.text, notes), model: result.model });
  } catch (e) {
    if (e instanceof InvalidSentenceError) {
      log("rejected model output", { reason: e.message });
      return error(502, "model_output_rejected");
    }
    throw e;
  }
}

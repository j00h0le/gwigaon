// Request handling for summarize-trip, with all I/O injected so it can be tested
// without Supabase or Claude. index.ts wires the real dependencies.
import {
  buildUserPrompt,
  InvalidSummaryError,
  SYSTEM_PROMPT,
  type TripEvent,
  type TripInfo,
  type TripSummary,
  validateSummary,
} from "./prompt.ts";

export interface TripRow extends TripInfo {
  owner: string;
  status: string;
}

export interface ReportRow {
  trip_id: string;
  summary: TripSummary;
  model: string;
}

export interface ModelResult {
  text: string | null;
  stopReason: string | null;
  model: string;
}

/** The only thing the handler needs from Claude. */
export interface SummaryModel {
  generate(system: string, user: string): Promise<ModelResult>;
}

export interface Deps {
  /** Returns the caller's user id from the request's JWT, or null if missing/invalid. */
  authenticate(req: Request): Promise<string | null>;
  getTrip(tripId: string): Promise<TripRow | null>;
  getReport(tripId: string): Promise<ReportRow | null>;
  getEvents(tripId: string): Promise<TripEvent[]>;
  /** Inserts the report; must not overwrite an existing one. */
  insertReport(row: ReportRow): Promise<void>;
  /** Records one Claude attempt for this trip and owner; false = budget used up (ADR 0011). */
  claimAttempt(tripId: string, owner: string): Promise<boolean>;
  model: SummaryModel;
  log?: (msg: string, extra?: Record<string, unknown>) => void;
}

/** A real trip's prompt is a few KB; this bounds the cost of a crafted one (~15k tokens). */
export const MAX_PROMPT_CHARS = 40_000;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

const error = (status: number, code: string) => json(status, { error: code });

export async function handle(req: Request, deps: Deps): Promise<Response> {
  const log = deps.log ?? ((m, e) => console.log(JSON.stringify({ msg: m, ...e })));

  if (req.method !== "POST") return error(405, "method_not_allowed");

  const uid = await deps.authenticate(req);
  if (!uid) return error(401, "unauthorized");

  let tripId: unknown;
  try {
    tripId = (await req.json())?.trip_id;
  } catch {
    return error(400, "invalid_json");
  }
  if (typeof tripId !== "string" || !UUID.test(tripId)) return error(400, "invalid_trip_id");

  const trip = await deps.getTrip(tripId);
  // Same answer for "no such trip" and "not yours": don't reveal which trips exist.
  if (!trip || trip.owner !== uid) return error(403, "forbidden");

  const existing = await deps.getReport(tripId);
  if (existing) return json(200, { ...existing.summary, model: existing.model, cached: true });

  const events = await deps.getEvents(tripId);
  // Only a stored TRIP_ENDED counts: the trip row's status can arrive before the last events, and the first
  // summary is kept for good, so summarizing early would freeze an incomplete log.
  if (!events.some((e) => e.type === "TRIP_ENDED")) return error(409, "trip_not_ended");
  if (events.length === 0) return error(409, "no_events");

  const user = buildUserPrompt(trip, events);
  if (user.length > MAX_PROMPT_CHARS) {
    log("trip too large to summarize", { trip_id: tripId, events: events.length, chars: user.length });
    return error(413, "trip_too_large");
  }

  // Counted before the call: a failed or rejected answer still spends the budget.
  if (!(await deps.claimAttempt(tripId, uid))) {
    log("summary attempt budget used up", { trip_id: tripId });
    return error(429, "too_many_attempts");
  }

  let result: ModelResult;
  try {
    result = await deps.model.generate(SYSTEM_PROMPT, user);
  } catch (e) {
    log("claude call failed", { trip_id: tripId, error: e instanceof Error ? e.name : String(e) });
    return error(502, "model_unavailable");
  }

  if (result.stopReason !== "end_turn" || result.text === null) {
    log("claude stopped early", { trip_id: tripId, stop_reason: result.stopReason });
    return error(502, "model_incomplete");
  }

  let summary: TripSummary;
  try {
    summary = validateSummary(result.text, events);
  } catch (e) {
    if (e instanceof InvalidSummaryError) {
      log("rejected model output", { trip_id: tripId, reason: e.message });
      return error(502, "model_output_rejected");
    }
    throw e;
  }

  await deps.insertReport({ trip_id: tripId, summary, model: result.model });
  return json(200, { ...summary, model: result.model, cached: false });
}

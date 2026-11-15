// Real dependencies: Supabase (service role, server-side only) and the Claude API.
import Anthropic from "npm:@anthropic-ai/sdk@1.3.0";
import { createClient } from "npm:@supabase/supabase-js@2.118.0";
import type { Deps, ModelResult, ReportRow, SummaryModel, TripRow } from "./handler.ts";
import { SUMMARY_SCHEMA, type TripEvent } from "./prompt.ts";

export const MODEL = "claude-sonnet-5-5";

/** Claude summary via structured outputs; server-side refusal fallback opted in. */
export function claudeModel(getApiKey: () => string): SummaryModel {
  let client: Anthropic | null = null;
  return {
    async generate(system: string, user: string): Promise<ModelResult> {
      // Created on first use: a missing key fails this call (→ 502), not the whole function.
      client ??= new Anthropic({ apiKey: getApiKey(), timeout: 60_000, maxRetries: 2 });
      const response = await client.beta.messages.create({
        model: MODEL,
        max_tokens: 4096,
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
        system,
        messages: [{ role: "user", content: user }],
        output_config: {
          effort: "medium",
          format: { type: "json_schema", schema: SUMMARY_SCHEMA as unknown as Record<string, unknown> },
        },
      });
      let text: string | null = null;
      for (const block of response.content) {
        if (block.type === "text") text = (text ?? "") + block.text;
      }
      return { text, stopReason: response.stop_reason, model: response.model };
    },
  };
}

function requireEnv(name: string): string {
  const v = Deno.env.get(name);
  if (!v) throw new Error(`missing env ${name}`);
  return v;
}

export function realDeps(): Deps {
  const url = requireEnv("SUPABASE_URL");
  // Service role stays inside the function; it is never returned or logged.
  const admin = createClient(url, requireEnv("SUPABASE_SERVICE_ROLE_KEY"), {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  return {
    async authenticate(req) {
      const header = req.headers.get("Authorization") ?? "";
      const jwt = header.startsWith("Bearer ") ? header.slice(7) : "";
      if (!jwt) return null;
      const { data, error } = await admin.auth.getUser(jwt);
      return error || !data.user ? null : data.user.id;
    },
    async getTrip(tripId) {
      const { data, error } = await admin.from("trips")
        .select("id,owner,status,destination_label,started_at,ended_at")
        .eq("id", tripId).maybeSingle();
      if (error) throw error;
      return data as TripRow | null;
    },
    async getReport(tripId) {
      const { data, error } = await admin.from("trip_reports")
        .select("trip_id,summary,model").eq("trip_id", tripId).maybeSingle();
      if (error) throw error;
      return data as ReportRow | null;
    },
    async getEvents(tripId) {
      const { data, error } = await admin.from("trip_events")
        .select("seq,type,occurred_at,lat,lng,reason,payload")
        .eq("trip_id", tripId).order("seq");
      if (error) throw error;
      return data as TripEvent[];
    },
    async insertReport(row) {
      const { error } = await admin.from("trip_reports")
        .upsert(row, { onConflict: "trip_id", ignoreDuplicates: true });
      if (error) throw error;
    },
    async claimAttempt(tripId, owner) {
      const { data, error } = await admin.rpc("claim_summary_attempt", { p_trip_id: tripId, p_owner: owner });
      if (error) throw error;
      return data === true;
    },
    model: claudeModel(() => requireEnv("ANTHROPIC_API_KEY")),
  };
}

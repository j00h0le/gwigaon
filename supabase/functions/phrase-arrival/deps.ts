// Real dependencies: Supabase (service role, server-side only) and the Claude API.
import Anthropic from "npm:@anthropic-ai/sdk@1.3.0";
import { createClient } from "npm:@supabase/supabase-js@2.118.0";
import type { Deps, ModelResult, PhraseModel } from "./handler.ts";
import { PHRASE_SCHEMA } from "./prompt.ts";

export const MODEL = "claude-opus-5-5";

/** One short sentence via structured outputs, low effort; server-side refusal fallback opted in. */
export function claudeModel(getApiKey: () => string): PhraseModel {
  let client: Anthropic | null = null;
  return {
    async generate(system: string, user: string): Promise<ModelResult> {
      // Created on first use: a missing key fails this call (→ 502), not the whole function.
      client ??= new Anthropic({ apiKey: getApiKey(), timeout: 30_000, maxRetries: 2 });
      const response = await client.beta.messages.create({
        model: MODEL,
        // ≤ 160 chars of answer plus low-effort thinking (always on for this model, and counted here); a lower cap
        // bounds what an injected note can cost (/ship Q5). A cut-off answer fails validation → template.
        max_tokens: 1024,
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
        system,
        messages: [{ role: "user", content: user }],
        output_config: {
          effort: "low",
          format: { type: "json_schema", schema: PHRASE_SCHEMA as unknown as Record<string, unknown> },
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
    async claimAttempt(owner) {
      const { data, error } = await admin.rpc("claim_phrase_attempt", { p_owner: owner });
      if (error) throw error;
      return data === true;
    },
    model: claudeModel(() => requireEnv("ANTHROPIC_API_KEY")),
  };
}

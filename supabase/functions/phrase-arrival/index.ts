// Edge Function phrase-arrival (ADR 0021): POST {entrance, parking, key} with the user's JWT →
// {sentence, model}: one polite spoken request built from the passenger's arrival notes.
import { realDeps } from "./deps.ts";
import { handle } from "./handler.ts";

const deps = realDeps();

Deno.serve(async (req) => {
  try {
    return await handle(req, deps);
  } catch (e) {
    console.error(JSON.stringify({ msg: "unhandled", error: e instanceof Error ? e.message : String(e) }));
    return new Response(JSON.stringify({ error: "internal" }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});

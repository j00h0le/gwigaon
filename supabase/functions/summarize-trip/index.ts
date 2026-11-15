// Edge Function summarize-trip: POST {trip_id} with the owner's JWT →
// {summary, summary_seqs, notable_events[], caveats[], model, cached}.
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

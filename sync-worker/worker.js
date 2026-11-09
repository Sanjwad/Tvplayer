// Sanj TV sync: stores each household's My List, favourites and watched progress.
// A household is identified by a 64-character key the app derives from its IPTV login
// (a SHA-256 hash, so the login itself never leaves the TV).
//
//   GET  /v1/<key>   -> the saved JSON (or {} if none yet)
//   PUT  /v1/<key>   -> replace it (body: JSON, up to 512 KB)

const MAX = 512 * 1024;
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, PUT, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return new Response(null, { headers: cors });
    const m = new URL(req.url).pathname.match(/^\/v1\/([0-9a-f]{64})$/);
    if (!m) return json({ ok: true, service: "sanjtv-sync" }, m === null && new URL(req.url).pathname === "/" ? 200 : 404);
    const key = m[1];
    if (req.method === "GET") {
      const v = await env.SYNC.get(key);
      return new Response(v ?? "{}", { headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });
    }
    if (req.method === "PUT") {
      const body = await req.text();
      if (body.length > MAX) return json({ error: "too big" }, 413);
      try { JSON.parse(body); } catch { return json({ error: "not json" }, 400); }
      await env.SYNC.put(key, body);
      return json({ ok: true });
    }
    return json({ error: "method" }, 405);
  },
};

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });
}

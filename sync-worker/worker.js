// v2
// Sanj TV sync + sharing.
//
// Sync: each household's My List, favourites and watched progress, under a 64-character key the
// app derives from its IPTV login (a SHA-256 hash, so the login itself never leaves the TV).
//   GET  /v1/<key>   -> the saved JSON (or {} if none yet)
//   PUT  /v1/<key>   -> replace it (body: JSON, up to 512 KB)
//
// Sharing ("Send to a friend"): each household also gets a public friend code like SANJ-4821.
// The private 64-character share key proves who you are; friend codes are only for finding people.
// Friendship is mutual: one side sends a request, the other accepts, and both are then friends.
//   POST /v1/share/me        {key, name}            -> my state (creates my code the first time)
//   GET  /v1/share/me/<key>                         -> my state (read only), or {} if not set up
//        state = {code, name, friends:[{code,name}], requestsIn:[…], requestsOut:[…]}
//   GET  /v1/share/who/<CODE>                       -> {code, name}  (to check a code before adding)
//   POST /v1/share/request   {key, to:CODE}         -> state  (accepts at once if they'd already asked me)
//   POST /v1/share/respond   {key, code, accept}    -> state
//   POST /v1/share/unfriend  {key, code}            -> state  (removes the friendship on both sides)
//   POST /v1/share/send      {key, to:CODE, item}   -> {ok}  (friends only)
//   GET  /v1/share/inbox/<key>                      -> {items:[…]}
//   GET  /v1/share/outbox/<key>                     -> {items:[…]}  (what I've sent, newest first)
//   POST /v1/share/unsend    {key, id}              -> {ok}  (removes it from my sent list only)
//   POST /v1/share/dismiss   {key, id}              -> {ok}

const MAX = 512 * 1024;
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, PUT, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};
const KEY = /^[0-9a-f]{64}$/;
const CODE = /^[A-Z]{1,8}-\d{4}$/;
const INBOX_MAX = 60;

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return new Response(null, { headers: cors });
    const path = new URL(req.url).pathname;
    if (path === "/") return json({ ok: true, service: "sanjtv-sync" });
    if (path.startsWith("/v1/share/")) {
      try { return await share(req, env, path.slice("/v1/share/".length)); }
      catch (e) { return json({ error: String(e && e.message || e) }, 400); }
    }
    const m = path.match(/^\/v1\/([0-9a-f]{64})$/);
    if (!m) return json({ error: "not found" }, 404);
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

// ---------- sharing ----------
async function getJ(env, k) { const v = await env.SYNC.get(k); return v ? JSON.parse(v) : null; }
const putJ = (env, k, o) => env.SYNC.put(k, JSON.stringify(o));
const cleanName = n => String(n || "").replace(/[^\p{L}\p{N} '\-]/gu, "").trim().slice(0, 24);

async function me(env, key) {
  if (!KEY.test(key || "")) throw new Error("bad key");
  return await getJ(env, "share:me:" + key);
}
async function friendList(env, codes) {
  const out = [];
  for (const c of codes || []) {
    const w = await getJ(env, "share:code:" + c);
    if (w) out.push({ code: c, name: w.name });
  }
  return out;
}
// Two TVs can talk to different Cloudflare locations, and a location may briefly see an older copy
// of a record, so an "accept" can be lost on one side. Repair it here: if the other person already
// lists me as a friend, we're friends.
async function repair(env, key, rec) {
  let changed = false;
  for (const c of [...(rec.requestsOut || []), ...(rec.requestsIn || [])]) {
    const o = await other(env, c);
    if (o && (o.rec.friends || []).includes(rec.code)) {
      rec.requestsOut = without(rec.requestsOut, c); rec.requestsIn = without(rec.requestsIn, c);
      rec.friends = withx(rec.friends, c); changed = true;
    }
  }
  if (changed) await putJ(env, "share:me:" + key, rec);
  return rec;
}
async function state(env, rec) {
  return { code: rec.code, name: rec.name, friends: await friendList(env, rec.friends),
           requestsIn: await friendList(env, rec.requestsIn), requestsOut: await friendList(env, rec.requestsOut) };
}
// Load the other person's record by code, change it, save it.
async function other(env, code) {
  const c = await getJ(env, "share:code:" + code);
  if (!c) return null;
  const rec = await getJ(env, "share:me:" + c.key);
  return rec ? { key: c.key, rec } : null;
}
const without = (list, x) => (list || []).filter(v => v !== x);
const withx = (list, x) => [...new Set([...(list || []), x])];

async function share(req, env, rest) {
  const body = req.method === "POST" ? await req.json().catch(() => ({})) : {};

  // Who am I? Creates my friend code the first time; renames if the name changed.
  if (rest === "me" && req.method === "POST") {
    const name = cleanName(body.name) || "Friend";
    let rec = await me(env, body.key);
    if (!rec) {
      const stem = name.normalize("NFD").replace(/[^A-Za-z]/g, "").toUpperCase().slice(0, 8) || "TV";
      let code = null;
      for (let i = 0; i < 20 && !code; i++) {
        const c = stem + "-" + String(1000 + Math.floor(Math.random() * 9000));
        if (!(await env.SYNC.get("share:code:" + c))) code = c;
      }
      if (!code) throw new Error("no free code");
      rec = { code, name, friends: [], requestsIn: [], requestsOut: [] };
      await putJ(env, "share:me:" + body.key, rec);
      await putJ(env, "share:code:" + code, { key: body.key, name });
    } else if (rec.name !== name) {
      rec.name = name;
      // Renamed: a new code to match the new name (SANJ-3111 → BRYAN-4820), and friends' lists updated.
      const stem = name.normalize("NFD").replace(/[^A-Za-z]/g, "").toUpperCase().slice(0, 8) || "TV";
      const oldCode = rec.code;
      if (body.recode && !oldCode.startsWith(stem + "-")) {
        let code = null;
        for (let i = 0; i < 20 && !code; i++) {
          const c = stem + "-" + String(1000 + Math.floor(Math.random() * 9000));
          if (!(await env.SYNC.get("share:code:" + c))) code = c;
        }
        if (code) {
          rec.code = code;
          await putJ(env, "share:code:" + code, { key: body.key, name });
          for (const c of new Set([...(rec.friends || []), ...(rec.requestsIn || []), ...(rec.requestsOut || [])])) {
            const o = await other(env, c);
            if (!o) continue;
            const swap = l => (l || []).map(x => x === oldCode ? code : x);
            o.rec.friends = swap(o.rec.friends); o.rec.requestsIn = swap(o.rec.requestsIn); o.rec.requestsOut = swap(o.rec.requestsOut);
            await putJ(env, "share:me:" + o.key, o.rec);
          }
          await env.SYNC.delete("share:code:" + oldCode);
        }
      }
      await putJ(env, "share:me:" + body.key, rec);
      await putJ(env, "share:code:" + rec.code, { key: body.key, name });
    }
    return json(await state(env, await repair(env, body.key, rec)));
  }

  if (rest.startsWith("me/") && req.method === "GET") {
    const key = rest.slice(3);
    const rec = await me(env, key);
    return new Response(JSON.stringify(rec ? await state(env, await repair(env, key, rec)) : {}),
      { headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });
  }

  if (rest.startsWith("who/") && req.method === "GET") {
    const c = decodeURIComponent(rest.slice(4)).toUpperCase().trim();
    if (!CODE.test(c)) return json({ error: "That isn't a friend code (it looks like SANJ-4821)." }, 404);
    const w = await getJ(env, "share:code:" + c);
    return w ? json({ code: c, name: w.name }) : json({ error: "No one has the code " + c + "." }, 404);
  }

  if (rest === "request" && req.method === "POST") {
    const rec = await me(env, body.key);
    if (!rec) throw new Error("set up your friend code first");
    const to = String(body.to || "").toUpperCase().trim();
    if (to === rec.code) return json({ error: "That's your own code." }, 400);
    const o = CODE.test(to) ? await other(env, to) : null;
    if (!o) return json({ error: "No one has the code " + to + "." }, 404);
    if ((rec.friends || []).includes(to)) return json(await state(env, rec));
    if ((rec.requestsIn || []).includes(to) || (o.rec.friends || []).includes(rec.code)) {
      // They'd already asked me: that's a yes from both sides.
      rec.requestsIn = without(rec.requestsIn, to); rec.friends = withx(rec.friends, to);
      o.rec.requestsOut = without(o.rec.requestsOut, rec.code); o.rec.friends = withx(o.rec.friends, rec.code);
    } else {
      rec.requestsOut = withx(rec.requestsOut, to);
      o.rec.requestsIn = withx(o.rec.requestsIn, rec.code);
    }
    await putJ(env, "share:me:" + body.key, rec);
    await putJ(env, "share:me:" + o.key, o.rec);
    return json(await state(env, rec));
  }

  if (rest === "respond" && req.method === "POST") {
    const rec = await me(env, body.key);
    if (!rec) throw new Error("set up your friend code first");
    const code = String(body.code || "").toUpperCase().trim();
    rec.requestsIn = without(rec.requestsIn, code);
    rec.requestsOut = without(rec.requestsOut, code); // also lets me cancel a request I sent
    const o = CODE.test(code) ? await other(env, code) : null;
    if (o) {
      o.rec.requestsOut = without(o.rec.requestsOut, rec.code);
      o.rec.requestsIn = without(o.rec.requestsIn, rec.code);
      if (body.accept) { rec.friends = withx(rec.friends, code); o.rec.friends = withx(o.rec.friends, rec.code); }
      await putJ(env, "share:me:" + o.key, o.rec);
    }
    await putJ(env, "share:me:" + body.key, rec);
    return json(await state(env, rec));
  }

  if (rest === "unfriend" && req.method === "POST") {
    const rec = await me(env, body.key);
    if (!rec) throw new Error("set up your friend code first");
    const code = String(body.code || "").toUpperCase().trim();
    rec.friends = without(rec.friends, code);
    const o = CODE.test(code) ? await other(env, code) : null;
    if (o) { o.rec.friends = without(o.rec.friends, rec.code); await putJ(env, "share:me:" + o.key, o.rec); }
    await putJ(env, "share:me:" + body.key, rec);
    return json(await state(env, rec));
  }

  if (rest === "send" && req.method === "POST") {
    const rec = await me(env, body.key);
    if (!rec) throw new Error("set up your friend code first");
    const to = String(body.to || "").toUpperCase().trim();
    const target = CODE.test(to) ? await getJ(env, "share:code:" + to) : null;
    if (!target) return json({ error: "No one has the code " + to + "." }, 404);
    if (!(rec.friends || []).includes(to)) {
      const theirs = await getJ(env, "share:me:" + target.key);
      if (!theirs || !(theirs.friends || []).includes(rec.code))
        return json({ error: "You can only send to friends who have accepted." }, 403);
      rec.friends = withx(rec.friends, to); rec.requestsOut = without(rec.requestsOut, to); rec.requestsIn = without(rec.requestsIn, to);
      await putJ(env, "share:me:" + body.key, rec);
    }
    const it = body.item || {};
    const item = {
      id: crypto.randomUUID(), at: Date.now(), from: rec.name, fromCode: rec.code,
      title: String(it.title || "").slice(0, 200), year: String(it.year || "").slice(0, 10),
      series: !!it.series, poster: String(it.poster || "").slice(0, 500), note: String(it.note || "").slice(0, 200),
      tmdb: Number(it.tmdb) || 0,
    };
    if (!item.title) throw new Error("missing title");
    const inbox = (await getJ(env, "share:inbox:" + target.key)) || [];
    inbox.unshift(item);
    await putJ(env, "share:inbox:" + target.key, inbox.slice(0, INBOX_MAX));
    // Keep a copy for my own "Sent by you" row.
    const outbox = (await getJ(env, "share:outbox:" + body.key)) || [];
    outbox.unshift({ ...item, to: target.name, toCode: to });
    await putJ(env, "share:outbox:" + body.key, outbox.slice(0, INBOX_MAX));
    return json({ ok: true, to: target.name });
  }

  if (rest.startsWith("inbox/") && req.method === "GET") {
    const key = rest.slice(6);
    if (!KEY.test(key)) throw new Error("bad key");
    return new Response(JSON.stringify({ items: (await getJ(env, "share:inbox:" + key)) || [] }),
      { headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });
  }

  if (rest.startsWith("outbox/") && req.method === "GET") {
    const key = rest.slice(7);
    if (!KEY.test(key)) throw new Error("bad key");
    return new Response(JSON.stringify({ items: (await getJ(env, "share:outbox:" + key)) || [] }),
      { headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } });
  }

  if (rest === "unsend" && req.method === "POST") {
    if (!KEY.test(body.key || "")) throw new Error("bad key");
    const outbox = (await getJ(env, "share:outbox:" + body.key)) || [];
    const left = outbox.filter(i => i.id !== body.id);
    if (left.length !== outbox.length) await putJ(env, "share:outbox:" + body.key, left);
    return json({ ok: true });
  }

  if (rest === "dismiss" && req.method === "POST") {
    if (!KEY.test(body.key || "")) throw new Error("bad key");
    const inbox = (await getJ(env, "share:inbox:" + body.key)) || [];
    const left = inbox.filter(i => i.id !== body.id);
    if (left.length !== inbox.length) await putJ(env, "share:inbox:" + body.key, left);
    return json({ ok: true });
  }
  return json({ error: "not found" }, 404);
}

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });
}

// ChinookTV site + accounts API.
// Payments go through Stripe Checkout; the webhook turns a paid checkout into an
// activation code that works on up to 5 TVs.
const HOME = "chinooktv.com";
const APK = "https://github.com/Sanjwad/Tvplayer/releases/download/latest/tvplayer.apk";
const MAX_TVS = 5;
const PLANS = { monthly: "chinook_monthly", lifetime: "chinook_lifetime" };

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.hostname !== HOME && !url.hostname.endsWith(".workers.dev")) {
      url.hostname = HOME; url.protocol = "https:"; url.port = "";
      return Response.redirect(url.toString(), 301);
    }
    const p = url.pathname;
    if (p === "/get" || p === "/get/" || p === "/apk") return Response.redirect(APK, 302);
    try {
      if (p === "/api/prices" && req.method === "GET") return await prices(env);
      if (p === "/api/checkout" && req.method === "POST") return await checkout(req, env, url);
      if (p === "/api/session" && req.method === "GET") return await sessionCode(env, url);
      if (p === "/api/webhook" && req.method === "POST") return await webhook(req, env);
      if (p === "/api/activate" && req.method === "POST") return await activate(req, env);
      if (p === "/api/account" && req.method === "POST") return await account(req, env);
      if (p === "/api/portal" && req.method === "POST") return await portal(req, env, url);
      if (p === "/api/remove-tv" && req.method === "POST") return await removeTv(req, env);
    } catch (e) {
      console.log("error", p, e && e.stack || e);
      return json({ error: "Something went wrong on our side. Try again in a minute." }, 500);
    }
    if (p.startsWith("/api/")) return json({ error: "Not found" }, 404);
    return env.ASSETS.fetch(req);
  },
};

// ---------- Stripe helpers (plain fetch; no SDK needed in a Worker) ----------
function form(obj, prefix = "", out = new URLSearchParams()) {
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined || v === null) continue;
    const key = prefix ? `${prefix}[${k}]` : k;
    if (typeof v === "object") form(v, key, out); else out.append(key, String(v));
  }
  return out;
}
async function stripe(env, method, path, body) {
  const r = await fetch("https://api.stripe.com/v1/" + path, {
    method,
    headers: { Authorization: "Bearer " + env.STRIPE_SECRET_KEY,
               "Content-Type": "application/x-www-form-urlencoded",
               "Stripe-Version": "2024-06-20" },
    body: body ? form(body) : undefined,
  });
  const data = await r.json();
  if (!r.ok) throw new Error("Stripe " + path + ": " + (data.error && data.error.message));
  return data;
}
async function priceFor(env, plan) {
  const res = await stripe(env, "GET", "prices?active=true&lookup_keys[]=" + PLANS[plan]);
  if (!res.data.length) throw new Error("No price with lookup key " + PLANS[plan]);
  return res.data[0].id;
}

// The pricing section shows whatever is set in Stripe, so prices live in one place.
async function prices(env) {
  const out = {};
  for (const [plan, key] of Object.entries(PLANS)) {
    try {
      const r = await stripe(env, "GET", "prices?active=true&lookup_keys[]=" + key);
      if (r.data[0]) out[plan] = { amount: r.data[0].unit_amount, currency: r.data[0].currency };
    } catch (e) {}
  }
  return new Response(JSON.stringify(out), { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=300" } });
}

// ---------- checkout ----------
async function checkout(req, env, url) {
  const { plan, ref } = await req.json().catch(() => ({}));
  if (!PLANS[plan]) return json({ error: "Choose monthly or lifetime." }, 400);
  const referral = cleanRef(ref);
  const base = `https://${url.host}`;
  const params = {
    mode: plan === "monthly" ? "subscription" : "payment",
    line_items: { 0: { price: await priceFor(env, plan), quantity: 1 } },
    success_url: `${base}/thanks.html?session_id={CHECKOUT_SESSION_ID}`,
    cancel_url: `${base}/#pricing`,
    allow_promotion_codes: "true",
    billing_address_collection: "auto",
    client_reference_id: referral || undefined,
    metadata: { plan, ref: referral || "" },
  };
  if (plan === "monthly") {
    params.subscription_data = { metadata: { plan, ref: referral || "" } };
  } else {
    // one-off payments get a proper invoice/receipt too
    params.customer_creation = "always";
    params.invoice_creation = { enabled: "true", invoice_data: { metadata: { plan, ref: referral || "" } } };
  }
  const s = await stripe(env, "POST", "checkout/sessions", params);
  return json({ url: s.url });
}
function cleanRef(r) {
  return (typeof r === "string" ? r : "").toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 20);
}

// After paying, the thanks page asks for the code. The webhook may land a second later.
async function sessionCode(env, url) {
  const id = url.searchParams.get("session_id") || "";
  if (!/^cs_[A-Za-z0-9_]+$/.test(id)) return json({ error: "Missing session" }, 400);
  let code = await env.ACCOUNTS.get("sess:" + id);
  if (!code) {
    // Webhook not here yet: confirm with Stripe directly and create the account now.
    const s = await stripe(env, "GET", "checkout/sessions/" + id);
    if (s.status !== "complete" || (s.payment_status !== "paid" && s.payment_status !== "no_payment_required"))
      return json({ pending: true });
    code = await createAccount(env, s);
  }
  return json({ code });
}

// ---------- webhook ----------
async function webhook(req, env) {
  const body = await req.text();
  if (!(await verifySig(body, req.headers.get("stripe-signature") || "", env.STRIPE_WEBHOOK_SECRET)))
    return new Response("bad signature", { status: 400 });
  const ev = JSON.parse(body);
  const o = ev.data.object;
  switch (ev.type) {
    case "checkout.session.completed":
    case "checkout.session.async_payment_succeeded":
      if (o.payment_status === "paid" || o.payment_status === "no_payment_required") await createAccount(env, o);
      break;
    case "customer.subscription.updated":
    case "customer.subscription.deleted":
      await setStatus(env, o.customer, o.status);   // active, past_due, canceled, unpaid…
      break;
    case "invoice.payment_failed":
      await setStatus(env, o.customer, "past_due");
      break;
    case "charge.refunded":
      if (o.refunded) await setStatus(env, o.customer, "refunded");
      break;
  }
  return new Response("ok");
}
async function verifySig(body, header, secret) {
  if (!secret) return false;
  const parts = Object.fromEntries(header.split(",").map(x => x.split("=")).filter(x => x.length === 2).map(([k, v]) => [k, v]));
  const sigs = header.split(",").filter(x => x.startsWith("v1=")).map(x => x.slice(3));
  const t = parts.t;
  if (!t || !sigs.length || Math.abs(Date.now() / 1000 - Number(t)) > 300) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(t + "." + body));
  const hex = [...new Uint8Array(mac)].map(b => b.toString(16).padStart(2, "0")).join("");
  return sigs.some(s => timingSafeEq(s, hex));
}
function timingSafeEq(a, b) {
  if (a.length !== b.length) return false;
  let d = 0; for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

// ---------- accounts (KV) ----------
// acct:<CODE> = {plan, status, customer, subscription, email, ref, tvs:[{id,name,added}], created}
// sess:<checkout id> = CODE, cust:<customer id> = CODE, ref:<REF>:<CODE> = {plan, created} (for provider reports)
function newCode() {
  const A = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I: easy to type with a remote
  const b = crypto.getRandomValues(new Uint8Array(8));
  const s = [...b].map(x => A[x % A.length]).join("");
  return s.slice(0, 4) + "-" + s.slice(4);
}
async function createAccount(env, s) {
  const existing = await env.ACCOUNTS.get("sess:" + s.id);
  if (existing) return existing;
  const plan = (s.metadata && s.metadata.plan) || (s.mode === "subscription" ? "monthly" : "lifetime");
  const ref = cleanRef(s.metadata && s.metadata.ref);
  // A returning customer keeps their code (e.g. monthly → lifetime upgrade).
  let code = s.customer ? await env.ACCOUNTS.get("cust:" + s.customer) : null;
  let acct = code ? JSON.parse(await env.ACCOUNTS.get("acct:" + code) || "null") : null;
  if (!acct) { code = newCode(); acct = { tvs: [], created: Date.now() }; }
  Object.assign(acct, {
    plan, status: "active", customer: s.customer || acct.customer || null,
    subscription: s.subscription || (plan === "lifetime" ? null : acct.subscription),
    email: (s.customer_details && s.customer_details.email) || acct.email || null,
    ref: acct.ref || ref || null,
  });
  await env.ACCOUNTS.put("acct:" + code, JSON.stringify(acct));
  await env.ACCOUNTS.put("sess:" + s.id, code);
  if (acct.customer) await env.ACCOUNTS.put("cust:" + acct.customer, code);
  if (ref) await env.ACCOUNTS.put(`ref:${ref}:${code}`, JSON.stringify({ plan, created: Date.now() }));
  return code;
}
async function setStatus(env, customer, status) {
  if (!customer) return;
  const code = await env.ACCOUNTS.get("cust:" + customer);
  if (!code) return;
  const acct = JSON.parse(await env.ACCOUNTS.get("acct:" + code) || "null");
  if (!acct) return;
  if (acct.plan === "lifetime" && status !== "refunded") return; // subscriptions don't affect a lifetime licence
  acct.status = status;
  await env.ACCOUNTS.put("acct:" + code, JSON.stringify(acct));
}
function normCode(c) {
  const s = (typeof c === "string" ? c : "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  return s.length === 8 ? s.slice(0, 4) + "-" + s.slice(4) : null;
}
async function load(env, code) {
  const c = normCode(code);
  if (!c) return [null, null];
  return [c, JSON.parse(await env.ACCOUNTS.get("acct:" + c) || "null")];
}
const isActive = a => a.status === "active" || a.status === "trialing";

// The TV app calls this when you type your code, and again now and then to check.
async function activate(req, env) {
  const { code, device, name } = await req.json().catch(() => ({}));
  const [c, acct] = await load(env, code);
  if (!acct) return json({ ok: false, error: "That code wasn't found. Check it and try again." }, 404);
  if (!isActive(acct)) return json({ ok: false, error: "This account isn't active. Visit chinooktv.com/account to fix it." }, 402);
  const id = String(device || "").slice(0, 64);
  if (!id) return json({ ok: false, error: "Missing device" }, 400);
  let tv = acct.tvs.find(t => t.id === id);
  if (!tv) {
    if (acct.tvs.length >= MAX_TVS)
      return json({ ok: false, error: `This code is already on ${MAX_TVS} TVs. Remove one at chinooktv.com/account.` }, 409);
    tv = { id, name: String(name || "TV").slice(0, 40), added: Date.now() };
    acct.tvs.push(tv);
    await env.ACCOUNTS.put("acct:" + c, JSON.stringify(acct));
  }
  return json({ ok: true, plan: acct.plan, tvs: acct.tvs.length, max: MAX_TVS });
}

async function account(req, env) {
  const { code } = await req.json().catch(() => ({}));
  const [c, acct] = await load(env, code);
  if (!acct) return json({ error: "That code wasn't found." }, 404);
  return json({ code: c, plan: acct.plan, status: acct.status, active: isActive(acct),
                tvs: acct.tvs.map(t => ({ id: t.id, name: t.name, added: t.added })), max: MAX_TVS,
                canManageBilling: !!acct.customer });
}
async function removeTv(req, env) {
  const { code, device } = await req.json().catch(() => ({}));
  const [c, acct] = await load(env, code);
  if (!acct) return json({ error: "That code wasn't found." }, 404);
  acct.tvs = acct.tvs.filter(t => t.id !== device);
  await env.ACCOUNTS.put("acct:" + c, JSON.stringify(acct));
  return json({ ok: true });
}
// Stripe's own customer portal: update card, see invoices, cancel.
async function portal(req, env, url) {
  const { code } = await req.json().catch(() => ({}));
  const [, acct] = await load(env, code);
  if (!acct || !acct.customer) return json({ error: "No billing details for this code." }, 404);
  const s = await stripe(env, "POST", "billing_portal/sessions",
                         { customer: acct.customer, return_url: `https://${url.host}/account.html` });
  return json({ url: s.url });
}

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
}

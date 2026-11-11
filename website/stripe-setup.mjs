// Run by the Website workflow. Makes sure Stripe has the ChinookTV product, the two prices,
// the webhook and the customer billing page. Safe to run again and again.
const KEY = process.env.STRIPE_SECRET_KEY;
const SITE = "https://chinooktv.com";
const CURRENCY = (process.env.CURRENCY || "cad").toLowerCase();
const MONTHLY = parseInt(process.env.PRICE_MONTHLY_CENTS || "200", 10);
const LIFETIME = parseInt(process.env.PRICE_LIFETIME_CENTS || "4000", 10);
const NEED_SECRET = process.env.NEED_WEBHOOK_SECRET === "1";
const EVENTS = ["checkout.session.completed", "checkout.session.async_payment_succeeded",
  "customer.subscription.updated", "customer.subscription.deleted", "invoice.payment_failed", "charge.refunded"];
import { writeFileSync } from "node:fs";

if (!KEY) { console.log("No STRIPE_SECRET_KEY secret yet: skipping Stripe setup."); process.exit(0); }
console.log("Stripe mode:", KEY.startsWith("sk_live") ? "LIVE" : "test");

function form(obj, prefix = "", out = new URLSearchParams()) {
  for (const [k, v] of Object.entries(obj)) {
    if (v === undefined || v === null) continue;
    const key = prefix ? `${prefix}[${k}]` : k;
    if (Array.isArray(v)) v.forEach((x, i) => typeof x === "object" ? form(x, `${key}[${i}]`, out) : out.append(`${key}[${i}]`, String(x)));
    else if (typeof v === "object") form(v, key, out); else out.append(key, String(v));
  }
  return out;
}
async function api(method, path, body, okMissing = false) {
  const r = await fetch("https://api.stripe.com/v1/" + path, { method,
    headers: { Authorization: "Bearer " + KEY, "Content-Type": "application/x-www-form-urlencoded", "Stripe-Version": "2024-06-20" },
    body: body ? form(body) : undefined });
  const d = await r.json();
  if (r.status === 404 && okMissing) return null;
  if (!r.ok) throw new Error(`${method} ${path}: ${d.error && d.error.message}`);
  return d;
}

// product
let product = await api("GET", "products/chinooktv", null, true);
if (!product) product = await api("POST", "products", { id: "chinooktv", name: "ChinookTV",
  description: "IPTV player for Android TV and Fire TV. One account covers up to 5 TVs." });
console.log("Product:", product.id);

// prices (Stripe prices can't be edited, so a new amount means a new price that takes over the lookup key)
async function ensurePrice(lookup, amount, recurring, nickname) {
  const cur = (await api("GET", `prices?active=true&lookup_keys[]=${lookup}`)).data[0];
  if (cur && cur.unit_amount === amount && cur.currency === CURRENCY) { console.log(`${nickname}: ${amount / 100} ${CURRENCY} (unchanged)`); return; }
  await api("POST", "prices", { product: "chinooktv", currency: CURRENCY, unit_amount: amount, nickname,
    lookup_key: lookup, transfer_lookup_key: "true", tax_behavior: "exclusive",
    recurring: recurring ? { interval: "month" } : undefined });
  if (cur) await api("POST", "prices/" + cur.id, { active: "false" });
  console.log(`${nickname}: now ${amount / 100} ${CURRENCY}`);
}
await ensurePrice("chinook_monthly", MONTHLY, true, "Monthly");
await ensurePrice("chinook_lifetime", LIFETIME, false, "Lifetime");

// webhook
const url = SITE + "/api/webhook";
const hooks = (await api("GET", "webhook_endpoints?limit=100")).data.filter(h => h.url === url);
if (hooks.length && !NEED_SECRET) {
  await api("POST", "webhook_endpoints/" + hooks[0].id, { enabled_events: EVENTS });
  console.log("Webhook: up to date");
} else {
  for (const h of hooks) await api("DELETE", "webhook_endpoints/" + h.id);
  const h = await api("POST", "webhook_endpoints", { url, enabled_events: EVENTS, description: "ChinookTV accounts" });
  writeFileSync("webhook-secret.txt", h.secret);
  console.log("Webhook: created (new signing secret saved to the site)");
}

// customer billing page (update card, invoices, cancel)
const confs = (await api("GET", "billing_portal/configurations?is_default=true&active=true")).data;
const portal = {
  business_profile: { headline: "ChinookTV: manage your plan" },
  default_return_url: SITE + "/account.html",
  features: {
    customer_update: { enabled: "true", allowed_updates: ["email", "address"] },
    invoice_history: { enabled: "true" },
    payment_method_update: { enabled: "true" },
    subscription_cancel: { enabled: "true", mode: "at_period_end" },
  },
};
if (confs.length) await api("POST", "billing_portal/configurations/" + confs[0].id, portal);
else await api("POST", "billing_portal/configurations", portal);
console.log("Billing page: ready");

#!/usr/bin/env node
// Walks the Bifrost twin the way an app and an admin would: call chat with a seeded
// virtual key, create a new key on the control plane and use it at once, revoke it
// and watch it get 401, then spend a rate-limit budget until it answers 429.
//
// Usage: node examples/bifrost/client.js [BASE_URL]      (default http://localhost:9300)
// Start the twin with --rate-limit 3 to see the 429 step; without it every call is 200.
const base = (process.argv[2] || process.env.BASE_URL || "http://localhost:9300").replace(/\/$/, "");

async function call(method, path, { vk, body } = {}) {
  const headers = { "content-type": "application/json" };
  if (vk) headers["x-bf-vk"] = vk;
  const res = await fetch(base + path, { method, headers, body: body && JSON.stringify(body) });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = text; }
  return { status: res.status, headers: res.headers, json };
}

const chat = (vk, model = "gpt-4o-mini") =>
  call("POST", "/v1/chat/completions", { vk, body: { model, messages: [{ role: "user", content: "hi" }] } });

function show(label, r, pick) {
  console.log(`${String(r.status).padEnd(4)} ${label}${pick ? "  " + pick(r) : ""}`);
}

const budget = (r) => {
  const rem = r.headers.get("x-ratelimit-remaining-requests");
  return rem == null ? "" : `(remaining ${rem}/${r.headers.get("x-ratelimit-limit-requests")})`;
};

(async () => {
  // 1. the app: an OpenAI-shaped completion, authenticated by a virtual key
  const r1 = await chat("sk-bf-live-0000000000", "gpt-4o");
  show("chat with the seeded key", r1, (r) => `model=${r.json.model} id=${r.json.id} tokens=${r.json.usage.total_tokens}`);
  show("chat with no key", await chat(undefined), (r) => r.json.error.message);
  show("chat with the inactive seeded key", await chat("sk-bf-off-00000000000"), (r) => r.json.error.message);

  // 2. the admin: create a key on the control plane, then the app uses it immediately
  const secret = `sk-bf-example-${Date.now()}`;
  const created = await call("POST", "/api/governance/virtual-keys",
    { body: { value: secret, name: "from client.js" } });
  show("create a virtual key", created, (r) => `id=${r.json.id} status=${r.json.status}`);
  show("chat with the new key", await chat(secret));

  // 3. revoke it (merge-patch status) and the same call is refused
  show("deactivate it", await call("POST", `/api/governance/virtual-keys/${created.json.id}`,
    { body: { status: "inactive" } }), (r) => `status=${r.json.status}`);
  show("chat with the revoked key", await chat(secret), (r) => r.json.error.code);

  // 4. spend a per-key budget (only limited when the kit was built with --rate-limit)
  for (let i = 1; i <= 4; i++) {
    const r = await chat("sk-bf-live-0000000000");
    show(`chat #${i} on the seeded key`, r, (x) => (x.status === 429 ? x.json.error.code + " " : "") + budget(x));
  }
})().catch((e) => {
  console.error(`could not reach the twin at ${base}: ${e.cause?.code || e.message}`);
  process.exit(1);
});

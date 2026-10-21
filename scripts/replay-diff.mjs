// Replays requests at two running twins and reports every difference in status,
// headers (names, order and values, except Date) and body bytes. Every request
// carries the same Host header, so the twins' self-referential URLs and the JWTs
// that embed them match too.
//
//   node scripts/replay-diff.mjs corpus <a-url> <b-url> <corpus.json>
//   node scripts/replay-diff.mjs okta   <a-url> <b-url>
//   node scripts/replay-diff.mjs limited <a-url> <b-url>
//
// `okta` and `limited` run stateful sequences against twins built by
// `mocksys kit okta` and `mocksys kit okta --rate-limit 3 --token-ttl 600`. Each
// step is built from the previous answers of twin a. Exits 1 on any difference.
import http from "node:http";
import fs from "node:fs";

const HOST = "localhost:9000";

function send(base, { method = "GET", path, headers = {}, body }) {
  const url = new URL(path, base);
  const text = body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body);
  const hs = { Host: HOST, ...(typeof body === "object" ? { "Content-Type": "application/json" } : {}), ...headers };
  if (text !== undefined) hs["Content-Length"] = Buffer.byteLength(text);
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: url.hostname, port: url.port, path: url.pathname + url.search, method, headers: hs }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        const raw = [];
        for (let i = 0; i < res.rawHeaders.length; i += 2) {
          if (res.rawHeaders[i].toLowerCase() !== "date") raw.push([res.rawHeaders[i], res.rawHeaders[i + 1]]);
        }
        resolve({ status: res.statusCode, headers: raw, body: Buffer.concat(chunks).toString("utf8") });
      });
    });
    req.on("error", reject);
    if (text !== undefined) req.write(text);
    req.end();
  });
}

const json = (r) => { try { return JSON.parse(r.body); } catch { return undefined; } };
const header = (r, name) => (r.headers.find(([k]) => k.toLowerCase() === name) || [])[1];
const form = (o) => new URLSearchParams(o).toString();
const formPost = (path, o) => ({ method: "POST", path, headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: form(o) });
const codeOf = (r) => new URL(header(r, "location")).searchParams.get("code");
const bearer = (t) => ({ Authorization: `Bearer ${t}` });

// Each step is a request, or a function of twin a's earlier answers returning one.
const sequences = {
  okta: [
    { method: "POST", path: "/api/v1/users", body: { profile: { login: "eve@example.com", email: "eve@example.com", firstName: "Eve" } } },
    (a) => ({ path: `/api/v1/users/${json(a[0]).id}` }),
    (a) => ({ method: "POST", path: `/api/v1/users/${json(a[0]).id}`, body: { status: "ACTIVE", profile: { lastName: "Adams" } } }),
    { path: "/api/v1/users?filter=" + encodeURIComponent('status eq "ACTIVE" and profile.lastName eq "Adams"') },
    { path: "/api/v1/users?filter=" + encodeURIComponent("profile.login sw \"e\" or not (status eq \"ACTIVE\")") },
    { path: "/api/v1/users?limit=2" },
    (a) => ({ path: "/api/v1/users?limit=2&after=" + new URL(header(a[5], "link").match(/<([^>]+)>/)[1], "http://x").searchParams.get("after") }),
    { path: "/api/v1/users?filter=" + encodeURIComponent("status eq") },
    { path: "/api/v1/users?filter=" + encodeURIComponent("(status pr") },
    (a) => ({ method: "PUT", path: `/api/v1/groups/00g1/users/${json(a[0]).id}` }),
    { path: "/api/v1/groups/00g1/users?limit=1" },
    (a) => ({ path: `/api/v1/users/${json(a[0]).id}/groups` }),
    { path: "/oauth2/v1/authorize?client_id=c&redirect_uri=http://app/cb&state=s1" },
    (a) => ({ path: `/oauth2/v1/authorize?login=eve@example.com&uid=${json(a[0]).id}&redirect_uri=http://app/cb&state=s1` }),
    (a) => formPost("/oauth2/v1/token", { grant_type: "authorization_code", code: codeOf(a[13]), client_id: "c", scope: "openid email" }),
    (a) => formPost("/oauth2/v1/token", { grant_type: "authorization_code", code: codeOf(a[13]), client_id: "c" }),
    (a) => ({ path: "/oauth2/v1/userinfo", headers: bearer(json(a[14]).access_token) }),
    { path: "/oauth2/v1/userinfo", headers: bearer("forged") },
    { path: "/oauth2/v1/authorize?login=b&uid=00u2&redirect_uri=http://app/cb&state=s2" },
    (a) => ({ method: "DELETE", path: `/api/v1/users/${json(a[0]).id}` }),
    (a) => ({ path: `/api/v1/users/${json(a[0]).id}` }),
    (a) => ({ path: "/oauth2/v1/userinfo", headers: bearer(json(a[14]).access_token) }),
    (a) => ({ method: "DELETE", path: `/api/v1/users/${json(a[0]).id}` }),
    { path: "/oauth2/v1/keys" },
    { path: "/.well-known/openid-configuration" },
    { method: "POST", path: "/_mocksys/clock", body: { advance: 7200 } },
    { path: "/_mocksys/clock" },
  ],
  limited: [
    { path: "/api/v1/users" },
    { path: "/api/v1/users", headers: bearer("t1") },
    { path: "/api/v1/users" },
    { path: "/api/v1/users/00u1" },
    { path: "/api/v1/users" },
    { method: "POST", path: "/_mocksys/clock", body: { advance: 60 } },
    { path: "/api/v1/users" },
    { path: "/oauth2/v1/authorize?login=a&uid=00u1&redirect_uri=http://app/cb&state=s" },
    (a) => formPost("/oauth2/v1/token", { code: codeOf(a[7]), scope: "openid" }),
    (a) => ({ path: "/oauth2/v1/userinfo", headers: bearer(json(a[8]).access_token) }),
    { method: "POST", path: "/_mocksys/clock", body: { advance: 600 } },
    (a) => ({ path: "/oauth2/v1/userinfo", headers: bearer(json(a[8]).access_token) }),
    { method: "POST", path: "/_mocksys/clock", body: { advance: 1 } },
    (a) => ({ path: "/oauth2/v1/userinfo", headers: bearer(json(a[8]).access_token) }),
    { method: "POST", path: "/_mocksys/clock", body: { set: 1700000000 } },
  ],
};

function differences(i, req, a, b) {
  const out = [];
  const at = `#${i} ${req.method || "GET"} ${req.path}`;
  if (a.status !== b.status) out.push(`${at}: status ${a.status} vs ${b.status}`);
  if (JSON.stringify(a.headers) !== JSON.stringify(b.headers)) out.push(`${at}: headers ${JSON.stringify(a.headers)} vs ${JSON.stringify(b.headers)}`);
  if (a.body !== b.body) out.push(`${at}: body ${JSON.stringify(a.body).slice(0, 300)} vs ${JSON.stringify(b.body).slice(0, 300)}`);
  return out;
}

const [mode, aUrl, bUrl, corpusFile] = process.argv.slice(2);
const steps = mode === "corpus"
  ? JSON.parse(fs.readFileSync(corpusFile, "utf8")).map(({ method, path, headers, body }) => ({ method, path, headers, body }))
  : sequences[mode];
if (!steps || !aUrl || !bUrl) {
  console.error("usage: replay-diff.mjs corpus|okta|limited <a-url> <b-url> [corpus.json]");
  process.exit(2);
}

const answers = [];
const diffs = [];
for (const [i, step] of steps.entries()) {
  const req = typeof step === "function" ? step(answers) : step;
  const a = await send(aUrl, req);
  const b = await send(bUrl, req);
  answers.push(a);
  diffs.push(...differences(i, req, a, b));
}
const statuses = answers.map((r) => r.status).join(" ");
console.log(`${mode}: ${steps.length} request(s), ${diffs.length} difference(s); statuses ${statuses}`);
for (const d of diffs) console.log("  " + d);
process.exit(diffs.length ? 1 : 0);

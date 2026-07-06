# Feasibility Analysis: A mocksys Digital Twin of the Bifrost LLM Gateway (Enterprise)

**Date:** 2026-07-06 · **Verdict:** **Greenlight, scoped, by slice** (revised after an adversarial source-level review — see §8/§9):

- 🟢 **Slices 1–2** (non-streaming `/v1/chat/completions` + VK `find`-auth + control-plane CRUD) — clean fit, unblocks most customer tests.
- 🟡 **Slice 3** (rate limiting) — *not* a drop-in gene reuse: needs a small `crud-collection` extension (`:key-source` + `:miss`) plus a one-line compiler edit to rename the hardcoded `X-Rate-Limit-*` headers.
- 🟡 **Slice 5** (streaming) — canned single-body SSE only; true token-by-token streaming is impossible through Mountebank and requires a new mocksys transport module (§8.9).

> **Note on the sections below:** §1–§7 are the original feasibility pass. They contain optimistic claims that the adversarial review (§9) corrected — most importantly, the "compose seven genes" framing in §2/§6 is wrong (only three are composable gene *functions*; the rest are opts-keys, params, or auto-enabled). Read §8 (proposed engine/gene work) and §9 (factual corrections) as authoritative where they conflict with §1–§7.

---

## 1. What Bifrost is (grounding)

Bifrost (github.com/maximhq/bifrost) is a high-performance LLM gateway in Go by Maxim AI that unifies 23+ providers (OpenAI, Anthropic, Bedrock, Vertex, Azure, Cohere, Mistral, Ollama, …) behind one **OpenAI-compatible** HTTP surface, plus **drop-in provider prefixes** that emulate each provider's native API. It ships an HTTP server binary (`bifrost-http`, default **:8080**), a Go SDK, and a plugin middleware pipeline. Key surfaces relevant to a twin:

- **Inference:** `POST /v1/chat/completions` (primary, confirmed), plus plausibly `/v1/completions`, `/v1/embeddings`, `/v1/images/*`, `/v1/audio/*`, `/v1/rerank`, `GET /v1/models` (single-source, unverified).
- **Drop-in integrations** (confirmed shapes): `POST /openai/v1/chat/completions`, `POST /anthropic/v1/messages`, `POST /genai/v1beta/models/{model}:generateContent`, and prefixes for groq/mistral/cohere/bedrock/ollama/… Each returns that provider's **native** response shape.
- **Control plane** `/api/*`: `POST /api/providers` (confirmed — body carries `provider` + `keys[]` with `value`/`models`/`weight`), `/api/governance/virtual-keys` (confirmed). Single-source/unverified: `/api/config`, `/api/logs`, `/api/mcp`, `/api/users|teams|roles`, `/api/audit`, `/api/session/login|logout`, `/oauth/callback`.
- **MCP:** `POST /v1/mcp/tool/execute` (confirmed); MCP client/server config routes (unverified).
- **Ops/UI:** `GET /metrics` (Prometheus, confirmed), health endpoints (unverified), Web UI at `/`.
- **Auth to Bifrost:** virtual key via **`x-bf-vk: sk-bf-*`** header (confirmed), also `Authorization`/`x-api-key`/`x-goog-api-key`; dashboard uses Bearer session token or HTTP Basic. VKs sit in a **Virtual Key → Team → Customer** hierarchy with budgets + **VK-level** request/token rate limits.
- **Response envelope:** OpenAI-schema compatible, plus a Bifrost **`extra_fields`** object with `provider`/`latency`/`cost`/`billed_usage` (single-source, exact keys unverified — the main deviation from a pure OpenAI mirror).
- **Enterprise-only** (per docs.getbifrost.ai/enterprise/overview): audit logs, SSO/OIDC + directory sync (Okta/Entra/Keycloak/…), RBAC + row-level access, guardrails (content safety, secrets/regex redaction), Access Profiles, HA clustering, adaptive load balancing. Virtual keys + governance budgets themselves are documented as **open-source core**; the audit/RBAC/SSO layer on top is Enterprise.

**Load-bearing verification gaps** (from web research — fix by cloning `transports/bifrost-http/`): exact `/api/*` route inventory beyond providers+VKs; the streaming SSE wire format (`[DONE]`, per-chunk `extra_fields`); the error-body JSON schema and any `X-RateLimit-*` headers; exact `extra_fields` key names.

---

## 2. What mocksys can actually do (verified in-repo)

From `docs/effects.md`, `docs/contracts.md`, `docs/twins.md`, and `src/mocksys/kits.cljs`, mocksys is a declarative (no user-authored JS) wrapper over Mountebank. Its building blocks:

| Capability | Primitive | Bifrost relevance |
|---|---|---|
| Static responses + request-branching | `operations` with `query`/`header`/`body` predicates, ordered specific-first | Route by `model` field, by presence of `x-bf-vk`, error vs happy path |
| Reflect request → response | `{{request.query/headers/body/path/method}}` (Mountebank `copy`) | Echo `model`, `id`, request fields into a canned completion |
| Named examples / round-robin / schema sampling | `responses.examples` with `select` | Multiple canned completions, swap happy↔error |
| **Stateful CRUD collections** | `create/get/update/remove/list` + SCIM-ish `filter=` + cursor pagination | providers, virtual-keys, teams, customers, MCP clients config |
| Buckets + TTL handshake | `store/consume/lookup` (+ `ttl` on the virtual clock) | dashboard session login; single-use codes; token expiry |
| Seed/collection reads (non-terminal) | `resolve`, `find` (by key/field, optional filter gate) | VK auth: `find` the VK row, gate on `status`/budget, else 401 |
| Many-to-many relations | `link/unlink/listJoin` (idempotent, FK-guarded, hydrated + paginated) | VK↔Team, VK↔Customer, Team↔Customer, VK↔allowed-model |
| **Signed JWTs** | `sign` (RS256, typed claims, `require('crypto')`) + published JWKS + `/.well-known` discovery | Enterprise SSO/OIDC login mock; dashboard bearer tokens |
| Rate limiting | `limit` gene → `429` + `X-Rate-Limit-*`, per-key, virtual-clock window | VK request-rate limits (per-request budget) |
| Deterministic time | virtual clock (`mocksys clock advance/set`) | Budget reset windows, token expiry, rate-limit windows |
| Transport faults | `fault`: `status`/`latency`/`timeout`/`drop-connection`/`malformed-json` | Simulate upstream provider failure, latency, timeouts |
| HTML from live state | `render` | Not needed (Bifrost has no server-rendered picker) |
| Fidelity + assertion | `conform` (golden or diff-vs-real, field-masked), `assert --saw` | Validate the twin against a real Bifrost; CI oracle |
| Composition | **genes** merged by a **kit** ClojureScript function | A `kit bifrost` composes these as `kit okta` does — **but see the correction below** |

> ⚠️ **Correction (§9.1):** only **three** of the "genes" are composable functions returning `{:operations :collections :seed}`: `crud-collection` (`kits.cljs:42`), `oidc-handshake` (`kits.cljs:262`), `membership` (`kits.cljs:281`). The others advertised by `mocksys genes` are **not** independently composable: `error-envelope` is a hardcoded `okta-error` helper (`kits.cljs:35`); `rate-limit` is an opts-key *inside* `crud-collection` (`kits.cljs:51`); `jwt-signing` is a param inside `oidc-handshake` (`kits.cljs:189`); `virtual-clock` is auto-enabled by op inspection (`inject.cljs:363`), not code you compose. The `genes` vector (`kits.cljs:541`) is a doc list printed by `mocksys genes`, not a builder registry. The Bifrost kit will hand-author more bespoke effects than a naive "compose seven genes" reading implies.

**Hard limits that shape scope:**

1. **No streaming / SSE.** Confirmed absent — no `text/event-stream`, no chunked/timed emission anywhere in `src/` or `docs/`. This is the single biggest gap for `stream: true`.
2. **Interpolation yields strings only.** Numbers/arrays in a JSON response need `sign`'s typed claim directives or `{var:}`; a canned `usage` object with integer token counts must be embedded as a literal, not computed.
3. **No arithmetic / accumulation except the rate-limit counter.** The `limit` gene counts **requests** per window. It cannot sum `usage.total_tokens` or dollar cost across requests. So **token/cost budgets are not faithfully enforceable** — only approximable as "N requests per window."
4. **One terminal verb per operation.** An op does one collection mutation and returns; it can't, as a side effect, append an audit-log row while also serving a config write. Audit logs can be **seeded and read back**, not auto-captured.
5. **No computation / similarity.** Semantic-cache similarity, real inference, and adaptive load-balancing decisions are out of scope (expected).

---

## 3. Fit assessment — Bifrost surface × mocksys blocks

### Natural fit 🟢 (this is most of the value)

- **Client-facing chat contract, non-streaming.** `POST /v1/chat/completions` → a stateful op that `find`s the VK (401 on miss), then `respond`s an OpenAI-shaped body with a canned/round-robin `choices[].message.content`, `model` echoed via `{{request.body.model}}`, a literal `usage` object, and a literal **`extra_fields`** (`provider`/`latency`/`cost`/`billed_usage`). Deterministic canned completions are explicitly acceptable here.
- **Virtual-key auth.** `x-bf-vk: sk-bf-*` → `bind {header, bearer}` then `find` in a `virtual_keys` collection with an optional filter gate (`status eq "active"`) — this is *exactly* the Okta OIDC bearer/`find` idiom already shipping.
- **Control-plane CRUD.** `/api/providers`, `/api/governance/virtual-keys`, teams, customers, MCP clients → `crud-collection` genes with SCIM filter + pagination. The provider `keys[]`/`weight`/`models` sub-objects round-trip as nested JSON.
- **Governance hierarchy state.** VK→Team→Customer → collections + `membership`/`link`; budget and rate-limit settings live as VK fields you can read back and filter on.
- **Error envelope + rate limiting.** `error-envelope` gene shaped to Bifrost's error JSON; `rate-limit` gene for VK request limits → `429` (header names may need renaming from `X-Rate-Limit-*` to match Bifrost).
- **Drop-in provider prefixes.** `/openai/v1/chat/completions`, `/anthropic/v1/messages`, `/genai/…:generateContent` → separate ops returning provider-shaped canned bodies.
- **Enterprise SSO/OIDC.** mocksys already mints RS256 JWTs + JWKS + discovery (`sign` verb, `oidc-handshake` gene). Bifrost's dashboard login (`/api/session/login` → bearer) maps to a bucket handshake; if the twin must *stand in for the IdP* Bifrost logs into, the existing Okta OIDC machinery is directly reusable.
- **Faults for reliability paths.** `fault` (latency/timeout/drop-connection/500) simulates upstream provider failure — enough to exercise a client's retry/timeout handling against the gateway.

### Hard or out of scope 🟡/🔴

- **Streaming SSE (`stream: true`)** 🔴→🟡. No native support. Workaround: return a canned body with `Content-Type: text/event-stream` containing pre-baked `data: {chunk}\n\ndata: [DONE]\n\n` frames. Buffered SSE parsers will still parse the frames, but you **lose token-by-token timing** and it's one Mountebank response, not a live stream. Acceptable for "does my client parse chunks and reassemble" tests; not for backpressure/latency-of-first-token behavior. This is the headline limitation. **True streaming is impossible through Mountebank — but achievable via a new mocksys transport module. See §8.9 for the full analysis of what it would take.**
- **Token/cost budget enforcement** 🟡. Budget *state* is readable/filterable, but live "spend $X → 429 budget_exceeded" can't be computed (no cross-request token summation). Approximate with the request-count `limit` gene and document the divergence.
- **Real provider fallback sequencing** 🟡. "Fail provider A twice, then succeed on B" needs a counter with branching that mocksys doesn't offer (the only counter is `limit`, which only emits 429). You can do *static* fallback outcomes (a provider that always 500s via `fault`, VK reports which provider "served" via `extra_fields`), but not stateful N-fail-then-succeed sequences.
- **Live Prometheus `/metrics`** 🟡. Serve a canned exposition-format text body; counters won't move. Fine for "scrape endpoint exists and parses," not for asserting real metric values.
- **Semantic caching, adaptive load balancing, real inference, guardrails/content-safety** 🔴. Out of scope — expected for any twin. A deterministic exact-match "cache hit" flag can be faked with a bucket if a test needs the cache-hit code path.
- **Audit-log auto-capture** 🟡. Serve a seeded, filterable audit collection; it won't auto-append on config mutations (one-terminal-verb limit). **See §8.1 — a proposed read-phase `append` verb removes this limit.**
- **Inference-request logging (`GET /api/logs`)** 🟡 — *missed in the original pass.* Bifrost logs every inference call, and a customer's admin code may `GET /api/logs` expecting to see the request it just made. Same one-terminal-verb problem as audit logs: the chat op can't serve a completion *and* append a log row. Arguably more central to a gateway twin than audit logs. Also addressed by the proposed `append` verb (§8.1).

---

## 4. What a useful twin should mock (the client contract)

The point is to let a customer's code that **calls** Bifrost run in tests without a real Bifrost + real providers. Priorities:

1. **`/v1/chat/completions` request+response contract** (non-streaming first): OpenAI-shaped body, `model` echoed, literal `usage`, the `extra_fields` envelope, VK auth, OpenAI/Bifrost error schema on bad/missing VK.
2. **Virtual-key auth + governance control-plane**: create a VK, use it, hit its rate limit, read its budget — the full admin loop a customer automates.
3. **Streaming** (best-effort canned event-stream) so streaming clients at least exercise their parser.
4. **Drop-in prefixes** for customers who point an existing OpenAI/Anthropic SDK at Bifrost.
5. **Reliability affordances**: `429` + rate-limit headers, `fault`-driven upstream errors, `extra_fields.provider` to show which provider "served."

Deterministic canned/templated completions are the design intent — no inference.

---

## 5. Enterprise-specific angle

| Enterprise feature | Twin approach | Fidelity |
|---|---|---|
| Virtual keys + budgets | `crud-collection` + VK fields; budget as readable state; `rate-limit` for request caps | 🟢 state / 🟡 live token-budget enforcement |
| Governance hierarchy (VK→Team→Customer) | collections + `membership`/`link`, filterable | 🟢 |
| SSO / OIDC login | reuse `oidc-handshake` + `sign` (RS256 JWKS/discovery) — already built for Okta | 🟢 |
| Audit logs | seeded, filterable collection (read-back) | 🟡 no auto-append |
| RBAC / row-level access | encode roles as fields; gate reads with `find` + filter | 🟡 coarse |
| Guardrails / content safety | canned "blocked" response branch by request body predicate | 🟡 pattern-match only |
| Clustering / HA / adaptive LB | not modeled (single imposter) | 🔴 out of scope |

---

## 6. Recommended build order (tracer-bullet slices, mirroring how `kit okta` was staged: CRUD → membership → OIDC → JWT signing → conform)

- **Slice 1 — Core client contract. ✅ DONE (2026-07-06).** `POST /v1/chat/completions`, non-streaming: VK auth (`find` by `value` field in the live `virtual_keys` collection with a `status eq "active"` gate, 401 on miss), canned OpenAI-shaped response with echoed `model`, minted `chatcmpl-` id, literal `usage`, literal `extra_fields`, OpenAI-shaped error envelope. Impl: `kits.cljs` `bifrost`/`chat-completions-op`; tests: `test/mocksys/bifrost_kit_test.cljs`. Verified through real Mountebank.
- **Slice 2 — Control plane. ✅ DONE (2026-07-06).** `crud-collection` genes for `/api/governance/virtual-keys` (id-prefix `vk_`, defaults `status active`) and `/api/providers` (nested `keys[]`/`weight`/`models` round-trip). The chat op authenticates against the **same live collection** the control plane writes — *create-a-key-then-use-it verified end-to-end*. Known gap: created VKs carry a client-supplied `value` rather than an auto-minted secret (a future `crud-collection` extension, §8).
- **Slice 3 — Governance + limits. ✅ RATE LIMITS DONE (2026-07-06).** Per-VK request budget on the inference op (`limit` keyed on `{vk}`) → `429` with a Bifrost-shaped error envelope and OpenAI-style `x-ratelimit-*-requests` headers, over the virtual clock. Landed the two flagged prerequisites: the **`limit-js` engine edit** making header names configurable (`inject.cljs`, defaults preserved for okta) and the **§8.3 `crud-collection` extension** (`:key-source` + `:miss` + `:headers`). Opt-in via `--rate-limit N [--rate-window S]`. Verified end-to-end. *Still TODO for Slice 3:* `teams`/`customers` collections + `membership` linking VK→Team/Customer (governance hierarchy).
- **Slice 4 — Drop-in prefixes + secondary inference routes.** `/openai/v1/chat/completions`, `/anthropic/v1/messages`, `/genai/…:generateContent`; canned `/v1/models`, `/v1/embeddings`.
- **Slice 5 — Streaming + reliability.** Best-effort canned `text/event-stream` body; `fault` overlays for upstream latency/timeout/500; canned `/metrics` and health.
- **Slice 6 — Enterprise + fidelity.** Dashboard session-login bucket handshake; optional OIDC-IdP stand-in via existing `sign`/JWKS; seeded audit-log readback; author a **`conform` corpus** and diff against a real Bifrost (`conform bifrost/bifrost --against http://real:8080`) to drive the twin to zero observable delta.

Package as a `kit bifrost` builder in `src/mocksys/kits.cljs`. **Correction (§9.2):** this is *not* "no new engine primitives for slices 1–4/6." Slice 3's rate limiter needs a `crud-collection` extension **and** a one-line `inject.cljs` edit to rename the hardcoded `X-Rate-Limit-*` headers (`inject.cljs:348-354`) — that is an engine edit. Slices 1–2 and 6 are genuinely gene-composition + bespoke effects. The recommended engine/gene work is consolidated in §8.

---

## 7. Risks, open questions, tensions

- **Streaming is the defining constraint.** If the customer's primary use of Bifrost is token-by-token streaming UX, the twin's value drops materially. Decide early whether buffered-frame fidelity is enough. Real streaming is **not** a "future compiler primitive" — Mountebank's buffered single-response model makes it impossible in-transport; it requires a dedicated streaming responder in mocksys itself (§8.9).
- **Unverified surface.** Before locking routes, clone `transports/bifrost-http/` to confirm: exact `/api/*` inventory, the SSE framing (`[DONE]`, per-chunk `extra_fields`), the **error-body schema**, `X-RateLimit-*` header names, and the exact `extra_fields` key names. These four are the only load-bearing items web docs couldn't fully pin down.
- **Budget/token accounting mismatch.** mocksys counts requests, not tokens/dollars. Live budget-exhaustion tests will be approximate; document it or push a small "counter-by-field" enhancement if a customer needs true budget enforcement.
- **Enterprise vs OSS boundary is fuzzy in the docs** (virtual keys/governance read as OSS; audit/RBAC/SSO are Enterprise). If "Enterprise edition" specifically means audit + RBAC + SSO + clustering, note that clustering is out of scope and audit is read-back-only — the *governance/VK* core (the most test-relevant part) is well within reach regardless of edition.
- **New verbs — several are worth adding** (contradicts the original "likely none needed"). The rate-limit header rename already touches the compiler; and §8 argues that a small set of new genes/verbs (`append`, weighted `limit`, `crud-collection` params) materially improve the Bifrost twin *and* every future twin. See §8.

**Bottom line:** The **control plane and non-streaming client contract are a strong, natural fit** — arguably a better fit than Okta was, since Bifrost's admin surface is plain CRUD + bearer-key auth and its inference responses are OpenAI-shaped JSON that canned/templated bodies reproduce well. The reasons this is 🟡 not fully 🟢 are all containable: streaming (canned-frame workaround), token/cost budgets (request-count approximation), and real fallback sequencing (static substitutes). **Recommend greenlight**, scoped to the client contract + governance control-plane, with streaming fidelity flagged as the key expectation to set with stakeholders.

---

## 8. Proposed engine / gene work (ranked by value ÷ effort)

Output of the adversarial source-level review. Every item cites the code it changes. Ranked so the top items are cheap wins that unlock the most twin surface; each is reusable beyond Bifrost.

### 8.1 `append` — a read-phase, non-terminal audit/log write verb  ·  **HIGHEST (high value, low effort)**
The "one terminal verb per operation" limit is really "one verb that *returns*." A read-phase `append` that mints an id and `put`s a row into an audit/logs collection, then **falls through** to the real terminal verb, sidesteps the barrier entirely — it runs *before* the terminal, exactly like `find`/`link` already compose in the read phase (`inject.cljs:443-448`).
- **Engine work:** add `append` to `effect-body-js` emission (mint id + `coll(state,X).put(row)`); no terminal-dispatch changes.
- **Payoff:** delivers **both** Bifrost audit-log auto-capture *and* `GET /api/logs` request logging (§3, §4) in one verb.
- **Reuse:** every future twin with an audit trail or request log.

### 8.2 Weighted `limit` (add a `weight` to the counter)  ·  **HIGH (high value, low effort)**
`limit-js` currently does `_c = (state[..][_lk]||0)+1` (`inject.cljs:346`). Replace the `1` with a `weight` defaulting to 1 but accepting `{const: N}` or `{body: "max_tokens"}`. Turns the request-counter into a **token/spend accumulator with a cap** → resurrects the "spend $X → 429 budget_exceeded" path the plan wrote off as impossible (§3, §7).
- **Caveat:** weight comes from the *request* (e.g. `max_tokens`), not the canned response `usage` — approximate but far closer than "N requests per window."
- **Reuse:** any weighted-quota API.

### 8.3 Parameterize `crud-collection`'s rate-limit `:key-source` + `:miss`  ·  ✅ **DONE (2026-07-06)** · **HIGH (correctness prerequisite for Slice 3)**
Today the embedded rate limiter hardcodes the client key to `{"header":"authorization","bearer":true}` (`kits.cljs:58`) and omits `miss`, so `limit-js` falls back to the **Okta** 429 body `errorCode E0000047` (`inject.cljs:340-343`). For a Bifrost twin that (a) buckets on the wrong header — Bifrost uses `x-bf-vk: sk-bf-*`, no bearer prefix — and (b) returns an Okta-shaped error. Let `:rate-limit` accept `:key-source` and `:miss`.
- **Effort:** trivial. **Not optional** — Slice 3 is wrong without it.
- (A *bespoke* op can already hand-write a `limit` block with correct `key`/`miss` — `inject.cljs:340` — so this only fixes the crud-collection-embedded path.)

### 8.4 Promote `error-envelope` to a real parameterized gene  ·  **MEDIUM-HIGH (low effort)** · ⬖ **PARTIAL (2026-07-06)**
`okta-error` (`kits.cljs:35-38`) is a hardcoded helper. Promote it to a gene taking an envelope template + variants: OpenAI (`{error:{message,type,code,param}}`), Anthropic (`{type:"error",error:{type,message}}`), Bifrost. ✅ The `X-Rate-Limit-*` **header-name parameterization** (`inject.cljs`) landed with Slice 3. ◻ Still TODO: the per-provider error-envelope gene itself — bifrost currently uses a local `bifrost-error` helper mirroring `okta-error`.

### 8.5 `sse-canned` gene (no engine change)  ·  **MEDIUM (low effort)**
Given a list of content chunks, build a literal `text/event-stream` body of `data: {chunk}\n\n … data: [DONE]\n\n` frames with `{{request.body.model}}` spliced in. Pure static response — no compiler work. Packages the §3 workaround as a reusable gene. Document the no-timing caveat (§8.9).

### 8.6 Typed interpolation in `respond.json`  ·  **MEDIUM (medium effort)**
Reuse `claim-val-js` (`inject.cljs:215-236`) inside `resp-js`/`deepInterp` so a response body can carry `{now:}`, `{var:}`, or an `{int: "{x}"}` directive. This lets `usage` / `extra_fields.latency` / `cost` be **computed** (echo `max_tokens`, stamp a virtual-clock latency) instead of frozen literals — fixing the §9.5 limitation that typed directives are currently reachable *only* inside `sign`. Touches the hot response path; needs care around the string-only `interp` contract (`inject.cljs:482`).

### 8.7 `fallback-sequencer` (N-fail-then-succeed)  ·  **MEDIUM (higher effort)**
Built on 8.2's counter plus a new terminal that **selects a response by counter value** (branch-on-computed-value, which the static-predicate model can't express — `contracts.md:75`). Enables real retry/fallback testing ("fail provider A twice, then succeed on B"). Most engine-invasive of the gene set.

### 8.8 Per-slice engine-work summary
| Slice | Needs | New/extended |
|---|---|---|
| 1 Core chat contract | bespoke `bind`→`find`→`respond` | none (compose + literals) |
| 2 Control plane | `crud-collection` genes | none |
| 3 Governance + limits | correct 429 | **8.3 (required)** + **8.4** header rename |
| 4 Drop-in prefixes | bespoke ops | none |
| 5 Streaming + reliability | canned SSE / faults | **8.5**; real streaming → **8.9** |
| 6 Enterprise + fidelity | audit/logs, conform | **8.1** for auto-capture |

### 8.9 True streaming — what it would actually take  ·  **RESEARCH, not roadmap**
mocksys emits **Mountebank** imposters, and a Mountebank stub returns a **single buffered response object** `{statusCode, headers, body}` (every `resp-js`/`collResp` return, e.g. `inject.cljs:113, 526-527`). Even an injected function *returns* one response and never touches the socket — so `wait`/`decorate`/`shellTransform` all buffer, and **time-separated SSE flushing is impossible in the Mountebank transport.** This is a genuine architectural wall, not a missing verb.

Three honest options, in order of realism:
1. **Canned single-body SSE (8.5).** Ship this. Buffered parsers reassemble the frames; timing is lost. Zero engine work.
2. **Dedicated streaming responder inside mocksys.** mocksys is already a Node process (ClojureScript on Node). Stand up a minimal `http.createServer` (or Mountebank `tcp` imposter + manual HTTP framing) that owns **only** the streaming routes, flushes `res.write()` per chunk with real delays driven by the **virtual clock**, and lets Mountebank keep every other route. Requires: a new transport module, a routing split (streaming routes bypass MB), and `run`-lifecycle integration. Medium-large effort, but **reusable for any SSE/chunked API** — OpenAI, Anthropic, and Bifrost streaming at once. This is the real path if streaming fidelity matters across twins.
3. **Fork/patch Mountebank.** Not recommended — maintenance burden, and option 2 is cleaner.

**Recommendation:** ship option 1 for the Bifrost twin; treat option 2 as a standalone mocksys transport RFC justified by demand across *all* LLM twins, not Bifrost alone.

---

## 9. Factual corrections applied (adversarial review, source-verified)

Each correction cites `file:line` and is reflected inline above.

1. **"Compose seven genes" — overstated.** Only `crud-collection` (`kits.cljs:42`), `oidc-handshake` (`kits.cljs:262`), `membership` (`kits.cljs:281`) are composable gene functions. `error-envelope`/`rate-limit`/`jwt-signing`/`virtual-clock` are a helper / opts-key / param / auto-behavior respectively. (§2)
2. **Rate-limit header rename is a compiler edit, not config.** `X-Rate-Limit-Limit/-Remaining/-Reset` are hardcoded literals in `limit-js` (`inject.cljs:348-354`) — no parameter. Contradicts "no new engine primitives for slices 1–4/6." (§6, §7)
3. **`crud-collection`'s built-in rate limiter is wrong for Bifrost twice over:** emits the hardcoded Okta 429 body (`inject.cljs:340-343`) and buckets on `authorization`+bearer (`kits.cljs:58`) instead of `x-bf-vk`. Fix = 8.3. (§3.3)
4. **VK auth bind is not the Okta bearer idiom.** Okta strips `Bearer `/`token ` (`inject.cljs:474`); Bifrost's `x-bf-vk` carries the raw `sk-bf-*`, so the bind is `{vk:{header:"x-bf-vk"}}` with no `bearer:true`. `find` then works as claimed.
5. **Typed directives (`{var:}`/`{now:}`) are reachable only inside `sign`** (`claim-val-js`, `inject.cljs:215-236`). A `respond.json` body goes through string-only `deepInterp`/`interp` (`inject.cljs:482-485`), so `usage` token counts must be literals — the plan's conclusion was right, its stated mechanism was wrong. Fix = 8.6.

**Claims that checked out:** one-terminal-verb (`inject.cljs:317-327`); `limit` counts requests (`inject.cljs:346`); no SSE (absent everywhere); audit read-back-only; `find`/`link`/`sign` non-terminal composition (`inject.cljs:443-448`).

---

## Sources

**Bifrost:** github.com/maximhq/bifrost · docs.getbifrost.ai (overview, `/features/governance`, `/enterprise/overview`, `/integrations/what-is-an-integration`, `/quickstart/gateway/setting-up`) · www.getmaxim.ai/bifrost · deepwiki.com/maximhq/bifrost · pkg.go.dev/github.com/maximhq/bifrost/transports/bifrost-http

**mocksys internals:** `docs/effects.md` (effect language / verbs) · `docs/contracts.md` (contract format, templating, faults) · `docs/twins.md` (kits, genes, conform loop) · `src/mocksys/kits.cljs` (gene/kit impl) · `src/mocksys/inject.cljs` (effect compiler / JWT / limit) · `src/mocksys/mb.cljs` (thin Mountebank admin-API client — the transport that constrains streaming) · `.claude/skills/mocksys/SKILL.md` (skill entry point)

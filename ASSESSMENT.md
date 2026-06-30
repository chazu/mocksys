# Assessment: stateful auth-flow mocks + de-throning recording

Two questions, examined against `~/dev/go/snooker`'s bespoke mocks and the current
mocksys codebase.

> **Status: implemented (all four phases).** See "## Status — what shipped" at the
> end. The headline result: `mocksys kit oauth2 --provider github` stands up a
> snooker-equivalent stateful OAuth provider (account picker, single-use codes,
> token table, bound userinfo) in one command, authored declaratively — no
> hand-written JavaScript, no recording.
>
> **Follow-on (digital-twin substrate):** this groundwork was extended to clone
> *large* stateful services — resource collections (CRUD + SCIM filter +
> pagination), composable behavioral *genes*, a flagship `kit okta`, and a
> `conform` fidelity harness. See `PLAN-DTU.md` for that plan and its shipped status.

---

## Part 1 — What makes snooker's mocks good

Snooker ships two hand-written mock servers and wires them into its dev loop:

- **`cmd/mockoauth`** — a local stand-in for GitHub + Codeberg OAuth2. Three
  endpoints that *share state*: `authorize` mints a single-use `code` and stores
  `code→grant`; `access_token` consumes the code (deletes it), mints a `token`,
  stores `token→grant`; `user` validates the bearer `token` and returns the bound
  user. ~250 lines.
- **`internal/idp`** — snooker as an OIDC *relying party* to Zitadel (not a mock,
  but the consumer the mock must satisfy).

What gives these mocks their utility/sophistication — the bar mocksys must clear:

1. **Shared state across endpoints.** Mint here, consume there. Single-use codes,
   a live token table. (`server.codes`, `server.tokens`, mutex.)
2. **Dynamic, computed responses.** Tokens/codes generated per request
   (`randToken`); `scope` echoed back; `userinfo` returns the user *bound to the
   presented token*.
3. **Request-conditional branching beyond method+path.** `authorize` with no
   `?login` renders an HTML picker; with `?login=octocat` it 302-redirects with a
   `code`; unknown login → 400. `access_token` with a bad code → 400 JSON.
4. **Redirects with constructed Location** carrying `code` + `state`.
5. **Seed data, overridable.** A `seedUsers` table; `-users file.json` swaps it.
6. **Protocol conformance.** It's a *valid* generic OAuth2 upstream, so a real
   OIDC provider (Zitadel) federates to it (`zitadel-bootstrap` registers the
   three endpoints as a generic-OAuth IdP).
7. **Dev-loop integration.** Runs as a Tilt `local_resource` (build+serve,
   hot-reload on save), pinned to `:9099`, seeded accounts printed at `/`,
   browser-vs-in-container base-URL split, exercised by `httptest` unit tests and
   `smoke.sh`.

**The essence:** these are *seeded, stateful, behavioral* stand-ins authored from
knowledge of a protocol — **not** recordings of real traffic. You could never
record mockoauth into existence; its value is the state machine.

---

## Part 2 — Why mocksys can't build these today

mocksys is further along than its README suggests — it already has a full
contract-authoring path (`new`/`add`/`import`/`examples`/`use`/`fault`/
`parameterize`, contract-as-source per PLAN.md). But the **contract model is
structurally incapable** of expressing any of the seven properties above.

The ceiling, in code:

- `contract/predicates-for` (contract.cljs:58) emits **only** `method` + `path`
  predicates. There is no way to branch on a query param, body field, or header —
  so the picker-vs-redirect fork (#3) and bearer-validation (#1) are inexpressible.
  `analyze/intended-match-fields` (analyze.cljs:23) is hard-coded to
  `#{"method" "path"}` and `doctor` actively *flags anything else as overfit* — the
  tool fights request-conditional matching rather than supporting it.
- `contract/operation-responses` (contract.cljs:90) emits static `{is {...}}`
  responses only. No templating, no echoing request fields (#2, #4). `add` can't
  even set an arbitrary response header (only `Content-Type`, contract.cljs:196).
- There is **no state primitive at all** (#1, #5). Contracts are a stateless set
  of request→response pairs. `mb/proxy-imposter` and the whole store layout assume
  static fixtures.
- Mountebank *does* support all of this — query/body/header predicates,
  `copy`/`lookup` behaviors, and `inject` with a persisted `state` object — but
  mocksys wires **none** of it (`mb.cljs` only posts static imposters; injection is
  never enabled). idea.md explicitly deferred "complex stateful mocks" and
  "custom JavaScript injection" and defaulted `allow_injection: false`.

So an agent asked to "build me a mockoauth equivalent" with today's mocksys
cannot. It can record a *single* captured `/user` response, but not a flow.

---

## Part 3 — The recording over-reliance is real, but it's a *framing* problem

Implementation: recording is **not** load-bearing — authoring works without it.
But everything that *orients* an agent points at recording first, which (a) steers
agents toward the one path that requires live credentials they usually don't have,
and (b) caps sophistication, because recording can *only* ever yield static
request/response pairs — the exact thing that can't produce a snooker-class mock.

Where the bias lives:

- **README** headline: "turns real API traffic into reusable, scrubbed mock
  fixtures." The entire "Why" section is about recording/scrubbing.
- **`cmd-prime`** (core.cljs:645) — the agent's orientation — leads with the
  record→freeze→run "Core loop." Authoring is the *third* section, literally
  titled "Author from a spec / docs / source (no recording)."
- **`usage`** lists record/freeze/run first.
- **`cmd-init`** only materializes a *service profile* (redact/volatile/target —
  all recording concerns); its success message says "record a scenario with…".
- **`cmd-ls`** empty state: "record one with `mocksys record …`".
- **Service templates** (service.cljs) encode *only* recording knobs
  (`redact_headers`, `volatile_headers`, `default_target`). They carry nothing
  about an API's shape — so `init` can't seed authoring, only proxying.

Recording's actual, narrow value: a convenient way to *seed a contract's examples
from reality when you have live access*. It should feed authoring, not be the
front door.

---

## Part 4 — The plan

Four themes. Themes A+B raise the ceiling to snooker-class; C matches snooker's
dev ergonomics; D repositions recording. Phasing at the end.

### Theme A — Raise the expressiveness ceiling

**A1. Request-conditional predicates (static, no injection).**
Extend an operation's `request` with optional `query` / `headers` / `bodyMatch`
predicate specs (`present` / `equals` / `matches`), and allow multiple operations
on the same method+path to branch on them.
- `contract/predicates-for` → emit `exists`/`equals`/`matches` predicates for the
  new fields.
- Teach `analyze`/`doctor` provenance: authored predicates are intentional, not
  overfit — only *recorded* extra predicates get flagged.
- *Unlocks:* picker-vs-redirect (`?login` present?), bearer-present checks, bad-code
  branch. This single change is the highest leverage and ships with zero injection.

**A2. Response templating via Mountebank `copy`/`lookup` (no injection).**
Support `{{request.query.X}}` / `{{request.body.X}}` / `{{request.headers.X}}`
interpolation in example bodies and headers; compile to Mountebank `copy`
behaviors. (idea.md item #7 already drew this: `body: "{{ request.body.title }}"`.)
- *Unlocks:* echo `state` back on the redirect Location, echo `scope`, reflect a
  posted `title` — everything snooker does with `randToken`/field-echo except the
  cross-request state.

**A3. Stateful flows — the differentiator (opt-in injection, *generated* JS).**
Add a declarative behavior layer to the contract:
```yaml
state: { codes: {}, tokens: {} }          # named buckets, seeded
operations:
  - id: authorize
    effects: [ {mint: code, store: {codes: {key: "$code", value: "$login"}}} ]
  - id: token
    effects: [ {consume: {codes: {key: "$body.code"}}, mint: token,
                store: {tokens: {key: "$token", value: "$consumed"}}} ]
  - id: userinfo
    effects: [ {lookup: {tokens: {key: "$headers.authorization"}}} ]
```
Compile these to a Mountebank `inject` response over the shared `state` object.
The injected JS is **generated by mocksys from the declarative effects — never
hand-written by the agent**, which is what keeps it agent-safe and dodges the
footgun idea.md rightly flagged. Gate behind an explicit `allow_injection`
(per-store config or per-contract `requiresInjection: true`); `mb/spawn-daemon!`
+ `mb-launcher` gain `--allowInjection` when any live scenario needs it.
- *Unlocks:* code→token→userinfo handshake, single-use codes, the full mockoauth.

**A4. Arbitrary response headers from the CLI.**
`add` (and a new `header` edit) take `--header K=V`; 302 redirects with a
`Location` become first-class. Small, removes a sharp edge.

### Theme B — Reusable kits (the agent-facing proof)

**B1. Scenario kits.** Parameterized, stateful mock generators shipped with
mocksys, built *on* A1–A3 (so the kit is the proof the primitives suffice). New
`kits.cljs` + `cmd-kit`. Flagship:
```
mocksys kit oauth2 --provider github --users users.json --port 9099
```
emits a complete, runnable scenario that behaves like snooker's mockoauth
(authorize/picker/redirect, access_token, userinfo, single-use codes). Further
kits: `oidc-provider`, `session-cookie-auth`, `jwt-bearer`, `webhook-receiver`.
This is the deliverable that makes "build me a mockoauth" a one-liner.

**B2. Seed data as a first-class input.** A `seed:` block (users, clients,
accounts) that templating (A2) and state (A3) read; `--users file.json`-style
overrides. Mirrors mockoauth's `seedUsers` + `-users`.

### Theme C — Dev-loop integration (match the Tilt experience)

**C1. Stacks / `mocksys up`.** Bring up a *named set* of scenarios as a persistent
stack on pinned ports with one env handoff (`mocksys up auth-stack` / `down`).
Today `run` is per-scenario, auto-port. A stack is a small YAML of
scenarios+ports+env.

**C2. Pinned ports.** Persist a stable port on a scenario/stack (mockoauth is
always `:9099`). `run --port` exists but isn't remembered.

**C3. Watch mode.** `mocksys run --watch` recompiles+restarts on `contract.yaml`
change — the Tilt "save a file, service reloads" loop, via `fs.watch`.

**C4. Integration emitters.** `mocksys env --tilt | --compose | --json` emits a
Tilt `local_resource`, a compose service, or JSON env (idea.md #9/#10, partly
unbuilt) so a mock drops straight into a dev stack.

### Theme D — De-throne recording

**D1. Reframe `prime`, `usage`, README.** Lead with author/import/kit; present
record as "seed a contract from live traffic *when you have access*" — one source
among several, explicitly the credentialed one.
**D2. `init` + empty states point at authoring first.** `cmd-init` offers a
`new`/`import`/`kit` next step; `cmd-ls`/`cmd-init` empty messages stop defaulting
to `record`.
**D3. Recording → draft → promote.** Make `freeze` write a *draft* the agent
reviews and `promote`s into a contract (idea.md #2, never built) — cements
"recording is an input, the contract is the artifact."
**D4. Decouple service templates from recording-only fields.** Let a template
optionally reference a kit / carry shape hints, so `init github` can seed
authoring, not just proxy-scrub.

### Sequencing

- **Phase 1 (cheap, repositioning + branching):** D1, D2, A1, A4. No injection.
  Mocks can branch on request content and the tool stops steering toward recording.
- **Phase 2 (dynamic):** A2, B2, C2. Templated/echoed responses + seed data + pinned
  ports.
- **Phase 3 (capstone):** A3 + B1. Stateful injection layer and the `oauth2` kit —
  snooker-class mocks become a one-liner.
- **Phase 4 (ergonomics):** C1, C3, C4, D3. Stacks, watch, emitters, draft/promote.

### The one decision worth confirming before Phase 3

Stateful behavior fundamentally requires either **Mountebank `inject` +
`--allowInjection`** (the footgun idea.md avoided) or a **different/custom
runtime**. Recommendation: gated injection with mocksys-**generated** JS from
declarative effects — it preserves declarative, agent-safe authoring while
unlocking the capability, and keeps Mountebank as the runtime. The alternative
(replace/augment the runtime) is a much larger bet. Worth an explicit yes before
building A3.

---

## Status — what shipped

All four phases implemented and verified end-to-end against a live Mountebank.

**Phase 1 — branching + reframe.** Contract compiler now emits request-conditional
predicates (`query`/`headers`/`body`, ops `present`/`equals`/`matches`); several
operations can share a path and branch, first match wins (`contract.cljs`
`field-predicates`/`body-predicates`/`predicates-for`). `add` gained `--header`,
`--when-query`, `--when-header`; unique ids on path collisions. `doctor` only flags
overfit on *recorded* scenarios (authored predicates are intentional). `prime`,
`usage`, `init`/`ls` empty states, and the README all lead with authoring;
recording is reframed as an optional seeding step.

**Phase 2 — dynamic responses + pinned ports.** `{{request.query|headers|body.X}}`
/ `{{request.path|method}}` templates in response bodies/headers compile to
Mountebank `copy` behaviors (`inject.cljs` is for state; templating is in
`contract.cljs` `templatize`/`walk-templates`). `apply-fault` now appends to
`behaviors` so templating + fault coexist. A scenario can pin a stable port
(persisted on the contract; honored by `run`/`env`).

**Phase 3 — stateful injection + oauth2 kit (the capstone).** New `inject.cljs`
compiles a declarative `effect` (`bind`/`consume`/`lookup`/`resolve`/`mint`/`store`/
`respond`, read-then-write order) into a generated, dependency-free Mountebank
response injection over a shared `state` object — the agent never writes JS.
`mb.cljs` manages the `--allowInjection` daemon lifecycle (starts/relaunches as
needed; `MOCKSYS_NO_INJECTION=1` opts out). New `kits.cljs` + `mocksys kit oauth2`
generates a full provider; verified: picker, 302+code, single-use code → 400,
token exchange w/ scope echo, bearer (`Bearer`/`token`) → bound user, per-user
identity, `--users` override.

**Phase 4 — dev-loop ergonomics.** `stack`/`up`/`down` bring a named set of
scenarios up together with combined env. `run --watch` recompiles+reloads on
contract edits. `env --json` and `env --tilt` emit machine env / a Tiltfile
`local_resource`. `freeze --draft` + `promote` make recording a reviewable input.

New modules: `src/mocksys/inject.cljs`, `src/mocksys/kits.cljs`. Touched:
`contract.cljs`, `core.cljs`, `mb.cljs`, `store.cljs`, `analyze.cljs`, `README.md`.

### Not done / deliberately deferred
- `env --compose`: skipped — there's no published mocksys container image, so a
  compose service would be half-working. `--tilt` (what snooker uses) covers the
  dev-stack need.
- Body predicates use JSONPath, so they fit JSON request bodies; form-encoded
  bodies are matched in stateful ops via the inject layer instead.
- No automated test suite was added (verification was manual against live mb);
  worth adding `*_test` coverage for `inject`/`contract` compilation next.

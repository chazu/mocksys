# Plan: mocksys as a Digital-Twin-Universe substrate (Okta-class target)

Builds on `ASSESSMENT.md`. That work made mocksys a behavioral-clone tool for *one
small stateful service* (the `oauth2` kit ≈ snooker's mockoauth). This plan raises it
to a **digital-twin substrate** — the bar set by StrongDM's DTU: behavioral clones of
large third-party services (Okta, Jira, Slack) you can drive at high volume,
deterministically, validated against the real thing until the observable diff is zero.

The **proximate target is a digital twin of Okta**. Okta is not mockoauth: it's a
*stateful resource system* — users/groups CRUD, SCIM `filter=` queries, cursor
pagination, an error envelope (`errorCode`/`errorSummary`/`errorCauses`), plus the
OIDC token flow mocksys already does. Four lifts get us there.

## Architecture decision (settled)

The `inject.cljs` model — declarative `effect` → mocksys-**generated**, dependency-free
JS over a per-imposter `state` object — is kept. The one risk flagged in the assessment
("a SCIM filter engine as a stringified template is a maintenance trap") is dissolved by
a rule: **the heavy logic lives in a hand-written, version-controlled `prelude` library
inside `inject.cljs`; generated per-op code stays thin glue that calls into it.** No new
runtime, Mountebank stays. This keeps regressions and friction low while making
collections first-class.

## Guardrails (checked after every phase)

1. **No regression** — the Phase-3 oauth2 handshake (picker→302+code→token→bearer→bound
   user→single-use-400) keeps passing, and existing commands behave unchanged. A
   scripted smoke test (`scripts/smoke-dtu.sh`) is the oracle; it runs against a live
   `mb` in an isolated `MOCKSYS_HOME`.
2. **Clear code** — generated JS stays thin; the prelude is readable and commented; new
   effect verbs follow the existing read-then-write ordering and naming.
3. **Low-friction UX for humans *and* agents** — every new capability is reachable in
   one obvious command, shows up in `prime`/`usage`/`--help`, and degrades with a
   helpful error, never a stack trace.

---

## Phase 1 — Collection primitives in the inject layer  *(the core lift)*

State today is flat `bucket[key] -> string` with one `store`/`consume`/`lookup` per op.
Add **collections**: named, seedable sets of *objects* with CRUD + filtered, paginated
list. All heavy logic in a hand-written prelude `Coll` helper.

New `effect` verbs (compile to thin calls into `Coll`):

- `create:  {collection, idFrom|idMint, body|bodyVar, as, status?}`  insert; returns the row
- `get:     {collection, key, as, miss}`                              read one by id
- `update:  {collection, key, patchVar|patch, as, miss}`             merge-patch a row
- `remove:  {collection, key, as, miss, status?}`                    delete a row
- `list:    {collection, filter?, after?, limit?, link?}`            filtered+paged; terminal

`state.collections` block on the contract seeds initial rows (idempotent). Filter grammar
(in the prelude, bounded): `field op "value"` clauses `and`/`or`-joined; ops `eq sw co pr`;
dotted paths (`profile.email`). Pagination: `limit` + `after` cursor → items + `next`;
`list.link` emits a `Link: <…after={next}>; rel="next"` header only when a next page exists.

**Verify:** hand-author a `demo/users` CRUD contract (create→get→list→filter→paginate→
update→delete) and exercise it with curl. Re-run the oauth2 smoke — unchanged.

## Phase 2 — Genes + the `okta` kit  *(gene transfusion made real)*

Refactor `kits.cljs` from one monolithic `oauth2` fn into a **library of composable gene
builders** — small parameterized contract fragments:

- `gene/oidc-authorize`, `gene/oidc-token`, `gene/bearer-userinfo` (extracted from oauth2 —
  oauth2 becomes "compose these", behavior identical → regression check)
- `gene/crud-collection` (generic REST resource on Phase-1 primitives: create/get/list/
  update/delete with filter+paging)
- `gene/error-envelope` (shape miss/4xx bodies like a provider's — Okta's
  `{errorCode,errorSummary,errorId,errorCauses}`)

New flagship `kit okta` composes `crud-collection` (users, groups) + the OIDC genes +
`error-envelope`, seeded with a couple of Okta-shaped users/groups. This is the proof the
primitives suffice — `mocksys kit okta` stands up an Okta twin in one command.

Gene transfusion is then a real operation: a new twin is *composed from existing genes*;
the assessment's "identify exemplar → extract → synthesize → validate → propagate" maps to
pick-genes → compose-kit → `conform` → it lives in the kit registry + git store. A thin
`mocksys genes` lists the available genes so an agent can see the building blocks.

**Verify:** `kit okta` → run → CRUD a user, SCIM-filter list, page, hit a bad id and get
the Okta error envelope, run the OIDC flow on the same twin. oauth2 smoke unchanged.

## Phase 3 — `conform`: the differential-fidelity harness  *(the DTU-defining capability)*

`mocksys conform <scenario> --corpus calls.http [--against URL]` replays the same request
corpus at the **twin** and (when creds exist) the **live** service, then diffs responses
**normalized by the existing scrub/volatility machinery** (so request-ids, dates, etc.
don't create false diffs). Exit 1 while any diff remains — the CI-shaped loop that drives a
twin toward zero observable delta. Without `--against`, it replays the corpus at the twin
and reports status/shape (a self-consistency + smoke check).

**Verify:** twin-vs-itself → diff 0; twin-vs-a-deliberately-divergent mock → non-zero, exit 1.

## Phase 4 — Twin ergonomics + docs reframe

- A multi-resource kit already emits *one* contract sharing one `state`/port/env — that *is*
  the twin aggregate. Add a `kit … --port`/pin path and ensure `env`/`run`/`up` treat it as
  one unit (mostly UX/labels).
- Reframe `prime`, `usage`, `--help`, `README.md`: collections, kits-as-twins, `conform`, and
  genes are first-class. `prime` gains a layered (pyramid) shape: one-line twin summary →
  per-resource → per-op, so an agent loads only the depth it needs.

## Phase 5 — Final integration + regression sweep

End-to-end Okta walkthrough as a new agent would hit it (`kit okta` → `run` → CRUD+filter+
page+errors+OIDC → `conform` → `inspect`). Full regression sweep of pre-existing commands.
Code-clarity read-through of `inject.cljs`/`kits.cljs`/new `core.cljs` commands. Update
`ASSESSMENT.md` status / `README.md`.

**Done when:** a fresh agent can stand up and drive an Okta-class twin with low friction,
the oauth2 baseline and existing commands still pass, and the new code reads cleanly.

---

## Status — what shipped

All five phases implemented and verified end-to-end against a live Mountebank (21
scripted checks, isolated `MOCKSYS_HOME`; the oauth2 baseline never regressed).

**Phase 1 — collections.** `inject.cljs` gained a hand-written `Coll` prelude library
(SCIM-ish filter parser `eq/sw/co/pr` with `and`/`or` + dotted paths, cursor
pagination) and five thin terminal verbs `create/get/update/remove/list`. Collections
are declared in `contract.yaml` `state.collections` (idField + seed) and seeded once
into shared state. Server-minted ids can't be spoofed by the client. A malformed
contract now yields a clean one-line error instead of a stack trace (`-main` try/catch +
friendly `store/read-contract`).

**Phase 2 — genes + okta kit.** `kits.cljs` refactored into composable gene builders
(`crud-collection`, `oidc-handshake`, `okta-error` envelope) merged by `compose`.
`oauth2` recomposed from the OIDC genes (behavior identical). New `kit okta` = users +
groups CRUD + OIDC on one stateful imposter. `mocksys genes` lists the building blocks.

**Phase 3 — conform.** New `conform.cljs` + `cmd-conform`: replay a JSON corpus (or the
twin's own recorded traffic) at the twin and, with `--against URL`, the live service;
diff status + canonical body (volatile-field- and `--ignore`-masked); exit 1 on drift.
Verified golden (exit 0), diff-vs-identical (0 diffs), diff-vs-divergent (exit 1).

**Phase 4 — ergonomics + docs.** `prime`, `usage`, and `README.md` lead with twins,
collections, `conform`, and `genes`; `inspect` shows friendly `/users/{id}` paths for a
twin instead of compiled regex.

### Follow-on (shipped — see `PLAN-CLOCK.md`)
- Determinism primitives: a **virtual clock** (token/session expiry, `store.ttl`, `now`
  bind, `mocksys clock`) and a **rate-limit gene** (`429` + `X-Rate-Limit-*` over a
  clock-windowed budget), both wired into `kit okta` via `--token-ttl`/`--rate-limit` and
  exposed as genes. Plus a human-friendly `docs/walkthrough.md` and runnable `examples/`.
- OIDC userinfo resolves a separate `oidc_users` seed, not the live SCIM `users`
  collection (a twin can log in as seeded accounts but not as SCIM-created ones yet).
  A `resolve-from-collection` variant would unify them.
- Body predicates / SCIM filters are bounded (no parenthesized groups); enough for
  Okta's common `filter=field op "v"` shape.

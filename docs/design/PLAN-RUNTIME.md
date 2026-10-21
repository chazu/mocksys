# Plan: authorable genes, disk kits, a native runtime, custom verbs, gRPC

Five changes. Together they let an author build a full twin without editing mocksys, and
they move serving off Mountebank so mocksys can speak protocols Mountebank cannot.

| Phase | Change | Depends on | Size (guess) |
|---|---|---|---|
| 1 | Genes callable from `contract.yaml` | none | 1 day |
| 2 | Kits loaded from YAML files on disk | 1 | half a day |
| 3 | Native runtime replaces Mountebank | none | 1 to 2 weeks |
| 4 | Custom verbs from JS modules | 3 | 2 days |
| 5 | gRPC (unary) | 3 | 1 week |

Phases 1+2 and phase 3 run in parallel. Phases 4 and 5 run in parallel after 3 lands.

**Status (2026-10-21).** All five phases have landed, each after an independent review
and a round of fixes. Still open, and planned in PLAN-RUNTIME-2.md:
- **The interpreter rewrite.** Effects are still JavaScript generated as strings.
- **gRPC beyond unary calls.** No streaming, reflection, recording or TLS.
- **Kits that ship modules.** A disk kit cannot carry `modules` yet.
- **No admin-API auth for local users,** the same as Mountebank.
- **The recorded-request log has no size cap.**

## Why

Today an author outside the repo can only hand-write effects. Genes are ClojureScript
functions reachable only through the closed `kits` map. Verbs are a fixed set in
`inject.cljs`. Any logic the effect DSL can't express (SigV4, DynamoDB expressions, S3
XML) needs a code change.

Mountebank speaks HTTP/1.1 only. That rules out gRPC, so most GCP emulators, and
websockets and SSE. It also runs as a second process whose launch mocksys has to manage
(`MOCKSYS_MB`, `--allowInjection` restarts, the Homebrew formula installing it).

## Phase 1. Genes in YAML

A contract may list genes. Each entry is a one-key map from gene name to its params.
Param keys are the gene's option names, kebab-case.

```yaml
genes:
  - crud-collection: { coll: users, base: /api/v1/users, seed-name: users, id-prefix: 00u }
  - membership: { coll: memberships, users-coll: users, groups-coll: groups,
                  users-base: /api/v1/users, groups-base: /api/v1/groups }
operations: [ ... ]   # hand-written ops; served before gene ops, so they can override
```

- `contract/expand` is a pure function from an authored contract to an effective one.
  Gene operations append after the hand-written `operations`, because first match wins and
  authored ops should be able to shadow gene ops. Gene `collections` and `seed` merge via
  the existing `compose`.
- Every reader of operations (`compile`, `examples`, `validate`, `conform`, the catalog)
  goes through `expand`. `contract.yaml` keeps the `genes:` form; `imposter.json` holds
  the expansion.
- Editing commands (`use`, `add`, `fault`, `parameterize`) act on authored operations. An
  attempt to edit a gene operation fails with a message naming the gene.
- `kits/genes` becomes a registry `name -> {:doc :params :build}`. `mocksys genes` lists
  each gene's params. Capabilities that are not standalone genes (rate limit, virtual
  clock, error envelope, JWT signing) are listed as params or built-ins, truthfully.
- `validate` reports an unknown gene or an unknown param as an error.

## Phase 2. Kits from disk

A kit is a YAML file: a contract template plus declared flags.

```yaml
name: github-oauth
doc: GitHub-shaped OAuth2 provider
flags: { token-ttl: 3600, provider: github }   # flag -> default
contract:
  genes:
    - oidc-handshake: { token-ttl: "{token-ttl}", ... }
```

- A string value that is exactly `"{flag}"` is replaced by the flag's value, keeping its
  type. No partial-string interpolation.
- Kits load from the built-ins shipped with mocksys and from `$MOCKSYS_HOME/kits/*.yaml`.
  A user kit with a built-in's name shadows it, with a warning.
- Built-in YAML kits are inlined at build time (`shadow.resource/inline`) because the bun
  binary cannot read repo files at runtime.
- Port the `oauth2` kit to YAML as the proof of the format. Port `okta` only if it needs no
  new code paths. It generates an RSA key at `kit` time, so it likely stays in code; record
  the reason in the kit's docstring if so.
- `unsupported-flags` reads the declared flags, so `kit X --bogus` keeps failing loudly.

## Phase 3. Native runtime

mocksys runs its own daemon in place of Mountebank. The daemon implements the subset of
Mountebank's imposter format that mocksys emits, so `contract/lower` and
`inject.cljs` do not change.

- `mocksys daemon` (hidden) runs the server. `mb/ensure-up!` spawns it detached: under node
  as `node <script> daemon`, under the bun binary as `<execPath> daemon`.
- The admin API binds 127.0.0.1 only. Default port 2626 (`MOCKSYS_ADMIN_PORT` overrides),
  so a real Mountebank on 2525 is never mistaken for it. `GET /` identifies the daemon.
- The admin routes are the ones `mb.cljs` calls today: create, get, delete, and list
  imposters, delete saved requests, and `?replayable=true&removeProxies=true`.
- The `mocksys.mb` public API keeps its function names, so its callers do not change.
  Rename the namespace in a later cleanup if wanted.
- Imposter semantics come from an inventory of what mocksys emits: predicate operators,
  `is` responses, behaviors, faults, `inject` and its function signature, `proxy` with
  `proxyOnce` and `predicateGenerators`, `recordRequests`. Implement that inventory and
  nothing else.
- Injection runs in-process with `require` reachable, as the test harness already does.
  `--allowInjection` and `restart-injecting!` go away. `MOCKSYS_NO_INJECTION` still makes
  `run` refuse stateful scenarios.
- **Proof before deletion.** With Mountebank still installed, serve the Okta twin on both
  runtimes and run `conform` from one to the other over `examples/okta-corpus.json`, aiming
  for 0 diffs. `scripts/smoke.sh` and the walkthrough script must pass on the native
  runtime.
- Then delete the `mountebank` dependency, `MOCKSYS_MB`, the launcher code, the Homebrew
  `npm install mountebank` step, and the docs that describe them.

**Deferred.** Replacing the JS-string codegen in `inject.cljs` with an interpreter over
effect maps. The codegen is covered by the unit suite and serves the same responses. With
injection in-process, custom verbs (phase 4) do not need the interpreter. Revisit if
codegen bugs keep recurring.

## Phase 4. Custom verbs

```yaml
modules: [./verbs.js]        # relative to the scenario directory
operations:
  - id: put-item
    effect:
      bind: { sig: { header: authorization } }
      call: { verb: checkSig, args: { sig: "{sig}" }, as: principal }
      create: { collection: items }
```

- A module exports `{ verbs: { name(ctx) { ... } } }` where
  `ctx = { request, state, vars, args, now, respond }`.
- A verb returns a value, bound to `as`, or `ctx.respond({ status, headers, json | text | body })`,
  which ends the request with that response. A plain object with a `respond` key is a value.
- `call` runs after `bind` and lookups and before minting or the terminal verb, matching
  the existing read-then-write order in `effect-body-js`.
- Lowering records module paths relative to the scenario directory, and the verbs the
  operations call, on the imposter. `run` makes the paths absolute before the post. The
  daemon loads the modules on each post, bypassing the require cache, checks the called
  verbs, and exposes them to the injection.
- The CLI never runs module code. `validate` checks that each module is a file inside the
  scenario directory and that each `call` is well formed. A missing verb fails `run`.

## Phase 5. gRPC (unary)

```yaml
protocol: grpc
proto: ./kv.proto            # relative to the scenario directory
operations:
  - id: get
    request: { rpc: kv.Store/Get }    # lowers to POST /kv.Store/Get
    effect: { ... }
```

- The daemon serves `protocol: grpc` imposters with `@grpc/grpc-js` and
  `@grpc/proto-loader`, registering every unary method in the proto.
- A call becomes the request shape the stubs already match on: method `POST`, path
  `/pkg.Service/Method`, metadata as headers, the decoded message as the JSON body. All of
  matching, injection, and state is reused.
- A 2xx response's JSON body encodes as the reply message. Other statuses map to a gRPC
  status. A body of `{ grpcCode, message }` picks the code directly, otherwise a fixed
  HTTP-to-gRPC table applies.
- Out of scope: streaming RPCs, server reflection, recording gRPC traffic, TLS.
- The bun binary must still build and serve gRPC. If it cannot, document the limit.

## Verification for every phase

`npm test`, `npm run build` with 0 warnings, and `scripts/smoke.sh`. Each phase adds tests
for its behavior and an example under `examples/`. Each phase gets an independent review
before it merges.

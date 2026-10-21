# Architecture

## What mocksys is

mocksys is a CLI with its own runtime: a daemon (`mocksys daemon`) that serves HTTP
mocks and takes commands over an admin API on `127.0.0.1:2626`. The CLI starts the
daemon on first use and keeps it behind the commands. You work in agent vocabulary
(*service, scenario, contract, example, effect, kit, twin*), never in the daemon's
*imposters* and *stubs*.

The imposter format is the subset of [Mountebank](https://www.mbtest.org/)'s that
mocksys compiles to. mocksys ran on Mountebank before it had its own daemon, and the
daemon still accepts that subset, so compiled imposters did not change.

The guiding split:

> **The contract is the source of truth. The imposter is a build artifact.**

You author (or generate, or record) a `contract.yaml`. mocksys *compiles* it to an
`imposter.json` and posts that to the daemon. Every command that starts a mock
recompiles from the contract first; an existing `imposter.json` is never posted as-is.

## Module map

mocksys is ClojureScript compiled with [shadow-cljs](https://github.com/thheller/shadow-cljs)
to a Node script (`out/mocksys.js`). Each module is focused:

| Module | Responsibility |
|---|---|
| `core` | The entry point and the command table (name → handler). |
| `cli.*` | One namespace per command group, holding all user-facing output: `cli.record` (init/record/freeze/promote), `cli.serve` (run/env/inspect/doctor/requests/assert/ls/stop/stacks), `cli.twins` (kit/genes/conform/clock), `cli.author` (import/new/add/validate/examples/use/fault/parameterize/compile), `cli.share` (store + pack/unpack), `cli.help` (prime/usage), `cli.util` (arg parsing). |
| `lifecycle` | A scenario's lifecycle: `compile!` (contract → imposter.json), `ensure-contract!` (lift a legacy imposter), `ensure-running!` (recompile, then post to the daemon), watch mode, port pins. |
| `contract` | The canonical contract format. `lower` compiles a contract → imposter; `lift` reverse-engineers a contract from a recorded imposter; plus validation, example selection, response templating, path/predicate compilation. |
| `inject` | The **stateful** layer. Compiles a declarative `effect` into a generated, dependency-free response injection over a shared `state` object — handshake buckets and resource collections. The agent never writes JavaScript. |
| `kits` | **Kits** and composable **genes**. A gene is a parameterized contract fragment (an OIDC handshake, a CRUD resource, an error envelope); a kit composes genes into a full twin. Ships `oauth2` and `okta`. |
| `conform` | Differential fidelity: replay a request corpus at the twin and (optionally) the live service, then diff status and canonical bodies (`--ignore` keys masked; volatile headers dropped when headers are compared). |
| `mb` | The admin-API client and daemon lifecycle (spawn `mocksys daemon` when none answers, free-port allocation, imposter CRUD, replayable pull). The name predates the native runtime. |
| `runtime` | The daemon: the admin API, one HTTP server per imposter, the proxy client for recording, and wire faults. `runtime.imposter` validates a posted imposter and resolves each request (stub choice, responses, behaviors, injection, proxyOnce recording); `runtime.match` evaluates predicates. |
| `store` | On-disk layout under `~/.mocksys`: contracts, imposters, drafts, recordings, services, stacks. YAML read/write. |
| `service` | Service templates (github, gitlab, aws-s3, stripe, generic-http): redact/volatile headers, default target, the consumer's base-URL env var. Composed with the base sets in `analyze`. |
| `scrub` | Redaction (secrets → a marker) and volatility stripping, in memory, on `freeze`. |
| `analyze` | Pure analysis over an imposter — the base secret/volatile header sets, matcher-hygiene/overfit detection — feeding `inspect` and `doctor`. |
| `openapi` | OpenAPI/Swagger import: one scenario per operation, schemas + named examples, a synthesized service profile. |
| `bundle` | `pack`/`unpack` — a scenario as a single self-describing `.mock.tgz` (git-less sharing). |
| `git` | The git-backed shared store (`publish`/`status`/`log`/`remote`/`push`/`pull`/`restore`/`rm`). |

## The compile pipeline

```
contract.yaml ──lower──▶ imposter.json ──POST /imposters──▶ mocksys daemon ──▶ :PORT
   (source)              (build artifact)                     (the runtime)        (your app hits this)
```

`contract/lower` walks each operation and emits a **stub**:

- **Static operation** → predicates (from `request`) + a `responses` array. Each selected
  example becomes an `{is {...}}` response (the daemon cycles through several). Response
  templating (`{{request.*}}`) lifts into `copy` behaviors; a transport
  `fault` adds a `wait` behavior or swaps in a `fault` response.
- **Stateful operation** (`effect` present) → a single `{inject "<generated JS>"}`
  response. `inject/gen` embeds the seed and collection declarations as literals and
  emits a self-contained function over `(request, state, logger)`.

Predicate compilation: `method`+`path` by default; a templated path (`/a/{id}`) compiles
to a `matches` regex (`^/a/[^/]+$`), an exact path to `deepEquals`. Optional
`query`/`headers`/`body` conditions add `exists`/`equals`/`matches` predicates so several
operations can share a path and branch on request content (the daemon ANDs predicates;
stub order = contract order; first match wins).

## The on-disk store

One shared, git-backed library — the same from every project. Location: `$MOCKSYS_HOME`,
else `~/.mocksys`. (Set `MOCKSYS_HOME=./.mocks` to scope mocks to a single project.)

```
~/.mocksys/
  services/<service>.yaml          # service profile (redact/volatile/target/env)
  stacks/<name>.yaml               # a named set of scenarios
  <service>/<name>/
    contract.yaml                  # the source of truth
    contract.draft.yaml            # a recording awaiting `promote` (optional)
    imposter.json                  # compiled build artifact
    mock.yaml                      # display metadata (service, port, origin, stub count)
    recording.yaml                 # record→freeze handoff state (transient)
```

A scenario id may nest (`github/create-issue`) — Node's path join turns it into a real
subdirectory. The catalog (`ls`) walks for `imposter.json`. Running a scenario always
recompiles `imposter.json` from `contract.yaml`. A legacy imposter-only scenario first gets
a contract lifted from its static responses; any `inject` in it is dropped.

## The runtime

`run` ensures the daemon is up and posts the imposter with `recordRequests=true`
(so `requests`/`assert`/`conform` have data). Port resolution order: explicit `--port` >
the already-running port > a port **pinned** on the contract > a fresh free port. An
explicit `--port` is persisted onto the contract as a pin. `run --watch` recompiles and
reloads on every `contract.yaml` change (the Tilt-style save-and-reload loop).

**The daemon.** `mb/ensure-up!` checks `GET /` on the admin port, which answers with
the daemon's name, mocksys version and pid. If nothing answers, it spawns a detached
`mocksys daemon` and waits for it: under node or bun running a script, `<runtime>
<script> daemon`; in the bun binary, whose script path is a virtual `/$bunfs/` one,
`<execPath> daemon`. The daemon's stdout and stderr go to `$MOCKSYS_HOME/daemon.log`,
truncated on each start. If the process cannot start, exits, or does not answer within
10s, the error ends with the last lines of that log. If a daemon of another mocksys
version answers, `ensure-up!` stops it with `DELETE /`, starts a fresh one, and prints
one stderr line naming the mocks that stopped. `mocksys daemon stop` sends the same
`DELETE /`. If something other than mocksys answers, it stops with an error naming
`MOCKSYS_ADMIN_PORT`, which overrides the default 2626. The daemon keeps imposters in
memory, so they last until it exits.

The daemon implements exactly what mocksys emits, and rejects anything else with a
400 that names the field:

| Part | Supported |
|---|---|
| Imposter | `protocol: http`, `port`, `name`, `recordRequests`, `stubs` |
| Predicates | `equals`, `deepEquals`, `matches`, `exists` over method, path, query, headers; `jsonpath` (plain paths) over the body. Case-insensitive, as in Mountebank. |
| Responses | `is` (with Mountebank's defaults: status 200, an object body as JSON, `Connection: close`), `inject` (source that evaluates to a function), `proxy` (`proxyOnce` with `predicateGenerators`; `to` is an origin with no path, query or fragment), `fault` (`CONNECTION_RESET_BY_PEER`, `RANDOM_DATA_THEN_CLOSE`) |
| Behaviors | `wait`, `copy` (regex or jsonpath) |
| Admin routes | `GET /` (name, version, pid), `DELETE /` (stop), `GET/POST /imposters`, `GET/DELETE /imposters/:port` (with `?replayable=true&removeProxies=true`), `DELETE /imposters/:port/savedRequests` |

**Injection.** Injections run in the daemon's process, called as
`f(request, state, logger)` with `require` in scope. `state` is one object per
imposter, shared by its stubs. Set `MOCKSYS_NO_INJECTION=1` to make `run` refuse
stateful scenarios.

**Trust boundary.** An injection runs with full Node access inside the daemon, so mocksys
only ever posts injections it generated itself:

- The admin API listens on `127.0.0.1` and each mock on `127.0.0.1` and `::1`, so no
  other machine can reach them.
- The admin API refuses a request that carries an `Origin` header or names a host
  other than `127.0.0.1` or `localhost`. A web page therefore cannot post an imposter,
  directly or through DNS rebinding.
- `imposter.json` is a build output, never an input. A pulled, unpacked or hand-edited
  one is overwritten from the contract before anything is posted.
- Every contract value spliced into generated JS goes through `JSON.stringify` (strings,
  objects) or must be a finite number (statuses, windows, TTLs, clock offsets);
  `validate` and `compile` reject anything else.
- JSONPath selectors from a contract (`request.body` predicate keys and
  `{{request.body.…}}` templates) must be plain paths (`$.a.b`, `$.items[0]`,
  `$['k']`, `[*]`). `compile` rejects filter and script expressions, and the daemon
  evaluates plain paths only.

**State lifetime.** The daemon keeps the `state` object for the imposter's lifetime.
Reposting an imposter (every `run`, and every `conform`) creates a fresh instance, so
collections are reseeded — each `conform` run starts from a clean, deterministic baseline.

## Stateless vs stateful, at a glance

|  | Static operation | Stateful operation (`effect`) |
|---|---|---|
| Source | `responses` + selected `examples` | `effect` verbs + `state` |
| Compiles to | `{is {...}}` (+ `copy`/`wait` behaviors), or a `fault` | `{inject "<generated JS>"}` |
| Dynamic from request | response templating `{{request.*}}` | `bind` + `{var}` interpolation |
| Cross-request memory | none | shared `state` (buckets + collections) |
| Use for | fixed fixtures, error variants, faults | auth handshakes, CRUD resources, whole twins |

See [contracts.md](contracts.md) for static operations and [effects.md](effects.md) for
stateful ones.

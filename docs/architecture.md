# Architecture

## What mocksys is

mocksys is a CLI with its own runtime: a daemon (`mocksys daemon`) that serves HTTP and gRPC
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
| `cli.*` | One namespace per command group, holding all user-facing output: `cli.record` (init/record/freeze/promote), `cli.serve` (run/env/inspect/doctor/requests/assert/ls/stop/stacks), `cli.twins` (kit/kits/genes/conform/clock), `cli.author` (import/new/add/validate/examples/use/fault/parameterize/compile), `cli.share` (store + pack/unpack), `cli.help` (prime/usage), `cli.util` (arg parsing). |
| `lifecycle` | A scenario's lifecycle: `compile!` (contract → imposter.json), `ensure-contract!` (lift a legacy imposter), `ensure-running!` (recompile, then post to the daemon), watch mode, port pins. |
| `contract` | The canonical contract format. `lower` compiles a contract → imposter; `lift` reverse-engineers a contract from a recorded imposter; plus validation, example selection, response templating, path/predicate compilation. |
| `effect` | The **stateful** layer. The effect language as one table of verbs, each with its field checks and how it runs; `errors` validates an effect (the CLI on `lower`/`validate`, the daemon on post) and `run` evaluates one over the imposter's shared `state` object: handshake buckets, resource collections, the virtual clock. `scim` parses the `list` verb's filters. The agent never writes JavaScript. |
| `modules` | **Custom verbs.** Resolves a contract's `modules:` against the scenario directory (refusing paths outside it) for `contract/lower`, which never runs them. The daemon loads each CommonJS file afresh with `vm.compileFunction` on every post, never from the require cache, and checks the verbs the imposter calls. |
| `kits` | **Kits** and composable **genes**. A gene is a parameterized contract fragment (an OIDC handshake, a CRUD resource, an error envelope); a kit composes genes into a full twin. Ships `oauth2` and `okta`. A contract can list genes under `genes:`, and a kit can be a YAML template, built in or in `$MOCKSYS_HOME/kits`. |
| `conform` | Differential fidelity: replay a request corpus at the twin and (optionally) the live service, then diff status and canonical bodies (`--ignore` keys masked; volatile headers dropped when headers are compared). |
| `mb` | The admin-API client and daemon lifecycle (spawn `mocksys daemon` when none answers, free-port allocation, imposter CRUD, replayable pull). The name predates the native runtime. |
| `runtime` | The daemon: the admin API, one HTTP server per imposter, the proxy client for recording, and wire faults. `runtime.imposter` validates a posted imposter and resolves each request (stub choice, responses, behaviors, effects, proxyOnce recording); `runtime.match` evaluates predicates. `runtime.grpc` serves a `protocol: grpc` imposter with grpc-js, turning each call into a request for `runtime.imposter` and its outcome into a reply or a gRPC status. |
| `proto` | A grpc contract's `.proto`, read on the CLI side with protobufjs: imports, the descriptor `compile!` embeds in the imposter, and the rpc checks `validate` runs. |
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
- **Stateful operation** (`effect` present) → a single `{effect {...}}` response that
  carries the operation's effect map as written. What every effect runs over goes once
  into the imposter's `state` settings: the seeds, the collections with their seed rows,
  the clock's start, the id stream's seed and the fallback issuer. A contract that uses
  the clock also gets a `{clock true}` stub at `/_mocksys/clock`.
- **Modules.** When the contract lists `modules:`, `lower` checks that each is a file
  inside the scenario directory and records it, relative to that directory, as the
  imposter's `modules`. It never runs a module. `ensure-running!` makes the paths
  absolute just before the post.

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
    verbs.js                       # custom verb modules the contract lists (optional; any name)
    recording.yaml                 # record→freeze handoff state (transient)
```

A scenario id may nest (`github/create-issue`) — Node's path join turns it into a real
subdirectory. The catalog (`ls`) walks for `imposter.json`. Running a scenario always
recompiles `imposter.json` from `contract.yaml`. A legacy imposter-only scenario first gets
a contract lifted from its `is` responses; nothing else in it carries over.

## The runtime

`run` ensures the daemon is up and posts the imposter with `recordRequests=true`
(so `requests`/`assert`/`conform` have data). Port resolution order: explicit `--port` >
the already-running port > a port **pinned** on the contract > a fresh free port. An
explicit `--port` is persisted onto the contract as a pin. `run --watch` recompiles and
reloads on every change to `contract.yaml` and, for a grpc contract, to its proto and the
imports inside the scenario directory (the Tilt-style save-and-reload loop).

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
| Imposter | `protocol` (`http` or `grpc`), `port`, `name`, `recordRequests`, `stubs`, `modules` (absolute paths), `state` (the settings effects run over); for `grpc`, a `descriptor` (the proto as protobufjs JSON) |
| Predicates | `equals`, `deepEquals`, `matches`, `exists` over method, path, query, headers; `jsonpath` (plain paths) over the body. Case-insensitive, as in Mountebank. |
| Responses | `is` (with Mountebank's defaults: status 200, an object body as JSON, `Connection: close`), `effect` (an operation's effect map, checked on post like `validate` checks it), `clock` (`true`: the virtual clock's control), `proxy` (`proxyOnce` with `predicateGenerators`; `to` is an origin with no path, query or fragment), `fault` (`CONNECTION_RESET_BY_PEER`, `RANDOM_DATA_THEN_CLOSE`) |
| Behaviors | `wait`, `copy` (regex or jsonpath) |
| Admin routes | `GET /` (name, version, pid), `DELETE /` (stop), `GET/POST /imposters`, `GET/DELETE /imposters/:port` (with `?replayable=true&removeProxies=true`), `DELETE /imposters/:port/savedRequests` |

**gRPC.** A `grpc` imposter gets a grpc-js server in place of the HTTP one. It
registers every unary method of the descriptor. A call becomes an ordinary request
(`POST /pkg.Service/Method`, metadata as headers, the message as a JSON body), so the
stubs, predicates, effects and state are the same code as for HTTP. The outcome maps
back to a reply message or a gRPC status; [contracts.md](contracts.md#grpc-unary) gives
the rules. `compile!` embeds the descriptor, so the daemon reads no scenario files and
`imposter.json` stays self-contained. Streaming and undeclared methods answer
`UNIMPLEMENTED`, and a grpc imposter rejects `proxy` responses. The bun binary serves
gRPC too.

**Effects.** The daemon evaluates an `effect` response with `effect/run` over the
imposter's `state`, one object per imposter shared by its stubs. On post, it checks
every effect with the same `effect/errors` the CLI runs, so a bad effect is a 400 that
names the stub and the field (`stubs[3].responses[0].effect: respond.status must be an
HTTP status from 100 to 599`). An imposter response has no form that carries code: a planted
`inject` is refused as an unsupported field. Set `MOCKSYS_NO_INJECTION=1` to make `run`
refuse, before compiling anything, a scenario that lists modules, the only code a
scenario can bring.

**Custom verbs.** The daemon is the only process that runs module code. On each post, it
loads the imposter's `modules` from disk with `modules/load-verbs` and checks that every
verb its effects `call` is exported. A module that fails to load, a bad export, two
modules exporting one verb, or a missing verb makes the post a 400 that names the module
or verb. A verb runs synchronously inside the effect, so the request's effect stays one
uninterrupted step over `state`. A verb that throws, returns a Promise, or returns an
invalid `ctx.respond` spec makes that request a 500 naming the verb. The daemon process handles
`unhandledRejection` and `uncaughtException` by logging the error and its stack to
`daemon.log`, so a module's stray promise or timer cannot stop the other mocks.
`GET /imposters/:port` returns `modules` and `state`, so a replayable imposter posts back
as it was.

**Trust boundary.** A module runs with full Node access inside the daemon. Effects are
data the daemon evaluates, so the modules a contract lists are the only code a scenario
brings:

- The admin API listens on `127.0.0.1` and each mock on `127.0.0.1` and `::1`, so no
  other machine can reach them.
- The admin API refuses a request that carries an `Origin` header or names a host
  other than `127.0.0.1` or `localhost`. A web page therefore cannot post an imposter,
  directly or through DNS rebinding.
- `imposter.json` is a build output, never an input. A pulled, unpacked or hand-edited
  one is overwritten from the contract before anything is posted.
- Every effect field has a type (statuses, windows, TTLs and clock offsets are finite
  numbers). `validate` and `compile` reject anything else, and the daemon checks again
  on post.
- JSONPath selectors from a contract (`request.body` predicate keys and
  `{{request.body.…}}` templates) must be plain paths (`$.a.b`, `$.items[0]`,
  `$['k']`, `[*]`). `compile` rejects filter and script expressions, and the daemon
  evaluates plain paths only.
- A module is code the author wrote, and it runs with the same access. It must live
  inside the scenario directory, so `pack`, `publish` and `push` share it with the
  contract that calls it rather than leaving it behind. The CLI never runs a module:
  only the daemon does, when `run` posts the scenario. `unpack` names each unpacked
  scenario that lists modules, so the reader knows the scenario runs code from the
  bundle.

**State lifetime.** The daemon keeps the `state` object for the imposter's lifetime.
Reposting an imposter (every `run`, and every `conform`) creates a fresh instance, so
collections are reseeded — each `conform` run starts from a clean, deterministic baseline.

## Stateless vs stateful, at a glance

|  | Static operation | Stateful operation (`effect`) |
|---|---|---|
| Source | `responses` + selected `examples` | `effect` verbs + `state` |
| Compiles to | `{is {...}}` (+ `copy`/`wait` behaviors), or a `fault` | `{effect {...}}`, run by the daemon |
| Dynamic from request | response templating `{{request.*}}` | `bind` + `{var}` interpolation |
| Cross-request memory | none | shared `state` (buckets + collections) |
| Use for | fixed fixtures, error variants, faults | auth handshakes, CRUD resources, whole twins |

See [contracts.md](contracts.md) for static operations and [effects.md](effects.md) for
stateful ones.

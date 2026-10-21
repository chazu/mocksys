# Architecture

## What mocksys is

mocksys is a CLI with its own runtime: a daemon (`mocksys daemon`) that serves HTTP and gRPC
mocks and takes commands over an admin API on `127.0.0.1` (port 2626 for the default
store, see [the daemon](#the-runtime)). The CLI starts the
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
| `effect` | The **stateful** layer. The effect language as one table of verbs, each with its field checks and how it runs; `errors` validates an effect and `collection-errors` checks it names only declared collections (the CLI on `lower`/`validate`, the daemon on post), and `run` evaluates one over the imposter's shared `state` object: handshake buckets, resource collections, the virtual clock. The `sse` and `websocket` terminals answer with a stream for the server to serve, and `publish` hands events to the subscribers in the imposter's channel map. That object, its buckets and collection maps, the channel map and a request's variables have no prototype, so a name from data is only a key. `scim` parses the SCIM filters of `list`, `find` and `render`, and walks the dotted paths that templates, `find` and `listJoin` read. The agent never writes JavaScript. |
| `modules` | **Custom verbs.** Resolves a contract's `modules:` against the scenario directory (refusing paths outside it) for `contract/lower`, which never runs them. The daemon loads each CommonJS file afresh with `vm.compileFunction` on every post, never from the require cache, and checks the verbs the imposter calls. |
| `kits` | **Kits** and composable **genes**. A gene is a parameterized contract fragment (an OIDC handshake, a CRUD resource, an error envelope); a kit composes genes into a full twin. Ships `oauth2` and `okta`. A contract can list genes under `genes:`, and a kit can be a YAML template, built in or in `$MOCKSYS_HOME/kits`. A user kit that ships modules is a directory; `kit` copies them into the scenario. |
| `conform` | Differential fidelity: replay a request corpus at the twin and (optionally) the live service, then diff status and canonical bodies (`--ignore` keys masked; volatile headers dropped when headers are compared). |
| `mb` | The admin-API client and daemon lifecycle (spawn `mocksys daemon` when none answers, free-port allocation, imposter CRUD, replayable pull). The name predates the native runtime. |
| `runtime` | The daemon: the admin API, one HTTP server per imposter, the proxy client for recording, and wire faults. `runtime.imposter` validates a posted imposter and resolves each request (stub choice, responses, behaviors, effects, proxyOnce recording); `runtime.match` evaluates predicates. `runtime.stream` serves a stream answer: server-sent events over the response, or a WebSocket (with `ws`) whose inbound messages each go through `runtime.imposter` as a `MESSAGE` request. `runtime.grpc` serves a `protocol: grpc` imposter with grpc-js, turning each call into a request for `runtime.imposter` and its outcome into a reply or a gRPC status; `runtime.grpc-reflection` serves server reflection for it. |
| `tls` | The local CA in `$MOCKSYS_HOME/tls/` and the certificates it issues (node-forge over keys from node's crypto), a contract's `tls` declaration, and `fetch-own`, which the CLI uses to reach its own HTTPS mocks. |
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
else `~/.mocksys`; a relative `MOCKSYS_HOME` is resolved against the working directory,
so a command run from a subdirectory finds the same store. (Set `MOCKSYS_HOME=./.mocks`
to scope mocks to a single project. A daemon serves one store, and each store gets its
own admin port, so the project store runs its own daemon.)

```
~/.mocksys/
  services/<service>.yaml          # service profile (redact/volatile/target/env)
  stacks/<name>.yaml               # a named set of scenarios
  tls/                             # the local CA for tls mocks: ca.pem, ca.key (0600), a .gitignore of *
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
(so `requests`/`assert`/`conform` have data). Each imposter keeps its newest 10000
requests, or `MOCKSYS_MAX_RECORDED_REQUESTS` from the daemon's environment, read once
when the daemon starts. It must be a whole number above 0: a mock that records but keeps
nothing would fail every `assert`, so 0 stops the daemon at start like any other bad
value.
`numberOfRequests` counts every request, and `GET /imposters/:port` reports the
dropped ones as `droppedRequests`. An `assert` that misses says when the call may have
been dropped. Port resolution order: explicit `--port` >
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
`MOCKSYS_ADMIN_PORT`. The admin port is `MOCKSYS_ADMIN_PORT` when set. Otherwise it is
2626 for the default store, and for any other store a port in 26000–26999 picked by a
SHA-256 of the store's path with its links resolved. That range is below the ephemeral
range of Linux, macOS and Windows, where mocks get their ports, so a mock never holds
it. Two stores whose paths hash to the same port share it: the second one gets a 401,
and `MOCKSYS_ADMIN_PORT` separates them. `mocksys home` prints the port. The daemon
keeps imposters in memory, so they last until it exits.

**The admin token.** Once the daemon holds its port, it writes a random 32-byte token
to `$MOCKSYS_HOME/daemon-<port>.token`, mode 0600. One file per port lets two daemons
share a store on different ports, and a daemon that loses the port race leaves the
running daemon's token in place. Every admin call except `GET /` must carry
`Authorization: Bearer <token>`, compared in constant time; a missing or wrong token
gets a 401. `mocksys.mb` reads the token file on every call. A 401 means the daemon on
the port was started from another store or by another user, so the CLI stops with an
error that names the token file it read, the pid that daemon reports on `GET /`, and
`MOCKSYS_ADMIN_PORT`. Replacing a daemon of
another version needs the token too, so the CLI never stops a daemon it cannot
authenticate to.

The daemon implements exactly what mocksys emits, and rejects anything else with a
400 that names the field:

| Part | Supported |
|---|---|
| Imposter | `protocol` (`http` or `grpc`), `port`, `name`, `recordRequests`, `stubs`, `modules` (absolute paths), `state` (the settings effects run over), `tls` (`{cert, key}` PEM text); for `grpc`, a `descriptor` (the proto as protobufjs JSON) |
| Predicates | `equals`, `deepEquals`, `matches`, `exists` over method, path, query, headers; `jsonpath` (plain paths) over the body. Case-insensitive, as in Mountebank. |
| Responses | `is` (with Mountebank's defaults: status 200, an object body as JSON, `Connection: close`), `effect` (an operation's effect map, checked on post like `validate` checks it; its answer may be an `sse` or `websocket` stream, in an `http` imposter only), `clock` (`true`: the virtual clock's control), `proxy` (`proxyOnce` with `predicateGenerators`; `to` is an origin with no path, query or fragment), `fault` (`CONNECTION_RESET_BY_PEER`, `RANDOM_DATA_THEN_CLOSE`) |
| Behaviors | `wait`, `copy` (regex or jsonpath) |
| Admin routes | `GET /` (name, version, pid; needs no token), `DELETE /` (stop), `GET/POST /imposters`, `GET/DELETE /imposters/:port` (with `?replayable=true&removeProxies=true`), `DELETE /imposters/:port/savedRequests` |

**gRPC.** A `grpc` imposter gets a grpc-js server in place of the HTTP one. It
registers every method of the descriptor. A call becomes an ordinary request
(`POST /pkg.Service/Method`, metadata as headers, the message as a JSON body), so the
stubs, predicates, effects and state are the same code as for HTTP. The outcome maps
back to a reply message or a gRPC status; [contracts.md](contracts.md#grpc) gives
the rules. `compile!` embeds the descriptor, so the daemon reads no scenario files and
`imposter.json` stays self-contained. A client stream's request body is the array of
its messages, each message of a bidirectional stream is a request of its own, and a
stream's reply array is its messages. Undeclared methods answer `UNIMPLEMENTED`.
`runtime.grpc-reflection` adds server reflection (v1 and v1alpha) from the same
descriptor. It registers the reflection service from proto text it carries, since
`@grpc/reflection`'s own registration reads a file the bun binary cannot ship. A grpc
imposter's `proxy` response forwards each call upstream with grpc-js, which is how
`record --proto` records a gRPC service. The bun binary serves
gRPC too.

**TLS.** An imposter with `tls` is served as HTTPS, or as gRPC over TLS, on both
loopback addresses. The CLI makes the certificate, not the daemon, and only when it
posts. `compile!` records the contract's `tls` in `imposter.json` as declared (`true`,
or paths inside the scenario directory), the way it records `modules`.
`ensure-running!` then swaps in PEM text: for `true`, a new certificate for
`localhost`, `127.0.0.1` and `::1` signed by the CA in `$MOCKSYS_HOME/tls/`; for
paths, the files. Three reasons put issuing there. `mocksys tls ca` and the trust lines
`run` prints name the CLI's store, and the daemon on the same admin port may have been
started with another `MOCKSYS_HOME`. `imposter.json` is copied by `pack`, `publish` and
`push`, so it must not hold a key. And the daemon reads no key files. The daemon
checks the pair with `tls.createSecureContext` (a bad pair is a 400 naming `tls`), and
the admin API shows `tls: true` without the key, so a replayable imposter with `tls`
posts back only with new credentials. The CLI's own calls to a TLS mock (`conform`,
`clock`) skip the certificate check: the CLI found the port through its own daemon, and
a contract's own certificate may come from any CA.

**Effects.** The daemon evaluates an `effect` response with `effect/run` over the
imposter's `state`, one object per imposter shared by its stubs. On post, it checks
every effect with the same `effect/errors` the CLI runs, so a bad effect is a 400 that
names the stub and the field (`stubs[3].responses[0].effect: respond.status must be an
HTTP status from 100 to 599`). An imposter response has no form that carries code: a planted
`inject` is refused as an unsupported field. Set `MOCKSYS_NO_INJECTION=1` to make `run`
refuse, before compiling anything, a scenario that lists modules, the only code a
scenario can bring.

**Streams.** An effect that ends in `sse` or `websocket` answers with a stream: the
interpolated script and a channel name. `runtime.stream` serves it. For SSE, it writes
`text/event-stream` over the response, then the script, then the channel's events, with
a keep-alive comment on a timer. For a WebSocket, each imposter's HTTP server takes the
`upgrade` event, sends the upgrade request through the imposter as a `GET`, and accepts
it with `ws` only when the answer is a `websocket` stream; any other answer is written
back as plain HTTP on the socket. Each inbound message then goes through the imposter as
`MESSAGE <path>` with the upgrade's query and headers, one at a time per socket, so it
is matched, run and recorded like any request. The reply becomes outbound messages, or a
close with code 4000 + status. `publish` runs after an effect's terminal and calls each
subscriber of the channel synchronously. A channel map belongs to the imposter, so
reposting a scenario starts with none. A client that leaves unsubscribes and clears its
timers, and an empty channel is deleted. Deleting the imposter destroys its sockets,
which runs the same cleanup. `ws` runs under node, bun and the bun binary.

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
- Every admin call but `GET /` needs the daemon's token, which only the user who
  started the daemon can read. Another local user therefore cannot post an imposter
  and run code as the daemon's owner.
- The local CA's key stays in `$MOCKSYS_HOME/tls/` (mode 0700, the key 0600, and a
  `.gitignore` keeps it out of git). It is never written to `imposter.json`, a bundle
  or a post.
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

# mocksys

**Agent-native CLI for authoring reusable mock fixtures — and full digital twins — of
external systems.**

Describe an API — GitHub, Stripe, an OAuth provider, Okta, an internal service — as a
canonical **contract**, and mocksys hosts it as a local mock your app/test hits
instead of the real thing, then asserts against it like a test oracle. You usually
*author* the contract from API docs, an OpenAPI spec, or source; recording live
traffic is just one way to **seed** a contract, for when you have real credentials.

A contract can be a single endpoint or a **digital twin**: a stateful behavioral clone
of a whole service — seeded resource **collections** with CRUD, SCIM-style filtering and
cursor pagination, plus auth handshakes — generated in one command from a **kit**, and
validated against the real service with `conform`. `mocksys kit okta` stands up an Okta
twin (users & groups CRUD + OIDC) you can drive at high volume, deterministically.

mocksys serves the mocks from its own daemon, which it starts for you and keeps
behind the CLI. You work in agent vocabulary (*service, scenario, contract,
example, matcher, fault, assertion*), never in imposters and stubs.

```sh
mocksys new acme/get-widget                            # scaffold a contract to edit
mocksys add acme/get-widget --request 'GET /widgets/1' --status 200 --body w.json
mocksys run acme/get-widget                            # host the mock (prints a port)
mocksys assert acme/get-widget --saw 'GET /widgets/*'  # exit non-zero on a miss
```

## Why

A mock is most useful as a **reusable capability** you can author, branch, and
share — not a one-off blob:

- **Author without live access.** Hand-write a contract, `import openapi` a spec, or
  `kit` a stateful recipe (e.g. an OAuth provider) — no proxy or real credentials
  needed. Recording is available when you *do* have access, to seed a contract.
- **Branch on request content.** Several operations can share a path and select by
  query/header/body, so picker-vs-redirect, authed-vs-anonymous, and error paths are
  all expressible — not just method+path lookups.
- **gRPC as well as HTTP.** A contract with `protocol: grpc` and a `.proto` serves its
  unary RPCs, statefully, through the same effects, matching and `assert`.
- **Mocks are test oracles.** `assert` checks what the running mock actually received
  and exits non-zero on a miss, so it drops straight into a test script.
- **Secrets never hit disk.** When you *do* record, raw traffic lives only inside the
  running mocksys daemon; `freeze` redacts auth headers and strips volatile headers
  (`Date`, `ETag`, request-ids) **in memory** before writing anything.
- **One shared library.** Every scenario lives in a git-backed store at `~/.mocksys`
  that's the same from every project, and shareable with a remote.
- **Shared mocks can't smuggle in code.** Stateful mocks run generated JavaScript inside
  the daemon, so mocksys only posts what it compiled from a contract. It rebuilds a
  pulled or unpacked `imposter.json` before use, and contract values reach the
  generated code only as escaped literals. The daemon listens on loopback addresses
  only, and its admin API refuses browser requests.

## Install

### Homebrew (standalone binary)

```sh
brew install chazu/mocksys/mocksys
```

A self-contained binary. It needs no Node or other runtime.

### npm

```sh
npm install -g mocksys      # ships prebuilt JS — no JVM needed to install
```

Requires **Node 18+** (for global `fetch`).
`mocksys` starts its daemon for you on first use and leaves it running. The daemon's
admin API listens on `127.0.0.1:2626`. Set `MOCKSYS_ADMIN_PORT` to use another port.
`mocksys daemon stop` stops the daemon and every mock it serves. After an upgrade, the
first command that serves a mock replaces a daemon from the old version and prints
which mocks stopped. The daemon logs to `$MOCKSYS_HOME/daemon.log` (else
`~/.mocksys/daemon.log`).

### Upgrading from a version that used Mountebank

Earlier versions of mocksys served mocks from a Mountebank process on port 2525, and
the upgrade does not stop that process. If it still runs, it holds the ports of the
mocks it served. A scenario with a pinned `--port` then fails to start with
`Port N is already in use`. To find the process that listens on 2525, and then stop it:

```sh
lsof -nP -iTCP:2525 -sTCP:LISTEN     # make sure it is the Mountebank that mocksys started
kill <pid>
```

## Develop / build

`mocksys` is ClojureScript compiled with [shadow-cljs](https://github.com/thheller/shadow-cljs).
The toolchain is pinned in `mise.toml` (Node, a JDK for shadow-cljs, bun), so
[mise](https://mise.jdx.dev) installs all of it. The JDK is a build tool only: the
shipped CLI is plain JavaScript and nothing at runtime needs a JVM.

```sh
git clone https://github.com/chazu/mocksys.git
cd mocksys
mise install                # node, java, bun
mise run test               # unit tests (compiles with shadow-cljs, runs on node)
mise run smoke              # release build + end-to-end CLI checks against a live daemon
mise run watch              # recompiles out/mocksys.js on save (keep running)
./bin/mocksys help          # node shim over the compiled output
```

`mise tasks` lists the rest. One-shot build: `mise run build`. A standalone binary (no
Node or JVM at runtime; it runs its own daemon too) is produced with
[bun](https://bun.sh):

```sh
mise run binary            # -> dist/mocksys (native)
mise run binary all        # -> dist/mocksys-bun-{darwin,linux}-{arm64,x64}
```

Cut a release (build matrix + tarballs + checksums + patch the Homebrew formula):

```sh
scripts/release.sh v0.1.0 --publish   # also creates the GitHub release via gh
```

The Homebrew tap lives in `packaging/homebrew/` — see its README for publishing.

## The core loop

```sh
mocksys init github                          # one-time: load the service profile
mocksys record github/create-issue           # proxy that records → the real API
#   ... exercise the proxy URL it prints ...
mocksys inspect github/create-issue          # agent-readable summary of what was captured
mocksys freeze github/create-issue           # scrub + save as a replay fixture
mocksys run github/create-issue              # host the mock (prints a port)
```

Service templates (`github`, `gitlab`, `aws-s3`, `stripe`, `generic-http`) supply the
default target, which headers carry secrets, which are volatile, and which env var the
consumer reads — so a scenario named `service/name` inherits all of it.

## Wire a test or app to a mock

```sh
eval "$(mocksys env github/create-issue)"    # exports GITHUB_API_URL=http://localhost:PORT, etc.
# point your code at $GITHUB_API_URL, run it, then verify what it called:
mocksys assert github/create-issue --saw 'POST /repos/*/issues'   # exit 1 on miss
mocksys requests github/create-issue         # everything the mock received
```

## Author from a spec, docs, or source — no recording

Recording is one way in. The other is a **contract**: a human/agent-editable
`contract.yaml` that mocksys *compiles* to an imposter for the daemon. The contract is
the source of truth; `imposter.json` is a build artifact that `run` recompiles
automatically when the contract changes.

**From an OpenAPI/Swagger spec** — one scenario per operation, the formal schemas
kept primary, every spec example imported *by name* (plus a schema-sampled
`generated` example to fill gaps), and a service profile synthesized from the spec's
servers + security schemes:

```sh
mocksys import openapi ./stripe.yaml --service stripe
mocksys examples stripe/createcharge          # ids are the slugged operationId; [x] = in play
mocksys use stripe/createcharge --op createcharge --example card_declined --only
mocksys run stripe/createcharge
```

**By hand, after reading API docs or source** — scaffold a contract and edit it, or
append endpoints with `add`:

```sh
mocksys new acme/get-widget                   # writes a contract.yaml to edit
mocksys add acme/list-widgets --request 'GET /widgets' --status 200 --body w.json
mocksys validate acme/list-widgets            # structural + example-vs-schema check
```

Selecting examples is how you author variants: each `select: true` example becomes a
response the mock serves (the mock cycles through several), so swapping the happy
path for an error is just `use ... --example <error> --only`. Transport faults layer
on top and are stored on the contract, so they survive recompiles:

```sh
mocksys fault github/create-issue --latency 2000   # or --status 500 / --timeout / --drop-connection / --clear
mocksys parameterize github/create-issue --path '/repos/{owner}/{repo}/issues'   # exact path -> {param} template
```

Recorded scenarios are contract-canonical too: `freeze` lifts a contract from the
scrubbed recording, and any legacy imposter-only scenario gets one the first time a
contract-aware command touches it.

## Digital twins (kits, collections, conform)

A **kit** generates a complete, stateful behavioral clone of a service in one command —
no operation-by-operation authoring. Kits are composed from reusable **genes** (an OIDC
handshake, a CRUD resource, an error envelope); `mocksys genes` lists them.

```sh
mocksys kit okta                              # users & groups CRUD + OIDC, on one stateful mock
mocksys run okta/okta                         # host it (prints a port)
mocksys genes                                 # the behavioral genes a kit composes
```

The Okta twin is a real resource system, not static fixtures: seeded **collections** with
create / list / get / update / delete, SCIM-style filters and cursor pagination, and the
Okta error envelope on misses — all driven by a declarative `effect`, never hand-written
JavaScript:

```sh
curl -G localhost:PORT/api/v1/users --data-urlencode 'filter=status eq "ACTIVE"'   # SCIM filter
curl -X POST localhost:PORT/api/v1/users -d '{"profile":{"login":"x@y.com"}}'   # 201, id minted
curl 'localhost:PORT/api/v1/users?limit=2'                      # paginated; Link: rel="next"
```

Author your own stateful resource by declaring a collection in `contract.yaml`:

```yaml
state:
  collections: { users: { idField: id, seed: users } }
operations:
  - id: get-user
    request: { method: GET, path: "/api/v1/users/{id}" }
    effect:
      bind: { id: { pathRegex: "/users/([^/]+)$" } }
      get:  { collection: users, key: "{id}", miss: { status: 404, json: { errorCode: "E0000007" } } }
```

**Validate fidelity with `conform`** — the digital-twin loop. Replay a request corpus at
the twin and (with credentials) the live service, diff the responses (bodies canonicalized,
`--ignore` keys masked, volatile headers dropped with `--headers`), and exit non-zero on drift:

```sh
mocksys conform okta/okta --corpus calls.json                    # golden: each status vs its `expect`
mocksys conform okta/okta --corpus calls.json --against https://your.okta.com   # twin vs live
mocksys conform okta/okta --corpus calls.json --against URL --ignore created,lastUpdated
```

A corpus is a JSON array of `{method, path, body?, expect?}`; omit `--corpus` to replay
what the running twin has already received. Drive the twin toward zero observable delta.

## Shared store + sharing

All scenarios live in one git-backed library at `~/.mocksys` (override with
`MOCKSYS_HOME`; set it to `./.mocks` to scope mocks to a single project).

```sh
mocksys home                                 # store path + git status
mocksys status                               # which scenarios are new/edited/removed
mocksys log --n 10                           # recent store history
mocksys publish github/create-issue          # git-commit a frozen scenario (inits the repo on first use)
mocksys rm github/create-issue               # delete a scenario (or a whole service); commits the removal
mocksys restore github/create-issue          # discard uncommitted local edits
mocksys remote git@github.com:org/mocks.git  # one-time: set the share remote
mocksys push     /     mocksys pull          # sync with teammates
```

Need git-less sharing? Bundle a scenario as a single self-describing file:

```sh
mocksys pack github/create-issue             # → github-create-issue.mock.tgz (includes the service profile)
mocksys unpack github-create-issue.mock.tgz  # restores it into another store
```

## Command reference

| | |
|---|---|
| `init <service>` | materialize a service template |
| `record <service/name> [--target URL] [--port N]` | start a recording proxy |
| `freeze <name> [--draft]` | scrub + save the recording as a fixture (`--draft`: land for review) |
| `promote <name>` | make a reviewed draft the canonical contract |
| `run <name> [--port N] [--env VAR]` | host a fixture as a mock |
| `env <name>` | `eval "$(...)"` — wire a shell to the mock |
| `inspect <name>` | agent-readable fixture summary |
| `doctor <name> [--fix]` | lint matchers/volatility; `--fix` cleans in place |
| `requests <name> [--clear]` | what the running mock received |
| `assert <name> --saw 'METHOD /path'` | verify a call happened (exit 1 on miss) |
| `kit <okta\|oauth2> [--scenario NAME] [--port N]` | generate a full stateful twin in one command |
| `kits` | list the kits, built in and in `$MOCKSYS_HOME/kits` (a file, or a directory that ships modules), with their flags |
| `genes` | list the genes a contract can list under `genes:`, with their params |
| `conform <name> --corpus FILE [--against URL] [--ignore k1,k2]` | replay+diff vs reality (exit 1 on drift) |
| `clock <name> [advance <1h\|30m> \| set <ts>]` | read/move a twin's deterministic virtual clock |
| `import openapi <spec> [--service NAME] [--target URL]` | spec → one scenario per operation |
| `new <service/name> [--service S]` | scaffold a blank contract to hand-author |
| `compile <name>` | rebuild `imposter.json` from `contract.yaml` (usually automatic) |
| `validate <name>` | structural + example-vs-schema check (exit 1 on errors) |
| `examples <name> [--op ID]` | list examples and which are in play |
| `use <name> --op ID --example NAME [--only \| --off]` | select example(s) to serve |
| `add <name> --request 'METHOD /path' [--status N] [--body FILE \| --text STR]` | author an endpoint |
| `fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection] [--clear]` | inject failures |
| `parameterize <name> --path '/a/{var}/b'` | loosen exact paths to templates |
| `pack <name> [--out FILE] \| --stdout` / `unpack <file>` | portable bundles |
| `ls` / `stop <name> \| --all` | catalog of scenarios / tear down |
| `daemon stop` | stop the daemon and every mock it serves |
| `stack <name> <scenario>...` / `up <stack>` / `down <stack>` | define a named set of scenarios / bring it all up or down |
| `rm <scenario\|service>` (alias `delete`) | delete from the store (stops it if live; commits the removal) |
| `status` / `log [--n N]` | uncommitted changes / recent store history |
| `restore <name> \| --all` | discard uncommitted local changes |
| `home` / `publish` / `remote` / `push` / `pull` | the git-backed store |
| `prime` | one-screen orientation for an agent |

Run `mocksys prime` to drop the whole workflow into an agent's context. The repo also
ships a Claude Code skill at [`.claude/skills/mocksys/`](.claude/skills/mocksys/SKILL.md)
so agents discover the tool and know when to reach for it (it defers to `mocksys prime`
and `docs/` for depth).

## Documentation

This README is the quick start. The full reference manual lives in [`docs/`](docs/index.md):

- [docs/walkthrough.md](docs/walkthrough.md) — **start here:** a friendly end-to-end tour that builds an Okta twin and explains every part.
- [docs/architecture.md](docs/architecture.md) — modules, the contract→imposter→daemon pipeline, the store, the runtime.
- [docs/contracts.md](docs/contracts.md) — the `contract.yaml` format: matching, responses, examples, templating, faults, validation.
- [docs/effects.md](docs/effects.md) — the stateful `effect` language: state buckets and resource collections, every verb, the SCIM filter grammar, pagination.
- [docs/twins.md](docs/twins.md) — building digital twins: kits, genes, the `okta` anatomy, and the `conform` fidelity loop.
- [docs/cli.md](docs/cli.md) — the complete command reference.

Runnable example contracts are in [`examples/`](examples/) (the ones the walkthrough uses).

## How it's built

ClojureScript compiled with [shadow-cljs](https://github.com/thheller/shadow-cljs) to a
Node script (`out/mocksys.js`), shipped as prebuilt JS over npm and as a
`bun build --compile` binary via Homebrew. Focused
modules: `core` (entry point + command table), `cli.*` (one namespace per command
group), `lifecycle` (compile/run a scenario), `mb` (admin-API client + daemon lifecycle), `runtime` (the daemon: admin API,
imposter servers, predicate matching, injection), `store` (disk layout), `scrub`
(redaction/volatility), `analyze` (matcher hygiene), `service` (templates), `contract`
(the canonical source format — compile/lift/validate/example selection), `inject` (the
declarative stateful layer — handshakes + resource collections, compiled to generated
injections), `kits` (kits + composable genes — `okta`, `oauth2`), `conform`
(differential fidelity vs reality), `openapi` (spec import, via
`@apidevtools/swagger-parser` + `openapi-sampler`), `bundle` (pack/unpack), `git` (the
shared store). Design notes and plans live in [`docs/design/`](docs/design/).

## License

[MIT](LICENSE) © 2026 Chaz Straney

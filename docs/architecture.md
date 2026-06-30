# Architecture

## What mocksys is

mocksys is a thin, opinionated CLI over [Mountebank](https://www.mbtest.org/).
Mountebank is the *runtime* — an HTTP mock server with a REST admin API — kept entirely
behind the CLI. You work in agent vocabulary (*service, scenario, contract, example,
effect, kit, twin*), never in Mountebank's *imposters* and *stubs*. mocksys never writes
Mountebank config files by hand; it drives a live `mb` daemon over its admin API on
`:2525`.

The guiding split:

> **The contract is the source of truth. The imposter is a build artifact.**

You author (or generate, or record) a `contract.yaml`. mocksys *compiles* it to an
`imposter.json` and posts that to Mountebank. Any contract-aware command recompiles a
stale imposter automatically.

## Module map

mocksys is ClojureScript compiled with [shadow-cljs](https://github.com/thheller/shadow-cljs)
to a Node script (`out/mocksys.js`). Each module is focused:

| Module | Responsibility |
|---|---|
| `core` | The CLI: arg parsing, command dispatch, orchestration, all user-facing output. |
| `contract` | The canonical contract format. `lower` compiles a contract → imposter; `lift` reverse-engineers a contract from a recorded imposter; plus validation, example selection, response templating, path/predicate compilation. |
| `inject` | The **stateful** layer. Compiles a declarative `effect` into a generated, dependency-free Mountebank response injection over a shared `state` object — handshake buckets and resource collections. The agent never writes JavaScript. |
| `kits` | **Kits** and composable **genes**. A gene is a parameterized contract fragment (an OIDC handshake, a CRUD resource, an error envelope); a kit composes genes into a full twin. Ships `oauth2` and `okta`. |
| `conform` | Differential fidelity: replay a request corpus at the twin and (optionally) the live service, then diff responses normalized by volatility. |
| `mb` | The Mountebank admin-API client and daemon lifecycle (start/relaunch, `--allowInjection`, free-port allocation, imposter CRUD, replayable pull). |
| `store` | On-disk layout under `~/.mocksys`: contracts, imposters, drafts, recordings, services, stacks. YAML read/write. |
| `service` | Service templates (github, gitlab, aws-s3, stripe, generic-http): redact/volatile headers, default target, the consumer's base-URL env var. Composed with the base sets in `analyze`. |
| `scrub` | Redaction (secrets → a marker) and volatility stripping, in memory, on `freeze`. |
| `analyze` | Pure analysis over an imposter — the base secret/volatile header sets, matcher-hygiene/overfit detection — feeding `inspect` and `doctor`. |
| `openapi` | OpenAPI/Swagger import: one scenario per operation, schemas + named examples, a synthesized service profile. |
| `bundle` | `pack`/`unpack` — a scenario as a single self-describing `.mock.tgz` (git-less sharing). |
| `git` | The git-backed shared store (`publish`/`status`/`log`/`remote`/`push`/`pull`/`restore`). |

## The compile pipeline

```
contract.yaml ──lower──▶ imposter.json ──POST /imposters──▶ Mountebank daemon ──▶ :PORT
   (source)              (build artifact)                     (the runtime)        (your app hits this)
```

`contract/lower` walks each operation and emits a Mountebank **stub**:

- **Static operation** → predicates (from `request`) + a `responses` array. Each selected
  example becomes an `{is {...}}` response (Mountebank round-robins multiple). Response
  templating (`{{request.*}}`) lifts into Mountebank `copy` behaviors; a transport
  `fault` overlays a `wait`/`fault` behavior.
- **Stateful operation** (`effect` present) → a single `{inject "<generated JS>"}`
  response. `inject/gen` embeds the seed and collection declarations as literals and
  emits a self-contained function over `(request, state, logger)`.

Predicate compilation: `method`+`path` by default; a templated path (`/a/{id}`) compiles
to a `matches` regex (`^/a/[^/]+$`), an exact path to `deepEquals`. Optional
`query`/`headers`/`body` conditions add `exists`/`equals`/`matches` predicates so several
operations can share a path and branch on request content (Mountebank ANDs predicates;
stub order = contract order; first match wins).

## The on-disk store

One shared, git-backed library — the same from every project. Location: `$MOCKSYS_HOME`,
else `~/.mocksys`. (Set `MOCKSYS_HOME=./.mocks` to scope mocks to a single project.)

```
~/.mocksys/
  services/<service>.yaml          # service profile (redact/volatile/target/env)
  stacks/<name>.yaml               # a named set of scenarios + ports
  <service>/<name>/
    contract.yaml                  # the source of truth
    contract.draft.yaml            # a recording awaiting `promote` (optional)
    imposter.json                  # compiled build artifact
    mock.yaml                      # display metadata (service, port, origin, stub count)
    recording.yaml                 # record→freeze handoff state (transient)
```

A scenario id may nest (`github/create-issue`) — Node's path join turns it into a real
subdirectory. The catalog (`ls`) walks for `imposter.json`. `contract-stale?` triggers a
recompile when `contract.yaml` is newer than `imposter.json` (or the artifact is
missing); a legacy imposter-only scenario with no contract is never stale.

## The runtime

`run` ensures the `mb` daemon is up and posts the imposter with `recordRequests=true`
(so `requests`/`assert`/`conform` have data). Port resolution order: explicit `--port` >
the already-running port > a port **pinned** on the contract > a fresh free port. An
explicit `--port` is persisted onto the contract as a pin. `run --watch` recompiles and
reloads on every `contract.yaml` change (the Tilt-style save-and-reload loop).

**Injection.** Stateful scenarios need the daemon launched with `--allowInjection`. mocksys
manages this: `ensure-up!` starts an injecting daemon when a stateful scenario runs, and
`post-imposter-injecting!` relaunches the daemon with the flag and retries if a running
daemon rejects injection. Set `MOCKSYS_NO_INJECTION=1` to refuse stateful scenarios.

**State lifetime.** Mountebank persists the `state` object for the imposter's lifetime.
Reposting an imposter (every `run`, and every `conform`) creates a fresh instance, so
collections are reseeded — each `conform` run starts from a clean, deterministic baseline.

## Stateless vs stateful, at a glance

|  | Static operation | Stateful operation (`effect`) |
|---|---|---|
| Source | `responses` + selected `examples` | `effect` verbs + `state` |
| Compiles to | `{is {...}}` (+ `copy`/`fault` behaviors) | `{inject "<generated JS>"}` |
| Dynamic from request | response templating `{{request.*}}` | `bind` + `{var}` interpolation |
| Cross-request memory | none | shared `state` (buckets + collections) |
| Needs `--allowInjection` | no | yes (managed automatically) |
| Use for | fixed fixtures, error variants, faults | auth handshakes, CRUD resources, whole twins |

See [contracts.md](contracts.md) for static operations and [effects.md](effects.md) for
stateful ones.

---
name: mocksys
description: >-
  Stand up local mocks and full digital twins of external HTTP and gRPC APIs (GitHub, Stripe,
  Okta, OAuth/OIDC providers, internal services) for tests and local dev — no real
  credentials or network. Use when you need to fake an external dependency: a simple
  fixture, a stateful resource API (CRUD + SCIM-style filtering + pagination), an
  auth handshake (authorize→token→userinfo), rate limiting (429s + X-Rate-Limit-*),
  or token/session expiry; to assert what your code called; or to validate a mock
  against the real service. Triggers: "mock the X API", "stub this service", "fake an
  OAuth/Okta provider", "spin up a mock server", "test against a fake <service>",
  "record and replay an API", "digital twin of <service>".
---

# mocksys

`mocksys` is a CLI that hosts local mocks of external systems from a declarative
`contract.yaml` — your app/test hits the mock instead of the real API. It scales from a
fixed fixture to a full stateful **digital twin** (e.g. an Okta clone: users/groups CRUD,
SCIM filtering, OIDC login, rate limits, expiring tokens). mocksys serves mocks from its
own daemon, kept entirely behind the CLI.

## Do this first

Run `mocksys prime` and read it — it is the authoritative, always-current orientation (the
full workflow and every command family). This skill is the trigger and a map; **`prime` is
the source of truth.** If a command here ever disagrees with `prime`, trust `prime`.

```sh
mocksys prime     # full agent orientation — read it before doing anything else
mocksys kits      # the kits available, built in and yours, with their flags
mocksys genes     # the genes (with params) a contract can list under `genes:`
mocksys help      # terse flag list
```

## When to use it

- You need a fake external HTTP API for a test or local dev (no creds, no network).
- A simple fixture, **or** a stateful service: a REST resource (CRUD + `filter=` +
  pagination), an OAuth/OIDC flow, rate limiting, or token/session expiry.
- You want to verify your code's calls (`assert`) or check a mock against reality
  (`conform`).

## Fastest path — a twin in one command

```sh
mocksys kit okta --port 9200            # users+groups CRUD, SCIM filter, OIDC, one stateful mock
#   add --rate-limit 100 --token-ttl 3600 for 429s + expiring tokens
mocksys run okta/okta --port 9200
eval "$(mocksys env okta/okta)"         # exports BASE_URL=http://localhost:9200 — point code at it
```

`mocksys kit oauth2 --provider github` is the other built-in twin. `mocksys kits` lists
every kit with its flags; `mocksys genes` lists the reusable genes a kit composes, with
their params. A kit is a YAML file (a contract template plus flags with defaults); drop
your own in `$MOCKSYS_HOME/kits/` and `mocksys kit <name>` uses it (see `docs/twins.md`).
A kit that ships custom-verb modules is a directory, `kits/<name>/kit.yaml` with the
modules beside it; `kit` copies them into the scenario, and refuses to overwrite one you
edited unless you pass `--force` (`examples/kits/webhooks/`).

## Authoring your own (no kit)

A `contract.yaml` lists operations. Static ops return fixed/example bodies; **stateful**
ops use a declarative `effect` (no JavaScript) over shared state — collections (CRUD),
buckets (handshakes), a virtual clock, rate limits.

A contract can also list **genes** (reusable fragments) instead of writing their
operations out. They expand after your own operations, so yours win on the same request:

```yaml
genes:
  - crud-collection: { coll: users, base: /api/v1/users, seed-name: users, id-prefix: u }
  - membership: { users-coll: users, groups-coll: groups }
```

`use` and `parameterize` refuse gene operations; change the gene's params instead.

When a rule needs logic no verb expresses (an HMAC signature, a SigV4 check), write a
**custom verb**. Put a CommonJS file in the scenario directory, list it under
`modules:`, and run its verb with `call`:

```yaml
modules: [./verbs.js]        # module.exports = { verbs: { verifySignature(ctx) { ... } } }
operations:
  - id: receive
    request: { method: POST, path: /webhooks }
    effect:
      bind: { sig: { header: X-Hub-Signature-256 } }
      call: { verb: verifySignature, args: { sig: "{sig}" }, as: event }
      respond: { status: 202, json: { received: "{event.action}" } }
```

A verb gets `ctx = { request, state, vars, args, now, respond }`. It returns a value,
which is bound to `as`, or `ctx.respond({ status, headers, json | text | body })`, which
ends the request. Verbs must be synchronous. A throw is a 500 that names the verb. Read
time from `ctx.now`: `Date.now()` and `Math.random()` make a twin differ between runs.
Only the daemon runs module code, on `run`; `validate` checks paths and `call` specs, and
`run` fails up front on a missing verb. `examples/webhooks/` is a runnable receiver.

```sh
mocksys new acme/widgets                                   # scaffold a contract
mocksys add acme/widgets --request 'GET /widgets/1' --status 200 --text ok
mocksys validate acme/widgets && mocksys run acme/widgets
```

**gRPC.** Set `protocol: grpc` and `proto: ./x.proto` (a file in the scenario directory)
and give each operation `request: { rpc: pkg.Service/Method }` in place of method and
path. A call is matched as `POST /pkg.Service/Method` with metadata as
headers and the message as a JSON body, so effects and `assert` work unchanged. The body
holds only the fields the caller set, so a `mergeVar` update keeps the rest. A 2xx
JSON body is the reply, a 404 reaches the client as `NOT_FOUND`, and a body
`{ grpcCode, message }` picks the code. The reply is checked against its type: a value
of the wrong type is `INTERNAL` naming the field, and `daemon.log` says the same.
Streaming RPCs work too: a server stream sends a 2xx JSON array as its messages (a
`list` effect streams one per record), a client stream's body is the JSON array of what
it sent, and each message of a bidirectional stream is its own request. Every grpc
mock serves server reflection, so `grpcurl -plaintext localhost:PORT list` works
without the proto. `mocksys record <name> --target host:port --proto ./x.proto` records a
gRPC upstream (unary, server and client streams); `freeze` lifts it to a grpc contract. `run`/`env` print `localhost:PORT`; connect with
insecure credentials. See `docs/contracts.md#grpc` and `examples/grpc-todos/`.

**TLS.** When the code under test needs `https://` or a TLS gRPC channel, add `tls: true`
to the contract. `run` issues the mock a certificate for localhost from mocksys's local
CA and prints `https://localhost:PORT` (gRPC: `localhost:PORT (gRPC over TLS)`). Make
the client trust the CA rather than turning verification off:
`export NODE_EXTRA_CA_CERTS="$(mocksys tls ca)"` for node, which `mocksys env` also
exports, or `curl --cacert "$(mocksys tls ca)"`. Never copy `$MOCKSYS_HOME/tls/ca.key`
anywhere. See `docs/contracts.md#tls` and `examples/https.yaml`.

The full effect-language reference is `docs/effects.md`; `docs/walkthrough.md` is a
narrated tour that builds the Okta twin; runnable contracts are in `examples/`.

## Verify / drive time

```sh
mocksys assert <name> --saw 'POST /repos/*/issues'                 # exit 1 if not seen
mocksys conform <name> --corpus calls.json [--against https://real.api]  # diff vs reality, exit 1 on drift
mocksys clock <twin> advance 1h                                    # move the deterministic virtual clock
```

## Notes for agents

- Scenarios live in one git-backed store at `~/.mocksys` (override `MOCKSYS_HOME`; set
  `MOCKSYS_HOME=./.mocks` to scope to a project). A daemon serves one store, and each
  store gets its own admin port, so a project store runs beside `~/.mocksys` with no
  other setting.
- `mocksys` starts its daemon for you and leaves it running; never run `mocksys daemon`
  yourself. Its admin API is `127.0.0.1:2626` for `~/.mocksys`, else a port in
  26000–26999 derived from the store's path; `mocksys home` prints it, and
  `MOCKSYS_ADMIN_PORT` overrides it. The error "belongs to another store or user" means
  that port is held by another store's daemon, and names its pid.
  `mocksys daemon stop` stops it and every running mock. If the daemon fails to start,
  read `$MOCKSYS_HOME/daemon.log`.
- **Quote any contract path containing `{param}`**: `path: "/users/{id}"` (unquoted YAML
  flow-parsing mis-reads `{...}` as a map).
- `assert` / `validate` / `conform` exit non-zero on failure — they drop into CI.
  A mock keeps only its newest 10000 requests; run `mocksys requests <name> --clear`
  before the calls you assert on. `MOCKSYS_MAX_RECORDED_REQUESTS` (a whole number above
  0) changes the limit. The daemon reads it when it starts, so run `mocksys daemon stop`
  first, then set it on the next command.
- `run`/`env` print the port; `eval "$(mocksys env <name>)"` wires a shell to it.

## Deeper references (read on demand)

| Need | Read |
|---|---|
| Full in-CLI orientation (authoritative) | `mocksys prime` |
| Friendly end-to-end tour (build an Okta twin) | `docs/walkthrough.md` |
| The stateful effect language (every verb) | `docs/effects.md` |
| The static contract format | `docs/contracts.md` |
| Kits, genes, the conform fidelity loop | `docs/twins.md` |
| Every command + flag | `docs/cli.md` |
| How the pieces fit | `docs/architecture.md` |
| Runnable example contracts | `examples/` |

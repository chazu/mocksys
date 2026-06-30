---
name: mocksys
description: >-
  Stand up local mocks and full digital twins of external HTTP APIs (GitHub, Stripe,
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
SCIM filtering, OIDC login, rate limits, expiring tokens). Mountebank is the runtime, kept
entirely behind the CLI.

## Do this first

Run `mocksys prime` and read it — it is the authoritative, always-current orientation (the
full workflow and every command family). This skill is the trigger and a map; **`prime` is
the source of truth.** If a command here ever disagrees with `prime`, trust `prime`.

```sh
mocksys prime     # full agent orientation — read it before doing anything else
mocksys genes     # the kits + behavioral genes available
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

`mocksys kit oauth2 --provider github` is the other built-in twin. `mocksys genes` lists
the reusable genes a kit composes.

## Authoring your own (no kit)

A `contract.yaml` lists operations. Static ops return fixed/example bodies; **stateful**
ops use a declarative `effect` (no JavaScript) over shared state — collections (CRUD),
buckets (handshakes), a virtual clock, rate limits.

```sh
mocksys new acme/widgets                                   # scaffold a contract
mocksys add acme/widgets --request 'GET /widgets/1' --status 200 --text ok
mocksys validate acme/widgets && mocksys run acme/widgets
```

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
  `MOCKSYS_HOME=./.mocks` to scope to a project).
- `mocksys` starts/stops Mountebank for you — never run `mb` directly.
- **Quote any contract path containing `{param}`**: `path: "/users/{id}"` (unquoted YAML
  flow-parsing mis-reads `{...}` as a map).
- `assert` / `validate` / `conform` exit non-zero on failure — they drop into CI.
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

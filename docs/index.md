# mocksys documentation

mocksys is an agent-native CLI for authoring reusable mock fixtures — and full
**digital twins** — of external systems. You describe an API as a canonical
**contract**; mocksys hosts it as a local mock (over Mountebank) that your app or test
hits instead of the real thing, and asserts against it like a test oracle.

This is the reference manual. For installation and a 60-second tour, see the
[README](../README.md). For an agent's one-screen orientation, run `mocksys prime`.

## Map

| Doc | What it covers |
|---|---|
| [architecture.md](architecture.md) | The system: modules, the contract→imposter→Mountebank pipeline, the on-disk store, the runtime, stateless vs stateful. |
| [contracts.md](contracts.md) | The `contract.yaml` format: operations, request matching, responses, named examples, response templating, transport faults, validation. |
| [effects.md](effects.md) | The declarative **stateful** layer — the `effect` language. State buckets and resource **collections**, every verb, the SCIM filter grammar, pagination, and the generated-injection model. |
| [twins.md](twins.md) | Building **digital twins**: kits, composable **genes**, the `okta` twin anatomy, and the `conform` fidelity loop. |
| [cli.md](cli.md) | Full command reference, grouped, with every flag. |

## The shape of the system in one paragraph

A **scenario** (named `service/name`) is described by a `contract.yaml` — the single
source of truth. mocksys *compiles* it to a Mountebank `imposter.json` (a build
artifact) and serves it. A contract operation is either **static** (a status + headers
+ a selected example body, optionally templated from the request) or **stateful** (a
declarative `effect` that mocksys compiles to a generated JavaScript injection running
over a persistent per-imposter `state` object — an auth handshake, or a CRUD resource
collection). A **kit** composes reusable **genes** into a whole twin in one command;
**conform** replays a request corpus at the twin and the live service and diffs them,
driving the twin toward zero observable delta. Everything lives in one git-backed store
at `~/.mocksys`.

## Conventions in these docs

- YAML examples use block style. **Quote any path containing `{param}`**
  (`path: "/users/{id}"`) — unquoted, YAML flow-parsing mistakes `{...}` for a map.
- `{var}` (single brace) is **effect-time** variable interpolation (see
  [effects.md](effects.md)). `{{request.query.x}}` (double brace) is **static** response
  templating (see [contracts.md](contracts.md)). They are different mechanisms.
- Commands are shown as `mocksys <cmd>`; a scenario id is `service/name`.

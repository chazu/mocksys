# The contract format

A `contract.yaml` is the canonical description of a scenario. This document covers the
**static** parts (request matching, responses, examples, templating, faults). The
**stateful** `effect` and `state` keys have their own reference: [effects.md](effects.md).

mocksys reads contracts string-keyed (no keyword coercion) so embedded JSON Schemas and
example bodies round-trip faithfully — arbitrary property names stay strings.

## Top-level shape

```yaml
service:  github                 # the owning service (first path segment of the scenario id)
scenario: github/create-issue    # the scenario id
origin:   authored               # authored | recorded | openapi | kit  (provenance; see below)
summary:  "..."                  # optional human/agent description
port:     9099                   # optional pinned port (honored by run/env/up)
fault:    {}                     # optional transport fault overlay (see Faults)
seed:     {}                     # named arrays for effects to read (see effects.md)
state:    {}                     # bucket/collection declarations (see effects.md)
operations:
  - { ... }                      # one or more operations
```

`origin` is provenance and it matters: `doctor` only flags over-specific matchers on
`recorded` (or origin-less) scenarios — authored/imported/kit predicates are treated as
intentional branching, not overfitting.

## Operations

Each operation is one endpoint (one Mountebank stub). It has an `id`, an optional
`summary`, a `request` (how to match), and **either** `responses` (static) **or** an
`effect` (stateful — see [effects.md](effects.md)).

```yaml
operations:
  - id: createIssue
    summary: open an issue
    request:
      method: POST
      path: "/repos/{owner}/{repo}/issues"
      match: [method, path]      # which of method/path to match on (default both)
    responses:
      - status: 201
        headers: { Content-Type: application/json }
        bodySchema: { ... }      # optional JSON Schema (kept primary; sampled if no example selected)
        examples:
          happy:   { select: true,  body: { number: 1, state: open } }
          gone:    { select: false, body: { message: "Not Found" } }
```

### Request matching

| Field | Meaning |
|---|---|
| `method` | HTTP method (compared upper-case). |
| `path` | Exact path, or an OpenAPI-style template `"/a/{id}/b"`. A template compiles to a one-segment-per-`{param}` regex (`^/a/[^/]+/b$`); an exact path to `deepEquals`. **Quote any path with `{`.** |
| `match` | Which of `["method" "path"]` to require. Default both. |
| `query` | Request-conditional predicates on query params (see below). |
| `headers` | Request-conditional predicates on headers. |
| `body` | Request-conditional predicates on JSON body fields (JSONPath selectors). |

**Request-conditional branching.** `query`/`headers`/`body` let several operations share
a method+path and select by content. Each is a `{field: condition}` map; a condition is:

```yaml
query:
  login:  { present: true }      # exists predicate
  type:   { equals: "user" }     # equals predicate   (a bare scalar is shorthand for equals)
  ref:    { matches: "^v[0-9]" } # regex predicate
```

Mountebank ANDs the predicates and serves the **first matching stub in contract order** —
so order operations specific-first, with a fallback last. `body` uses JSONPath selectors
(`{ "$.action": { equals: "opened" } }`) and fits JSON request bodies.

> Authoring shortcut: `mocksys add <name> --request 'GET /authorize' --when-query 'login'`
> appends a branch operation. See [cli.md](cli.md).

### Responses and named examples

A `response` carries a `status`, optional `headers`, an optional `bodySchema`, and a map
of **named examples**. An example with `select: true` becomes a served response; mocksys
round-robins multiple selected examples. If no example is selected and a `bodySchema`
exists, mocksys serves one sampled from the schema.

Authoring variants is just (de)selecting examples — swap the happy path for an error
without editing bodies:

```sh
mocksys examples stripe/createCharge                              # list, [x]=in play
mocksys use stripe/createCharge --op createCharge --example card_declined --only
```

`--only` clears the operation's other selections first (the happy↔error swap).

### Response templating (`{{request.*}}`)

A static response body or header may splice values from the **incoming request** at serve
time — no injection, no state. Tokens:

```
{{request.query.<name>}}    {{request.headers.<name>}}    {{request.body.<jsonpath>}}
{{request.path}}            {{request.method}}
```

Each compiles to a Mountebank `copy` behavior. Example — a redirect that echoes `state`
and a code back:

```yaml
responses:
  - status: 302
    headers:
      Location: "https://app/cb?code=abc&state={{request.query.state}}"
    examples: { redirect: { select: true } }
```

> This is for *reflecting* request values into a static response. For *computed* or
> *remembered* values (mint an id, look it up later), use a stateful `effect`
> ([effects.md](effects.md)). Note the brace count: `{{request.x}}` is static templating;
> `{x}` is effect-time interpolation.

## Transport faults

A `fault` overlay injects failure on top of an operation's normal response. It lives on
the contract, so it survives recompiles, and (except connection/protocol faults) coexists
with templating.

```yaml
fault:
  status: 500          # override the status code
  latency: 2000        # add a 2s wait (ms)
  timeout: true        # hang (~120s wait)
  drop-connection: true# CONNECTION_RESET_BY_PEER
  malformed-json: true # RANDOM_DATA_THEN_CLOSE
```

Set via `mocksys fault <name> --latency 2000` (or `--status` / `--timeout` /
`--drop-connection` / `--malformed-json` / `--clear`).

## Validation

`mocksys validate <name>` (exit 1 on errors):

- **Errors** (block compile): no operations; a request missing `method` or an absolute
  `path`; an `effect` with no `respond` and no collection verb; a response `status` that
  isn't a number; an operation with nothing to serve (no selected example, no
  `bodySchema`, no effect).
- **Warnings** (advisory): a selected example whose body diverges from its response
  `bodySchema` (lightweight, ajv-free: top-level type, required object props, one-level
  recurse).

## Authoring entry points

| You have… | Use |
|---|---|
| API docs / source in your head | `mocksys new <service/name>`, then edit; or `mocksys add ...` to append operations |
| an OpenAPI/Swagger spec | `mocksys import openapi <spec> --service <name>` |
| a whole service to clone | `mocksys kit okta` / `kit oauth2` ([twins.md](twins.md)) |
| live credentials | `mocksys record` → exercise the proxy → `mocksys freeze` (optionally `--draft` then `promote`) |

The `imposter.json` is always derivable — never hand-edit it; edit `contract.yaml` and let
`run`/`compile` rebuild.

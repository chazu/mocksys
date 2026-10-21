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
issuer:   "http://..."           # optional origin for baseUrl/JWT iss when a request has no Host header
protocol: http                   # http (default) or grpc (see gRPC)
proto:    ./todo.proto           # grpc only: the .proto file, relative to the scenario directory
seed:     {}                     # named arrays for effects to read (see effects.md)
state:    {}                     # collections, clock, randomSeed (see effects.md)
genes:                           # optional: reusable fragments that expand into operations (see Genes)
  - crud-collection: { coll: users, base: /api/v1/users, seed-name: users }
operations:
  - { ... }                      # hand-written operations, served before gene operations
```

`origin` is provenance and it matters: `doctor` only flags over-specific matchers on
`recorded` (or origin-less) scenarios — authored/imported/kit predicates are treated as
intentional branching, not overfitting.

## Operations

Each operation is one endpoint (one stub in the compiled imposter). It has an `id`, an optional
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

The daemon ANDs the predicates and serves the **first matching stub in contract order** —
so order operations specific-first, with a fallback last. `body` uses JSONPath selectors
(`{ "$.action": { equals: "opened" } }`) and fits JSON request bodies. Selectors must be
plain paths (`$.a.b`, `$.items[0]`, `$['k']`, `[*]`); filter and script expressions
(`[?(...)]`, `(...)`) are rejected at compile time; the daemon evaluates plain paths only.

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
mocksys examples stripe/createcharge                              # list, [x]=in play
mocksys use stripe/createcharge --op createcharge --example card_declined --only
```

`--only` clears the operation's other selections first (the happy↔error swap).

### Response templating (`{{request.*}}`)

A static response body or header may splice values from the **incoming request** at serve
time — no injection, no state. Tokens:

```
{{request.query.<name>}}    {{request.headers.<name>}}    {{request.body.<jsonpath>}}
{{request.path}}            {{request.method}}
```

Each compiles to a `copy` behavior. Example — a redirect that echoes `state`
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

## Genes

A gene is a parameterized fragment of a contract: a proven pattern such as a REST
resource or an OAuth handshake. List genes under `genes:`, each as a one-key map from the
gene's name to its params. Param names are the gene's option names, in kebab-case. Values
pass through as written, so a nested option such as `rate-limit` is a YAML map. Each param
has a shape (`string`, `number`, `map` or `list`), and a value of another shape is an
error. A param set to `null` counts as unset, so a required param cannot be `null`.

```yaml
seed:
  users:  [ { id: u1, status: ACTIVE, profile: { login: alice@example.com } } ]
  groups: [ { id: g1, profile: { name: Admins } } ]
genes:
  - crud-collection: { coll: users, base: /api/v1/users, seed-name: users, id-prefix: u,
                       rate-limit: { max: 100, window: 60 } }
  - crud-collection: { coll: groups, base: /api/v1/groups, seed-name: groups }
  - membership: { users-coll: users, groups-coll: groups }
operations: []                   # optional; hand-written operations go here
```

When the contract compiles, each gene expands into operations, collections and seed:

- Gene operations come **after** the hand-written `operations`. The first matching
  operation wins, so a hand-written operation with the same method and path overrides
  the gene's.
- Gene collections and seed arrays merge into `state.collections` and `seed`. A
  collection or seed array the contract declares itself wins over the gene's.
- `contract.yaml` keeps the `genes:` form. `imposter.json` holds the expansion. `examples`,
  `inspect` and `validate` all show the expanded operations, and `examples` marks each
  gene operation with its gene.

`mocksys genes` lists every gene with its params and their shapes, and marks the required
ones with `*`.
Today's genes are `crud-collection`, `oidc-handshake`, `membership` and
`oauth2-provider`, described in [twins.md](twins.md#genes). Rate limiting, the Okta error
envelope, the virtual clock and JWT signing are not standalone genes. `mocksys genes` says
where each one lives.

The editing commands (`use`, `add`, `parameterize`) act on hand-written operations only.
`use` or `parameterize` on a gene operation fails with a message naming the gene. To
change a gene operation, change the gene's params, or write an operation with the same
request so that it is served first. `add` never reuses a gene operation's id. A `fault`
applies to every static operation, gene operations included, as it has no per-operation
target.

[`examples/directory.yaml`](../examples/directory.yaml) builds a users, groups and
memberships twin from genes alone.

## gRPC (unary)

A contract with `protocol: grpc` serves the unary RPCs of a `.proto` file. Each
operation names an `rpc` in place of a method and path. Everything else works as it
does for HTTP: request matching, static responses and examples, templating, faults,
effects and state.

```yaml
protocol: grpc
proto: ./todo.proto              # relative to the scenario directory
operations:
  - id: get
    request: { rpc: todo.v1.Todos/GetTodo }       # pkg.Service/Method
    effect:
      bind: { id: { body: id } }
      get:  { collection: todos, key: "{id}", miss: { status: 404, json: { message: "no todo {id}" } } }
```

[`examples/grpc-todos`](../examples/grpc-todos) is a complete stateful example with a
grpc-js client.

### A call is a request

The daemon turns each gRPC call into the request an HTTP stub matches on:

| Request field | From the call |
|---|---|
| `method` | `POST` |
| `path` | `/pkg.Service/Method`, so `rpc: pkg.Service/Method` lowers to that method and path |
| `headers` | the call's metadata. A binary (`-bin`) value is base64. |
| `body` | the decoded request message as JSON |
| `query` | empty |

So `request.headers` matches metadata and `request.body` matches message fields by
JSONPath (`{ "$.id": "t1" }`). In an effect, `bind: { x: { body: field } }` reads a
message field and `bodyJson: true` reads the whole message. `requests` and `assert` show a
call as `POST /pkg.Service/Method`.

### Messages as JSON

The proto is read with these options:

- Field names stay as written in the `.proto` (`display_name` stays `display_name`).
- `int64` and other 64-bit integers are strings, so no precision is lost.
- `bytes` are base64 strings.
- Enums are their value names (`"SECRET"`).

A request body holds only the fields the message carries. An unset field is absent, not
its default, so an `update` with `mergeVar` over the request changes the fields the
caller sent and keeps the rest, and `requests` shows what the caller sent.

proto3 cannot tell a scalar set to its default (`""`, `0`, `false`, the first enum
value) from an unset one. Most clients do not send a default scalar at all; grpc-js
sends one the caller set explicitly. So a default-valued scalar may arrive absent:

- `present: true` holds when the field arrived. `present: false` holds when it is unset
  or the client left out its default.
- A missing field reads as `""`, so `equals: ""` matches an unset string. `equals: 0` or
  `equals: false` does not match an absent number or bool. To branch on a default,
  match the non-default values and let the default fall through to the next operation.
- A field marked `optional`, a `oneof` member, and a message field have presence, so
  they arrive whenever the caller set them, even to a default.

### Replies and status codes

A response with a 2xx status is the reply:

- A JSON object body is encoded as the reply message. The client fills the fields the
  body leaves out with their defaults.
- A JSON array body fills the reply message's one repeated field. This is how a `list`
  effect answers `ListTodosResponse { repeated Todo todos = 1; }`. A `map` field does not
  count. A reply type with no repeated field, or several, cannot take an array.
- Any other body (text, or an array with no field to fill) is `INTERNAL`.

The body is checked against the reply type before it is sent, not coerced:

- A field is named as in the `.proto` (`display_name`) or by its proto3 JSON name
  (`displayName`).
- A value takes its proto3 JSON form. A `string` is a string and a `bool` is `true` or
  `false`. A 32-bit integer is a number or a decimal string, and a 64-bit one is the
  same. A `float` or `double` is a number, a numeric string, `"NaN"` or `"Infinity"`.
  `bytes` is base64. An enum is a value name or a number. A message is an object, and a
  `google.protobuf.Timestamp` may also be an RFC 3339 string
  (`"2026-10-21T10:00:00Z"`). A repeated field is an array, and a `map` is an object.
  `null` leaves the field unset.
- A value of the wrong type fails the call with `INTERNAL`. The details name each field
  and the problem, for example `done: expected a bool, got the string "yes"`, and the same
  line goes to the daemon log (`$MOCKSYS_HOME/daemon.log`).
- A key that names no field is dropped, and the daemon log names it once per method and
  set of keys. A record in a collection often carries fields the reply type leaves out,
  so this is not an error.

Any other status is a gRPC error. A JSON body of `{ grpcCode, message }` picks the code
directly; `grpcCode` is a code name (`NOT_FOUND`) or number (`5`). Otherwise the HTTP
status maps through this table. It inverts `google.rpc.Code`'s HTTP mapping where that
mapping gives a status one code. For a status that mapping does not name, the table
picks the code with the same meaning.

| HTTP | gRPC | Why |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | google.rpc.Code |
| 401 | `UNAUTHENTICATED` | google.rpc.Code |
| 403 | `PERMISSION_DENIED` | google.rpc.Code |
| 404 | `NOT_FOUND` | google.rpc.Code |
| 408 | `DEADLINE_EXCEEDED` | a request timeout, as 504 |
| 409 | `ALREADY_EXISTS` | google.rpc.Code |
| 412 | `FAILED_PRECONDITION` | a precondition failed |
| 422 | `INVALID_ARGUMENT` | the request is well-formed but its content is wrong, as 400 |
| 429 | `RESOURCE_EXHAUSTED` | google.rpc.Code |
| 499 | `CANCELLED` | google.rpc.Code |
| 500 | `INTERNAL` | google.rpc.Code |
| 501 | `UNIMPLEMENTED` | google.rpc.Code |
| 502 | `UNAVAILABLE` | the upstream cannot be reached, as 503 |
| 503 | `UNAVAILABLE` | google.rpc.Code |
| 504 | `DEADLINE_EXCEEDED` | google.rpc.Code |
| any other | `UNKNOWN` | |

google.rpc.Code also maps 400 to `FAILED_PRECONDITION` and `OUT_OF_RANGE`, 409 to
`ABORTED`, and 500 to `UNKNOWN` and `DATA_LOSS`. To answer one of those, give a
`grpcCode`.

The error's message is the body's `message` when it is a string, else its `error` when
that is a string (an OAuth error body), else the body when it is text and not JSON, else
`HTTP <status>`. A JSON body is never sent as the message. A `grpcCode` that is not a valid code other than `OK` is ignored, and
the table applies.

Response headers become the reply's initial metadata, lower-cased. `Content-Type`,
`Content-Length`, `Connection` and other hop-by-hop headers, and `grpc-*` names, are
dropped. Rate-limit headers from a `limit` effect therefore reach the client as
`x-rate-limit-*` metadata.

### Static responses and examples

Static operations work unchanged: the selected example's body is the reply, or the
error, by the rules above. An error variant is an example with a non-2xx status, and
`use --only` swaps to it.

```yaml
  - id: delete
    request: { rpc: todo.v1.Todos/DeleteTodo }
    responses:
      - status: 200
        examples: { ok: { select: true, body: {} } }
      - status: 403
        examples: { denied: { select: false, body: { grpcCode: PERMISSION_DENIED, message: "read-only" } } }
```

A `bodySchema` with no selected example is sampled as for HTTP. A transport `fault`
applies too. `latency` and `timeout` delay the reply, `status` maps through the table,
and `drop-connection` and `malformed-json` end the call with `UNAVAILABLE`.

### What is served

The daemon registers every unary method in the proto. A unary call that matches no
stub gets an empty reply, every field at its default, as an unmatched HTTP request gets
an empty 200. A streaming method, or a method the proto does not declare, answers
`UNIMPLEMENTED`.

Out of scope: streaming RPCs, server reflection, recording gRPC traffic (`record`,
`freeze`), TLS, `proxy` responses, and `conform`, which replays HTTP. A grpc mock
listens on `127.0.0.1` and `::1` without TLS, so a client connects with insecure
credentials.

### The proto file

`proto` is a path relative to the scenario directory, usually a file next to
`contract.yaml`. An `import` resolves against the directory of that file, as
`protoc -I <that directory>` resolves it, whichever file holds the import. Google's
well-known types (`google/protobuf/empty.proto`, `timestamp.proto` and the rest) are
built in.

`compile` (and so `run`) reads the proto and embeds it in `imposter.json` as a
descriptor, so the daemon never reads the scenario's files. A proto that does not parse
stops the compile with the file and line. `pack` bundles the scenario directory, so a
proto inside it travels with the contract. `pack` refuses a proto that reads a file
outside the scenario directory and names the file to copy in.

`run`, `env` and `up` give a grpc mock's address as `localhost:PORT`, the target form
gRPC clients take. `clock` reaches a grpc twin's virtual clock: every grpc mock also
serves the `/_mocksys/clock` control method, with JSON messages.

## Transport faults

A `fault` overlay injects failure on top of every static operation's normal response.
Operations with an `effect` are not affected. It lives on the contract, so it survives
recompiles, and (except connection/protocol faults) coexists with templating.

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

- **Errors** (block compile): an unknown gene, an unknown, missing or misshapen gene
  param (a required param set to `null` is missing), or a `genes:` entry that is not a
  one-key map; two operations with one `id`, whether authored or from genes; a collection
  that two genes declare; no operations; a request missing
  `method` or an absolute `path`; an `effect` with no `respond` and no collection verb; a response `status` that
  isn't a number; an operation with nothing to serve (no selected example, no
  `bodySchema`, no effect); an operation that fails to compile (for example a JSONPath
  selector that isn't a plain path, or a non-numeric status, `ttl` or `window`).
- **gRPC errors**: an unknown `protocol`; a grpc contract with no `proto`; a grpc
  operation that names `method` or `path`, or whose `rpc` is missing or not of the form
  `pkg.Service/Method`; an `rpc` on an http contract; a proto that does not load (a
  missing file or import, or a syntax error, with its line); an `rpc` the proto does not
  declare; a streaming `rpc`.
- **Warnings** (advisory): a selected example whose body diverges from its response
  `bodySchema` (lightweight, ajv-free: top-level type, required object props, one-level
  recurse).

## Authoring entry points

| You have… | Use |
|---|---|
| API docs / source in your head | `mocksys new <service/name>`, then edit; or `mocksys add ...` to append operations |
| an OpenAPI/Swagger spec | `mocksys import openapi <spec> --service <name>` |
| a whole service to clone | `mocksys kit okta` / `kit oauth2` ([twins.md](twins.md)) |
| a service built from known patterns | list `genes:` in a contract (see [Genes](#genes)) |
| live credentials | `mocksys record` → exercise the proxy → `mocksys freeze` (optionally `--draft` then `promote`) |

The `imposter.json` is always derivable — never hand-edit it; edit `contract.yaml` and let
`run`/`compile` rebuild.

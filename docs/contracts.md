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
tls:      true                   # optional: serve HTTPS or gRPC over TLS (see TLS)
seed:     {}                     # named arrays for effects to read (see effects.md)
state:    {}                     # collections, clock, randomSeed (see effects.md)
modules:  [./verbs.js]           # optional: JS files in the scenario directory whose verbs effects can `call` (see effects.md)
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
time — no effect, no state. Tokens:

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

`rate-limit` also takes `key-source` (what to count requests by, default the bearer token),
`miss` (the `429` response) and `headers` (the rate-limit header names); see
[`examples/llm-rate-limits.yaml`](../examples/llm-rate-limits.yaml).

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

## gRPC

A contract with `protocol: grpc` serves the RPCs of a `.proto` file, unary and
streaming. Each
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
dropped. A header whose name ends in `-bin` carries base64, which the client receives as
bytes. A header grpc-js refuses as metadata (a name outside `0-9 a-z _ . -`, or a value
that is not printable ASCII) is dropped, and the daemon log names it. Rate-limit headers from a `limit` effect therefore reach the client as
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

### Streaming

A streaming rpc maps onto the same request and outcome, so its operations are written as
for unary ones.

| Kind | Request | Reply |
|---|---|---|
| Server streaming | the one message, as for unary | a 2xx JSON array is the stream's messages, in order; an object is one message; a blank body is none |
| Client streaming | `body` is the JSON array of every message the client sent, once it half-closes | as for unary |
| Bidirectional | each message the client sends is a request of its own, in order | each request's reply is zero or more messages, as for a server stream |

Each message of a stream is checked against the reply type, as a unary reply is, and a
problem names the message (`[1].version: expected an integer`). A reply with a problem
ends the stream with `INTERNAL`, and an error status ends it with that error. An error
body's `messages` array is sent before the error, so a stream can fail partway:
`{ grpcCode: NOT_FOUND, message: gone, messages: [{ id: t1 }] }` sends one message, then
`NOT_FOUND`. A bidirectional stream ends when the client half-closes and every reply is
sent, or at the first error, after the replies to the messages before it; the first
reply's headers become the stream's metadata, and `requests` shows each inbound message
as its own call.

A collection's `list` returns an array, so it streams one message per record. A client
stream reads its batch with `bodyJson: true`:

```yaml
  - id: watch                                       # rpc WatchTodos (ListTodosRequest) returns (stream Todo)
    request: { rpc: todo.v1.Todos/WatchTodos }
    effect:
      bind: { q: { body: filter } }
      list: { collection: todos, filter: "{q}" }
  - id: preview                                     # rpc PreviewImport (stream CreateTodoRequest) returns (ImportPreview)
    request: { rpc: todo.v1.Todos/PreviewImport }
    effect:
      bind:    { batch: { bodyJson: true } }
      respond: { json: { count: "{batch.length}", first_title: "{batch.0.title}" } }
```

A client stream's `bodyJson` is that array, not one message, so a collection verb sees
the batch as one value. `create` with `merge: [batch]`, or `update` with
`mergeVar: batch`, would store the array as one record, and `validate` warns of it.
Effects run once per call, not once per message: a client stream answers from the whole
batch, as `preview` does.

### What is served

The daemon registers every method in the proto. A unary or client-streaming call that
matches no stub gets an empty reply, every field at its default, as an unmatched HTTP
request gets an empty 200; a server stream, or a bidirectional stream's turn, sends no
messages. A method the proto does not declare answers `UNIMPLEMENTED`.

Every grpc mock serves server reflection, `grpc.reflection.v1` and `v1alpha`, so a
client that lists and describes services, such as `grpcurl` or Postman, calls the mock
without the proto. The files it returns are rebuilt from the descriptor, one per
package, so their names need not match the `.proto` files on disk.
[`examples/grpc-todos/reflect.js`](../examples/grpc-todos/reflect.js) does the same with
grpc-js:

```bash
grpcurl -plaintext localhost:9150 list                         # todo.v1.Todos
grpcurl -plaintext -d '{"id":"t1"}' localhost:9150 todo.v1.Todos/GetTodo
node examples/grpc-todos/reflect.js localhost:9150 todo.v1.Todos  # one line per rpc
```

### Recording

`record --proto` seeds a grpc contract from a live upstream, and `freeze` lifts it as
it does for HTTP:

```bash
mocksys record acme/todos --target localhost:50051 --proto ./todo.proto --port 9160
node examples/grpc-todos/client.js localhost:9160 GetTodo '{"id":"t1"}'   # forwarded upstream
mocksys freeze acme/todos                                                  # protocol: grpc, rpc operations
```

`record` copies the proto, and the files it imports, into the scenario directory, so the
frozen contract compiles from what the scenario holds. The imports must sit beside the
proto or in a directory below it; `record` refuses a proto that imports a file from
elsewhere. Google's well-known types are built in and need no copy. The proxy forwards each call
with grpc-js, the metadata as it came, and keeps the first answer for each rpc, as the
HTTP proxy keeps the first for each method and path. A reply is a 200 body, and a
server stream's messages are a 200 array. An error is the HTTP status that
`google.rpc.Code` maps its code to, with a `{ grpcCode, message }` body, so it replays
with the same code. A server stream that fails partway keeps the messages it sent as
the body's `messages`, and replays them before the error; a stream still open when the
60-second default deadline passes is recorded so, with `DEADLINE_EXCEEDED`. A client
stream is recorded with its reply. A bidirectional stream is not recorded: the proxy
answers it `UNIMPLEMENTED`. An upstream that does not connect fails the call
`UNAVAILABLE`, and nothing is saved. The upstream call carries the client's deadline,
or 60 seconds when the client sets none; a call the client cancels, or whose deadline
passes, is cancelled upstream and nothing is saved. `--target` is `host:port` without
TLS; an `https://` target is refused, since `record` does not yet reach an upstream
over TLS.

Out of scope: `proxy` responses in a contract, and `conform`, which replays HTTP. A
grpc mock listens on `127.0.0.1` and `::1`, without TLS unless the contract sets `tls`
([TLS](#tls)), so a client connects with insecure credentials.

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

## Streams: server-sent events and WebSockets

An operation answers with a stream when its effect ends in `sse` or `websocket` (the
fields are in [effects.md](effects.md#streams-sse-websocket-and-publish)). Because the
stream is an effect, the verbs before it apply: `bind` names the channel, and a
`lookup` or `find` miss answers with its own status, such as 401 or 404, instead of
opening the stream. Other operations push to an open stream with `publish`. Streams need
`protocol: http`; a `websocket` operation's method is `GET`, and `sse` cannot answer a
`MESSAGE`.

```yaml
operations:
  - id: job-events                            # GET /jobs/j1/events -> text/event-stream
    request: { method: GET, path: "/jobs/{id}/events" }
    effect:
      bind: { id: { pathRegex: "/jobs/([^/]+)/" } }
      find: { collection: jobs, key: "{id}", as: job }     # 404 for an unknown job
      sse:
        retry: 3000
        events:                               # scripted, sent first, in order
          - { event: status, id: "0", data: { id: "{id}", state: "{job.state}" } }
        channel: "job-{id}"                   # then whatever is published here
  - id: job-progress                          # POST /jobs/j1/progress {"pct":40}
    request: { method: POST, path: "/jobs/{id}/progress" }
    effect:
      bind: { id: { pathRegex: "/jobs/([^/]+)/" }, pct: { body: pct } }
      publish: { channel: "job-{id}", event: progress, data: { pct: "{pct}" } }
      respond: { status: 202 }
```

**SSE.** The stream answers `200 text/event-stream`, then sends `retry:` when set, the
scripted events, and every event published on its channel. A stream with no `channel`
ends after its last scripted event; one with a channel stays open until the client
leaves. A `: keep-alive` comment goes out every 15 seconds (`keepAlive`, in ms; `0`
turns it off). A client that stops reading is disconnected once 1 MiB is waiting for it,
so a stalled reader does not hold every publish in memory. A client that reconnects with `Last-Event-ID` resumes the script after
the event with that id. The id is compared with each scripted `id` as the reconnecting
request renders it. An id built from a value that differs between the two requests,
such as a query parameter the client changed, matches nothing, and the whole script is
sent again. When two scripted events share an id, the stream resumes after the first.
Channel events are not replayed.

**WebSockets.** A `websocket` answer accepts the upgrade on the operation's path.
Each message the client sends is then a request through the same contract:

| Request field | Value |
|---|---|
| method | `MESSAGE` |
| path, query, headers | those of the upgrade request |
| body | the message text |

An operation with `request.method: MESSAGE` handles it. Body conditions route the
messages, and every message is recorded, so `assert --saw 'MESSAGE /rooms/*/socket'`
works. The reply becomes outbound messages:

| Reply | Sent to the client |
|---|---|
| status below 400, empty body | nothing |
| status below 400, JSON array body | one message per element (a string as written, anything else as JSON) |
| status below 400, any other body | the body as one message |
| status 400 or above | the socket closes with code 4000 + status, the body as the reason |

Messages are handled one at a time, in the order they arrive. An inbound message over
1 MiB closes the socket with `1009`. A client with 1000 messages already waiting for
their replies is sending faster than the mock answers: its next message closes the
socket with `1008` (policy violation), and the waiting messages are dropped unanswered.
`1008` rather than `4413`, because a `4xxx` code always means an operation's reply.

```yaml
  - id: room-socket
    request: { method: GET, path: "/rooms/{room}/socket" }
    effect:
      bind: { room: { pathRegex: "/rooms/([^/]+)/" } }
      websocket:
        messages: [ { data: { type: welcome, room: "{room}" } } ]
        channel: "room-{room}"
  - id: socket-ping
    request: { method: MESSAGE, path: "/rooms/{room}/socket", body: { "$.type": ping } }
    effect:
      respond: { json: { type: pong } }
```

The upgrade request is itself recorded as a `GET`. An upgrade that reaches any other
answer gets that answer as plain HTTP, and a plain `GET` that reaches a `websocket`
answer gets `426 Upgrade Required`. Only `Upgrade: websocket` is taken over; any other
upgrade, such as curl's `h2c`, stays an ordinary request with its body. A mock with no
`websocket` answer takes over no upgrade at all. On node before 22.21 (or 24.9 on the
24 line), a mock with a `websocket` answer cannot tell the upgrades apart, so another
upgrade reaches the contract without its body. [`examples/chat.yaml`](../examples/chat.yaml) is a
chat room served both ways.

## TLS

`tls: true` serves the mock as HTTPS, or a grpc mock as gRPC over TLS, on the same
port. Use it when the code under test only accepts `https://` URLs or TLS channels.

```yaml
tls: true                                   # a certificate from the mocksys CA
tls: { cert: ./cert.pem, key: ./key.pem }   # or your own, inside the scenario directory
```

With `tls: true`, `run` issues the mock a new certificate for `localhost`,
`127.0.0.1` and `::1` each time it posts the scenario. The certificate comes from one
local CA that mocksys creates in `$MOCKSYS_HOME/tls/` on first use. The client must
trust that CA:

```sh
export NODE_EXTRA_CA_CERTS="$(mocksys tls ca)"   # node: https, fetch, grpc-js createSsl()
curl --cacert "$(mocksys tls ca)" https://localhost:9160/whoami
```

`run` prints the CA's path and these commands. `env` exports `NODE_EXTRA_CA_CERTS`,
and `env --json` adds `caCert`. See [cli.md](cli.md#tls) for other clients and for
trusting the CA system-wide.

What is stored where:

- `imposter.json` records only `tls: true`, or the two paths relative to the scenario
  directory. It never holds a key, so `pack`, `publish` and `push` carry no key
  material for `tls: true`.
- The certificate and key exist only in the post to the daemon and in the daemon's
  memory. The admin API shows `tls: true` and never returns the key.
- A `{cert, key}` pair must be files inside the scenario directory, like `modules`, so
  they travel with the scenario. That shares the key with everyone who gets the
  scenario, so use a key made for tests.

The address of an HTTPS mock is `https://localhost:PORT`. A gRPC mock's address stays
`localhost:PORT`; `run` prints `(gRPC over TLS)`. `conform` and `clock` reach a TLS
twin without extra flags. Recording from an `https://` target works with or without
`tls`, because the recording proxy is the client of that connection.

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
  `method` or an absolute `path`; an `effect` with no `respond` and no terminal verb
  (a collection verb, `sse` or `websocket`); a `store` beside a terminal verb, which would
  skip it; a stream answer in a grpc contract, a `websocket` on a method other than
  `GET`, or an `sse` on a `MESSAGE`; a response `status` that
  isn't a number; an operation with nothing to serve (no selected example, no
  `bodySchema`, no effect); an operation that fails to compile (for example a JSONPath
  selector that isn't a plain path, or a non-numeric status, `ttl` or `window`); a
  module that is outside the scenario directory or missing; a `call` with no `verb`, an
  unknown key, non-object `args`, or a reserved `as`; a `call` in a contract with no
  `modules`. `validate` never runs a module, so whether each verb exists is checked
  when the scenario runs (see effects.md). A `tls` that is not `true`, `false` or
  `{cert, key}`, or a `cert` or `key` file that is missing or outside the scenario
  directory.
- **gRPC errors**: an unknown `protocol`; a grpc contract with no `proto`; a grpc
  operation that names `method` or `path`, or whose `rpc` is missing or not of the form
  `pkg.Service/Method`; an `rpc` on an http contract; a proto that does not load (a
  missing file or import, or a syntax error, with its line); an `rpc` the proto does not
  declare.
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

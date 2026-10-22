# Guide: assemble a digital twin, then ship it as a Docker image

This guide builds a twin from mocksys's primitives, one layer at a time. It then turns
the twin into a reusable kit and packages it as a container image that tests, CI jobs
and other services can run. The [walkthrough](docs/walkthrough.md) tours a finished twin
(Okta). This guide shows how to build your own.

The service is a made-up **helpdesk API**. It is small, but it needs every kind of
primitive:

| Endpoint | Behavior | Built from |
|---|---|---|
| `GET /v2/status` | Health, with a degraded variant | a static operation, named examples, request templating |
| `POST /oauth/token` | Client-credentials token exchange; tokens expire | `bind`, `find`, `mint`, `store` with a TTL, `respond` |
| `GET/POST/DELETE /v2/tickets[/{id}]` | Ticket CRUD with filtering and pagination | the `crud-collection` gene |
| `POST /v2/tickets` | Create a ticket: authenticated, rate-limited, with an SLA due time | a hand-written operation that shadows the gene's: `lookup`, `limit`, a custom verb, `create`, `publish` |
| `GET /v2/events` | A live stream of created tickets | an `sse` stream |

The finished twin is [`examples/kits/helpdesk/`](examples/kits/helpdesk/). The image
files are [`packaging/docker/`](packaging/docker/) and
[`examples/helpdesk/`](examples/helpdesk/).

## Contents

1. [Setup](#setup)
2. [The primitives](#the-primitives)
3. [Step 1: a static operation](#step-1-a-static-operation)
4. [Step 2: state and an auth handshake](#step-2-state-and-an-auth-handshake)
5. [Step 3: a resource from a gene](#step-3-a-resource-from-a-gene)
6. [Step 4: shadow a gene operation with your own rules](#step-4-shadow-a-gene-operation-with-your-own-rules)
7. [Step 5: a live event stream](#step-5-a-live-event-stream)
8. [Step 6: time](#step-6-time)
9. [Step 7: check the twin](#step-7-check-the-twin)
10. [Step 8: package the twin as a kit](#step-8-package-the-twin-as-a-kit)
11. [Step 9: package the twin as a Docker image](#step-9-package-the-twin-as-a-docker-image)
12. [Checklist](#checklist)

## Setup

Build mocksys and keep this guide's store inside the repository, away from your shared
`~/.mocksys`:

```sh
npm ci && npm run build
alias mocksys="node $PWD/bin/mocksys"
export MOCKSYS_HOME=$PWD/.mocks
```

A store gets its own daemon. The first command that serves a mock starts it.

## The primitives

A twin is assembled from five layers. Each layer covers what the one below it cannot
express.

| Layer | What it is | Use it for | Reference |
|---|---|---|---|
| **Static operation** | A request matcher and a fixed response, chosen from named **examples**. Responses can copy request values (`{{request.headers.x}}`). A **fault** adds latency, errors or dropped connections. | Fixtures, error variants, health checks | [contracts.md](docs/contracts.md) |
| **Effect** | A declarative map of **verbs** the daemon runs over the scenario's persistent `state`: buckets, collections, a virtual clock. | Anything that remembers: tokens, sessions, resources, rate limits, streams | [effects.md](docs/effects.md) |
| **Gene** | A parameterized bundle of effect operations, such as a whole REST resource. | Proven patterns you would otherwise write by hand | [twins.md](docs/twins.md#genes) |
| **Custom verb** | A synchronous JavaScript function, shipped beside the contract, that an effect runs with `call`. | The one rule the verbs cannot express | [effects.md](docs/effects.md#custom-verbs-call) |
| **Kit** | A contract template with typed flags, which generates the twin in one command. | Reuse, parameters, and distribution | [twins.md](docs/twins.md#writing-a-kit) |

The rules that hold all of this together:

- **`contract.yaml` is the source of truth.** mocksys compiles it to `imposter.json` on
  every `run`. Never edit the compiled file.
- **Hand-written operations are served before gene operations, and the first match
  wins.** That is how you override part of a gene.
- **An effect runs in a fixed read-then-write order**, whatever order you write its keys:
  `bind` → `limit` → `consume`/`lookup` → `resolve` → `find` → `call` → `mint` → `sign` →
  one terminal (a collection verb, a stream, or `store` + `respond`) → `publish`. A read
  that misses ends the request with its `miss` response, so nothing after it runs.
- **State is fresh on every `run`.** Each run reseeds the collections, restarts the
  clock and restarts the seeded id stream, so the same requests mint the same ids.
- `{name}` (single brace) is effect-time interpolation of a bound variable.
  `{{request.x}}` (double brace) is static response templating. They are different
  mechanisms.

## Step 1: a static operation

Scaffold the scenario:

```sh
mocksys new helpdesk/helpdesk
```

Replace `.mocks/helpdesk/helpdesk/contract.yaml` with a pinned port and one static
operation:

```yaml
service: helpdesk
scenario: helpdesk/helpdesk
origin: authored
port: 9400
operations:
  - id: status
    summary: service health
    request: { method: GET, path: /v2/status }
    responses:
      - status: 200
        headers: { Content-Type: application/json, X-Request-Id: "{{request.headers.x-request-id}}" }
        examples:
          ok:       { select: true,  body: { status: ok } }
          degraded: { select: false, body: { status: degraded, detail: "ticket search is slow" } }
```

```sh
mocksys run helpdesk/helpdesk
curl -i localhost:9400/v2/status -H 'x-request-id: abc'    # X-Request-Id: abc, {"status": "ok"}
```

Switch variants without editing bodies, and add transport failure:

```sh
mocksys use helpdesk/helpdesk --op status --example degraded --only
mocksys fault helpdesk/helpdesk --latency 1500      # every static operation now waits 1.5 s
mocksys run helpdesk/helpdesk                       # re-post the scenario
mocksys fault helpdesk/helpdesk --clear
mocksys use helpdesk/helpdesk --op status --example ok --only
```

A fault applies only to static operations. The stateful operations you add next are not
slowed down. Commands like `use` and `fault` rewrite `contract.yaml` in plain block
style, so keep any explanation you want to preserve in `summary` fields, not in YAML
comments.

## Step 2: state and an auth handshake

A token endpoint has to remember what it issued. Add the clients as a **collection** and
a token operation as an **effect**. Add these top-level keys:

```yaml
state:
  clock: { start: "2026-01-05T09:00:00Z" }
  collections:
    clients: { idField: id, seed: clients }
seed:
  clients:
    - { id: ci_acme, secret: s3cret, name: Acme Corp }
    - { id: ci_globex, secret: hunter2, name: Globex }
```

And this operation under `operations:`:

```yaml
  - id: token
    summary: client-credentials token exchange
    request: { method: POST, path: /oauth/token }
    effect:
      bind:    { cid: { body: client_id }, secret: { body: client_secret } }
      find:
        collection: clients
        key: "{cid}"
        filter: 'secret eq "{secret}"'
        as: client
        miss: { status: 401, json: { error: invalid_client } }
      mint:    { token: { prefix: "hd_" } }
      store:   { bucket: tokens, key: "{token}", value: "{cid}", ttl: 3600 }
      respond: { json: { access_token: "{token}", token_type: Bearer, expires_in: 3600 } }
```

How each verb works here:

- `bind` reads `client_id` and `client_secret` from a form or JSON body.
- `find` looks the client up by id. The SCIM `filter` also requires the secret to match,
  so a wrong secret is a miss, and the request ends with a `401`.
- `mint` makes a token, `hd_` plus 24 hex characters, from the seeded id stream.
- `store` writes the token to a `tokens` **bucket**, mapped to the client id. The
  `ttl` makes the token expire 3600 seconds later on the virtual clock.

```sh
mocksys run helpdesk/helpdesk
curl -s -X POST localhost:9400/oauth/token -d 'client_id=ci_acme&client_secret=s3cret'
# {"access_token":"hd_009182217a95b00c3c1f8e5d","token_type":"Bearer","expires_in":3600}
curl -s -X POST localhost:9400/oauth/token -d 'client_id=ci_acme&client_secret=nope'   # 401
```

Run the scenario again and the first token is the same `hd_0091…` value. That
repeatability is what makes a twin usable in tests.

> A collection holds objects with CRUD, filtering and pagination. A bucket is a flat
> `string → string` map with optional TTLs, for codes, tokens and sessions.

## Step 3: a resource from a gene

A REST resource with create, list, SCIM filtering, cursor pagination, get, update and
delete takes five operations to write by hand. The `crud-collection` gene writes them
for you. Add the seed rows and a `genes:` list:

```yaml
seed:
  # ...clients as before...
  tickets:
    - { id: tkt_001, subject: "Printer on fire", priority: urgent, status: open, requester: ci_acme }
    - { id: tkt_002, subject: "Password reset", priority: low, status: closed, requester: ci_globex }
genes:
  - crud-collection:
      coll: tickets
      base: /v2/tickets
      seed-name: tickets
      id-prefix: tkt_
      create-defaults: { status: open }
      not-found: { status: 404, json: { error: { code: not_found, message: "no such ticket" } } }
      invalid-filter: { status: 400, json: { error: { code: bad_filter, message: "{filterError}" } } }
```

The gene's default error bodies are Okta-shaped, and `not-found` and `invalid-filter`
replace them with this API's envelope. `mocksys genes` lists every gene and its
parameters.

```sh
mocksys run helpdesk/helpdesk
curl -s -G localhost:9400/v2/tickets --data-urlencode 'filter=status eq "open"'   # [tkt_001]
curl -s localhost:9400/v2/tickets/nope                                           # 404, your envelope
curl -s -G localhost:9400/v2/tickets --data-urlencode 'filter=status zz 1'
# {"error":{"code":"bad_filter","message":"Invalid filter: unknown operator zz"}}
mocksys examples helpdesk/helpdesk      # gene operations are marked [gene crud-collection]
```

`contract.yaml` keeps the short `genes:` form, and `imposter.json` holds the expanded
operations.

## Step 4: shadow a gene operation with your own rules

The gene's `POST /v2/tickets` accepts anyone. The real API requires a token, limits each
client's rate, and stamps every ticket with an SLA due time that depends on its priority.
Write your own `POST /v2/tickets`. Hand-written operations are served first, so yours
replaces the gene's, and the gene still serves the other endpoints.

The SLA rule needs a table lookup and date arithmetic, which the verbs cannot express. It
gets a **custom verb**. Save this as `.mocks/helpdesk/helpdesk/verbs.js`, next to the
contract:

```js
// Custom verbs for the helpdesk twin. A verb is synchronous and reads time only
// from ctx.now (the virtual clock), so the twin stays deterministic.
const SLA_HOURS = { urgent: 4, high: 24, normal: 72, low: 168 };

module.exports = {
  verbs: {
    // The SLA a new ticket gets from its priority. An unknown priority is the API's
    // own 422, so it ends the request through ctx.respond rather than throwing.
    slaDue({ args, now, respond }) {
      const priority = args.priority || "normal";
      const hours = SLA_HOURS[priority];
      if (hours === undefined) {
        return respond({ status: 422, json: { error: { code: "invalid_priority", message: `unknown priority ${priority}` } } });
      }
      return { priority, dueAt: new Date((now + hours * 3600) * 1000).toISOString() };
    },
  },
};
```

Declare the module at the top level of the contract, and add the operation:

```yaml
modules: [./verbs.js]
```

```yaml
  - id: create-ticket
    summary: open a ticket (authenticated, rate-limited, SLA-stamped, broadcast)
    request: { method: POST, path: /v2/tickets }
    effect:
      bind:   { auth: { header: authorization, bearer: true }, incoming: { bodyJson: true } }
      limit:
        bucket: rl
        key: "{auth}"
        max: 5
        window: 60
        miss: { status: 429, json: { error: { code: rate_limited, message: "slow down" } } }
      lookup:
        bucket: tokens
        key: "{auth}"
        as: cid
        miss: { status: 401, json: { error: { code: unauthorized, message: "bad or expired token" } } }
      call:   { verb: slaDue, args: { priority: "{incoming.priority}" }, as: sla }
      mint:   { id: { prefix: "tkt_" } }
      create:
        collection: tickets
        merge: [incoming, sla]
        body: { id: "{id}", status: open, requester: "{cid}" }
        as: ticket
      publish: { channel: tickets, event: ticket.created, id: "{id}", dataVar: ticket }
```

Read it in execution order:

1. `bind` takes the bearer token and the whole JSON body.
2. `limit` allows five requests per token per 60 virtual seconds, then answers `429` with
   `X-Rate-Limit-*` headers. It runs before `lookup`, so it counts a request whether or
   not the token is valid.
3. `lookup` resolves the token to the client id that `store` wrote in step 2. A missing
   or expired token is a `401`.
4. `call` runs `slaDue`. It either returns `{priority, dueAt}` into `sla`, or ends the
   request with the `422`. Because `call` runs before `mint` and `create`, a rejected
   request writes nothing.
5. `create` merges the request body and the SLA under the template, so the template's
   `id`, `status` and `requester` win over anything the client sends.
6. `publish` sends the stored row to the `tickets` channel. It runs only after a
   successful answer, in the next step's stream.

```sh
mocksys run helpdesk/helpdesk
TOK=$(curl -s -X POST localhost:9400/oauth/token -d 'client_id=ci_acme&client_secret=s3cret' | node -pe 'JSON.parse(require("fs").readFileSync(0)).access_token')
curl -s -X POST localhost:9400/v2/tickets -H "authorization: Bearer $TOK" -d '{"subject":"VPN down","priority":"high"}'
# {"subject":"VPN down","priority":"high","dueAt":"2026-01-06T09:00:00.000Z","id":"tkt_…","status":"open","requester":"ci_acme"}
curl -s -X POST localhost:9400/v2/tickets -H "authorization: Bearer $TOK" -d '{"priority":"meh"}'   # 422
curl -s -X POST localhost:9400/v2/tickets -d '{}'                                                   # 401
```

A module runs with full Node access inside the daemon, and it is the only code a
scenario can bring. Keep verbs synchronous, and read time from `ctx.now`, never from
`Date.now()`.

## Step 5: a live event stream

A client that watches for new tickets subscribes to the channel that `create-ticket`
publishes to:

```yaml
  - id: events
    summary: live ticket events (server-sent events)
    request: { method: GET, path: /v2/events }
    effect:
      sse: { channel: tickets, keepAlive: 15000 }
```

```sh
mocksys run helpdesk/helpdesk
curl -N localhost:9400/v2/events &          # leave it open, then create a ticket as in step 4
# event: ticket.created
# id: tkt_…
# data: {"subject":"VPN down",...}
```

Change `sse` to `websocket` for a WebSocket that receives the same events. See [effects.md](docs/effects.md#streams-sse-websocket-and-publish).

## Step 6: time

`state.clock` fixes the twin's "now" at `2026-01-05T09:00:00Z`. The time moves only when
you move it, so expiry and rate-limit windows are reproducible:

```sh
mocksys clock helpdesk/helpdesk                 # the current virtual time
mocksys clock helpdesk/helpdesk advance 61s     # the rate-limit window resets
mocksys clock helpdesk/helpdesk advance 1h      # tokens issued before this are now expired: 401
```

A clock-using twin also answers `POST /_mocksys/clock` on its own port. The body is
`{"advance": SECONDS}` or `{"set": EPOCH}`, and the answer is the current time. Tests that
reach the twin only over HTTP, as in a container, use this endpoint.

## Step 7: check the twin

```sh
mocksys validate helpdesk/helpdesk                         # structure, effects, gene params
mocksys inspect helpdesk/helpdesk                          # an agent-readable summary
mocksys requests helpdesk/helpdesk                         # what the twin received
mocksys assert helpdesk/helpdesk --saw 'POST /v2/tickets'  # exit 1 if it never happened
```

**conform** replays a request corpus and checks each status.
[`examples/helpdesk/corpus.json`](examples/helpdesk/corpus.json) covers the happy paths
and the misses:

```sh
mocksys conform helpdesk/helpdesk --corpus examples/helpdesk/corpus.json
#   ✓ conformant — 9 request(s), 0 diffs
```

When you have access to the real service, add `--against https://helpdesk.example.com`.
conform then sends the same corpus to both and diffs the canonicalized bodies. Mask
server-generated fields with `--ignore id,dueAt`. Repeat until the diff is empty: that
is how a twin becomes faithful. Each `conform` reposts the twin, so every run starts
from the seed.

## Step 8: package the twin as a kit

Your scenario directory now holds a working twin, but its numbers are fixed. A **kit**
makes the twin a template with typed flags, and generates it into any store with one
command. A kit that ships a module is a directory:

```
examples/kits/helpdesk/
  kit.yaml     # name, doc, flags, and the contract under `contract:`
  verbs.js     # copied into each generated scenario
```

[`kit.yaml`](examples/kits/helpdesk/kit.yaml) is the contract from steps 1–6 under a
`contract:` key, with the fixed numbers replaced by flags:

```yaml
name: helpdesk
doc: a ticketing API with client-credentials auth, SLAs and live ticket events
flags:
  token-ttl: 3600                       # access-token lifetime, virtual seconds
  rate-limit: 5                         # ticket creations per token per window
  rate-window: 60                       # rate-limit window, virtual seconds
  clock-start: "2026-01-05T09:00:00Z"   # where the virtual clock starts
contract:
  modules: [./verbs.js]
  state:
    clock: { start: "{clock-start}" }
  # ...
        store:   { bucket: tokens, key: "{token}", value: "{cid}", ttl: "{token-ttl}" }
  # ...
        limit:   { bucket: rl, key: "{auth}", max: "{rate-limit}", window: "{rate-window}", ... }
```

How a kit substitutes flags:

- A string that is exactly `"{flag}"` becomes the flag's value, with the flag's type.
  `ttl: "{token-ttl}"` becomes the number `3600`.
- A flag inside a longer string is left as written. So is a `{name}` that is not a flag,
  such as the effect variable `{token}`. Do not give a flag the same name as an effect
  variable.
- A flag's type comes from its default (`3600` makes `number`) or from an explicit
  `{default, type}`. `kit` rejects a value of the wrong type and any flag the kit does
  not declare.

Install the kit and generate twins from it:

```sh
mkdir -p .mocks/kits && cp -R examples/kits/helpdesk .mocks/kits/
mocksys kits                                          # lists helpdesk, its flags, "ships modules"
mocksys kit helpdesk --rate-limit 2 --token-ttl 60 --port 9400
mocksys run helpdesk/helpdesk
```

`kit` overwrites `contract.yaml` on every run, but it does not overwrite a module that
you edited by hand: it stops instead, unless you pass `--force`. Use
`--scenario helpdesk/strict` to generate a second variant beside the first. To share a
kit, copy its directory. To share a generated scenario instead, use `mocksys pack` or
the git-backed store (`publish`, `push`).

## Step 9: package the twin as a Docker image

A container runs the twin wherever your tests run: in CI, in a compose stack, or next to
a service in a shared environment. You need no Node or mocksys on the host.

### Two constraints the image must handle

1. **Mocks listen on loopback only.** The daemon binds each mock to `127.0.0.1` and
   `::1`, so other machines cannot reach it. Inside a container, that excludes the host
   and every other container. The image therefore publishes each mock with `socat`, on
   `0.0.0.0` and a **different** port. A listener on `0.0.0.0` cannot share a port with
   the mock's `127.0.0.1` listener.
2. **`mocksys run` returns at once.** It starts the daemon in the background, posts the
   scenario and exits. A container needs a process in the foreground. The entrypoint
   therefore runs `mocksys daemon` itself, as a child it can wait on, and the container
   lives as long as the daemon does.

### The base image

[`packaging/docker/Dockerfile`](packaging/docker/Dockerfile) builds mocksys from this
checkout in two stages:

- The **build** stage uses `node:22` with a Java 21 runtime copied in, because
  shadow-cljs compiles the ClojureScript on the JVM. It runs `npm ci`, then
  `shadow-cljs release cli`, then drops the dev dependencies.
- The **runtime** stage uses `node:22-bookworm-slim` with `socat` and `tini`. It holds the
  compiled `out/mocksys.js`, the production `node_modules`, and `bin/mocksys` on the
  `PATH`. It sets `MOCKSYS_HOME=/twins` and a fixed `MOCKSYS_ADMIN_PORT=2626`, so a later
  `docker exec … mocksys …` reaches the same daemon. It runs as the unprivileged `node`
  user.

```sh
docker build -f packaging/docker/Dockerfile -t mocksys:local .    # the context is the repo root
```

[`packaging/docker/entrypoint.sh`](packaging/docker/entrypoint.sh) reads two
environment variables:

| Variable | Meaning |
|---|---|
| `MOCKSYS_SERVE` | Required. Space-separated `SCENARIO=PUBLIC_PORT` pairs, such as `helpdesk/helpdesk=8080 okta/okta=8081`. |
| `MOCKSYS_KIT` | Optional. Arguments for `mocksys kit`, run before serving, such as `helpdesk --rate-limit 100 --port 9400`. Use it to change a twin's flags without rebuilding the image. |

It runs these steps:

1. If `MOCKSYS_KIT` is set, it runs `mocksys kit $MOCKSYS_KIT`.
2. It starts `mocksys daemon` in the background and waits until the daemon writes its
   admin token. It deletes any token left by an earlier run first, so a restarted
   container does not read a stale one.
3. For each pair, it runs `mocksys run SCENARIO`, reads the scenario's port from
   `mocksys env --json`, and starts `socat` on `0.0.0.0:PUBLIC_PORT`.
4. It waits on the daemon.

`tini -g` is PID 1. It passes `docker stop` to the whole process group, so the container
stops at once instead of after the 10-second kill timeout.

### The twin image

A twin image starts from the base and adds the twin.
[`examples/helpdesk/Dockerfile`](examples/helpdesk/Dockerfile):

```dockerfile
ARG MOCKSYS_IMAGE=mocksys:local
FROM ${MOCKSYS_IMAGE}

ARG RATE_LIMIT=5
ARG ACCESS_TTL=3600
COPY --chown=node:node examples/kits/helpdesk /twins/kits/helpdesk
RUN mocksys kit helpdesk --rate-limit "$RATE_LIMIT" --token-ttl "$ACCESS_TTL" --port 9400 \
 && mocksys validate helpdesk/helpdesk

ENV MOCKSYS_SERVE="helpdesk/helpdesk=8080"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s \
  CMD node -e "fetch('http://127.0.0.1:8080/v2/status').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))"
```

- The twin is generated and validated **at build time**. A broken contract fails the
  build, not a test run, and every container from the image serves the same twin.
- Kit flags become build arguments (`--build-arg RATE_LIMIT=100`).
- The internal port (9400) is pinned by `kit --port`. The public port (8080) is the one
  you publish.
- The healthcheck requests the twin through the `socat` forward, so it checks the daemon,
  the scenario and the forward together.

```sh
docker build -f examples/helpdesk/Dockerfile -t helpdesk-twin .
docker run -d --name helpdesk -p 8080:8080 helpdesk-twin
docker logs helpdesk
# mocksys 0.0.1 daemon on http://127.0.0.1:2626 (pid 9)
# ▶ running 'helpdesk/helpdesk' at http://localhost:9400
# mocksys-entrypoint: helpdesk/helpdesk on 0.0.0.0:8080 (-> 127.0.0.1:9400)
curl -s localhost:8080/v2/status
```

### Drive the containerized twin

Everything in steps 1–7 still works, over HTTP or through `docker exec`:

```sh
curl -s -X POST localhost:8080/_mocksys/clock -d '{"advance":3601}'   # move time over HTTP
docker exec helpdesk mocksys clock helpdesk/helpdesk                 # or with the CLI
docker exec helpdesk mocksys assert helpdesk/helpdesk --saw 'POST /v2/tickets'
docker exec helpdesk mocksys requests helpdesk/helpdesk
```

The admin API stays on the container's loopback, so `requests`, `assert` and
`conform` run inside the container. `docker restart` reseeds the twin, like a new
`mocksys run`.

Change flags at run time with `MOCKSYS_KIT`, and publish the twin on another port with
`MOCKSYS_SERVE`:

```sh
docker run --rm -p 8081:8081 \
  -e MOCKSYS_KIT="helpdesk --rate-limit 1 --port 9400" \
  -e MOCKSYS_SERVE="helpdesk/helpdesk=8081" helpdesk-twin
```

The healthcheck checks port 8080. A container that publishes on another port therefore
reports `unhealthy` until you override the check with `--health-cmd`.

### Run it beside your app

[`examples/helpdesk/compose.yaml`](examples/helpdesk/compose.yaml) starts the twin and a
consumer that waits for the healthcheck, then reaches the twin by its service name:

```yaml
services:
  helpdesk:
    image: helpdesk-twin
    ports: ["8080:8080"]
  app:
    image: node:22-bookworm-slim
    depends_on:
      helpdesk: { condition: service_healthy }
    environment:
      HELPDESK_URL: http://helpdesk:8080
    command: [node, -e, "..."]    # takes a token and opens a ticket
```

```sh
docker compose -f examples/helpdesk/compose.yaml up --abort-on-container-exit --exit-code-from app
# app-1  | 201 {"subject":"from compose","priority":"urgent","dueAt":"2026-01-05T13:00:00.000Z",...}
```

Point your real service at `http://helpdesk:8080` the same way. `socat` forwards raw TCP,
so the client's `Host` header reaches the twin unchanged, and a twin that builds URLs
from it (such as the Okta twin's OIDC discovery document) advertises
`http://helpdesk:8080`.

### Package other twins the same way

Any scenario fits in this image. These are the two ways to put one in:

```dockerfile
FROM mocksys:local

# a built-in kit, generated at build time
RUN mocksys kit okta --port 9200 --rate-limit 100

# or a scenario directory you authored: contract.yaml and its modules
COPY --chown=node:node .mocks/payments/stripe /twins/payments/stripe

ENV MOCKSYS_SERVE="okta/okta=8080 payments/stripe=8081"
EXPOSE 8080 8081
```

Things to know:

- **Okta keys.** `kit okta` generates a new RSA signing key on each run. If you generate
  the twin at build time, the image keeps one key, and its JWKS stays stable across
  containers. If you set `MOCKSYS_KIT` at run time, each container gets a new key. Pass
  `--signing-key` to pin it.
- **gRPC and TLS mocks** work through the TCP forward unchanged. A `tls: true` mock's
  certificate names `localhost`, so a client in another container that dials
  `helpdesk:8080` fails the host-name check. Plain HTTP is simpler inside a compose
  network.
- **Where the image runs.** The forward makes a mock reachable on every network the
  container joins, without the loopback-only protection a local mock has. A twin has no
  real security, and a module runs code. Publish twin images on test networks only.
  The admin API is not forwarded and stays on the container's loopback.
- **A smaller image.** `scripts/build-binary.sh bun-linux-x64` compiles mocksys to a
  single binary that needs no Node or `node_modules`. Copying that binary into a slim
  base gives a smaller runtime stage. The entrypoint reads the scenario's port with
  `node`, so you would need to change that step.

## Checklist

1. List the endpoints that your code calls. Twin those, not the whole service.
2. Use a static operation and named examples for each endpoint that never changes.
3. Use an effect for each endpoint that remembers: buckets for tokens and codes,
   collections for resources.
4. Use a gene for every pattern a gene already covers (`mocksys genes`). Shadow its
   operations where your service's rules differ.
5. Write a custom verb only for logic the verbs cannot express. Keep it synchronous and
   use `ctx.now` for time.
6. Put time under the virtual clock: TTLs, rate-limit windows, `now` binds.
7. Run `validate`, then `conform` with a corpus. Add `--against` when you can reach the
   real service.
8. Make it a kit, so its numbers become flags.
9. Build it into an image on `mocksys:local`, generate it at build time, and publish it
   with `MOCKSYS_SERVE`.

Further reading: [contracts.md](docs/contracts.md) for every static option,
[effects.md](docs/effects.md) for every verb, [twins.md](docs/twins.md) for kits, genes
and conform, and [cli.md](docs/cli.md) for every command.

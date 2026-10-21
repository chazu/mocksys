# Plan: what PLAN-RUNTIME left open

PLAN-RUNTIME landed all five phases and left seven gaps. This plan closes them.

| Work | Change | Wave |
|---|---|---|
| A | Daemon hardening: admin-API token, a cap on the recorded-request log | 1 |
| B | Kits that ship modules | 1 |
| C | gRPC streaming, reflection, recording | 1 |
| D | TLS for HTTP and gRPC mocks | 1 |
| E | An interpreter replaces the generated effect JavaScript | 1 |
| F | Server-sent events and WebSockets | 2, after E |

Wave 1 runs in parallel. F waits for E because its stateful half adds effect verbs, and
those belong in the interpreter, not in code that E deletes.

## A. Daemon hardening

**Admin token.** Today any local user can post an imposter, and an imposter can carry
module code, so any local user can run code as the daemon's owner.
- At start, once it holds its port, the daemon writes a random 32-byte token to
  `$MOCKSYS_HOME/daemon-<port>.token`, mode 0600, through a rename so a client never
  reads half a token. The temp file is removed and then created with `wx`, since a
  mode only applies to a new file and a link at that path would be followed. One file
  per port lets two daemons share a store on different admin ports. Writing after the
  listen means a daemon that loses the port race leaves the running daemon's token
  alone. No client reads the token before the port answers, so nothing is lost by
  waiting.
- A daemon therefore serves one store, so each store gets its own admin port. Without
  `MOCKSYS_ADMIN_PORT`, the default store `~/.mocksys` keeps 2626 and any other store
  takes 26000 plus the first four bytes of `sha256(path)` mod 1000, its path absolute
  and with links resolved as far as it exists. A project store (`MOCKSYS_HOME=./.mocks`) then works beside the
  shared daemon with no setting, from any subdirectory. The range is below the
  ephemeral ranges of Linux (32768+), macOS and Windows (49152+), where mock ports come
  from. `mocksys home` prints the port. A hash collision, or an explicit port another
  store holds, still gets the 401 below. In-process test daemons take the token from
  `runtime/start!`'s result instead of a file.
- A request whose URL does not parse (`GET //`) gets a 400 after the origin check and
  before the token check, rather than a throw in the request listener.
- Every admin call except `GET /` needs `Authorization: Bearer <token>`, compared in
  constant time. A missing or wrong token gets 401.
- `mocksys.mb` reads the token file for every call. A 401 means the daemon on that port
  belongs to another store or user. The error says so, names the pid that daemon
  reports on `GET /` so the user can stop it, and names `MOCKSYS_ADMIN_PORT`.
- Replacing a daemon after a version mismatch needs the token too. Without it the CLI
  fails with the same message.

**Recorded-request cap.** An imposter with `recordRequests` keeps the newest N requests,
default 10000, set by `MOCKSYS_MAX_RECORDED_REQUESTS` in the daemon's environment and
read once at start (a whole number above 0; anything else stops the daemon at start, and
the CLI shows the log). 0 is refused rather than read as "keep none": a mock that records
and keeps nothing fails every `assert`, and an imposter that should keep nothing already
says so with `recordRequests: false`.
`numberOfRequests` still counts every request. The full imposter view reports how many
were dropped. `assert` says when a dropped request could change its answer.

## B. Kits that ship modules

- A disk kit may be a directory, `$MOCKSYS_HOME/kits/<name>/kit.yaml`, with its files
  beside it. A single-file kit stays valid.
- The kit's `contract.modules` paths are relative to the kit directory and must stay
  inside it. `mocksys kit` copies each module into the scenario directory at the same
  relative path, so the scenario keeps travelling as one directory.
- A single-file kit that lists modules fails, and the error points at the directory form.
- `mocksys kits` marks kits that ship modules. `mocksys kit` prints the same warning
  `unpack` prints about module JavaScript.
- An example kit under `examples/kits/` ships a module.

## C. gRPC streaming, reflection, recording

**Streaming.** Every method kind maps onto the request and response shape the stubs
already match on.
- Server streaming. The request is one message, as for unary. A 2xx JSON array body is
  the stream's messages in order, and an object is one message. An error status ends the
  stream with that error after no messages.
- Client streaming. The call collects every message, and the request body is the JSON
  array of them. The reply is as for unary.
- Bidirectional. Each inbound message is one request through the imposter, in order. Its
  reply (an object or an array) is zero or more outbound messages. The server ends the
  stream when the client half-closes. An error reply ends the stream with that error.
- `validate` knows each method's kind from the proto.

**Reflection.** Every gRPC mock serves server reflection (v1 and v1alpha), so `grpcurl`
and Postman can list and call it without the proto.

**Recording.** `mocksys record` works against a gRPC upstream for unary and server
streaming calls. The daemon forwards each call with a grpc-js client built from the
descriptor and records it as a stub. The status becomes the HTTP status and a
`grpcCode` body, so the recorded stub replays through the existing mapping. `freeze`
then works unchanged.

## D. TLS

- A contract may say `tls: true`. The mock then serves HTTPS, or gRPC over TLS, with a
  certificate for `localhost`, `127.0.0.1` and `::1`.
- mocksys keeps one local CA in `$MOCKSYS_HOME/tls/`, created on first use, and issues
  each mock's certificate from it. `mocksys tls ca` prints the CA's path, so a client can
  trust it, for example with `NODE_EXTRA_CA_CERTS`.
- `tls: {cert: ./cert.pem, key: ./key.pem}` brings your own, with paths inside the
  scenario directory.
- Certificate generation must work in the bun binary. A pure-JS library is preferred to
  shelling out to `openssl`.
- `record --target https://...` keeps working, since that is the client side of TLS.

## E. The effect interpreter

Effects compile to JavaScript source strings (`inject.cljs`), which the daemon
evaluates. Mountebank needed that form. It is now gone, so the daemon can evaluate
effect maps directly.

- Stubs carry the operation's effect map, and the contract settings it needs (state,
  collections, the clock, signing keys), instead of an `inject` string. The daemon
  validates effects when an imposter is posted. Bad effects get 400, naming the stub and
  the field.
- One interpreter namespace evaluates effects over the per-imposter state. Custom verbs
  run from it directly.
- **Proof before deletion.** While both exist, run every effect case in the unit suite
  through codegen and through the interpreter, and compare the responses and the
  resulting state. Run `conform` from a daemon built at the base commit to the new
  one over `examples/okta-corpus.json`. Both must show 0 differences.
- Then delete the codegen, the `inject` response type and `compile-injection`. A
  planted `inject` in an imposter.json is then refused as an unknown field.
- The Okta twin's responses do not change.

## F. Server-sent events and WebSockets

Built on E.
- **SSE.** An operation can answer with a stream of events
  (`event`, `data`, `id`, `retry`), either a scripted list with delays or events that
  other operations publish to a named channel while the stream is open.
- **WebSockets.** An operation can accept an upgrade on its path. Each inbound message
  goes through the imposter as one request, like a bidirectional gRPC message, so state,
  effects and `assert --saw` apply. A reply is zero or more outbound messages. Other
  operations can publish to a connection's channel.
- Publishing is one effect verb that SSE and WebSockets share.
- Both must run in the bun binary.

## Verification for every work item

`npm test`, `npm run build` with 0 warnings, and `scripts/smoke.sh` on node. A change
that adds a dependency or touches serving also runs smoke against the bun binary:
`scripts/build-binary.sh && MOCKSYS_BIN=dist/mocksys scripts/smoke.sh`. Use a distinct
`SMOKE_PORT` per worktree. Each item adds tests and an example, updates the docs it
changes, and gets an independent review before it merges.

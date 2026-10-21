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
  It fails when the kits load, as a skipped kit with a warning, so `kits` reports it too.
- `mocksys kits` marks kits that ship modules. `mocksys kit` prints the same warning
  `unpack` prints about module JavaScript.
- `kit` checks every destination before it writes: a copy may not land outside the
  scenario directory through a symlink in it, or through a dangling one, or below a
  file. It copies the modules before it writes `contract.yaml`, so a copy that fails
  at write time (permissions, a full disk) leaves no new contract behind.
- Running `kit` again rewrites `contract.yaml`, since flags change it, but no flag changes
  a module, so a module that differs from the kit's copy is a hand edit. `kit` then writes
  nothing and names the file; `--force` replaces it. A matching module is left alone.
- An example kit under `examples/kits/` ships a module.
- Not covered: a directory kit's gRPC proto is not copied. A gRPC kit still needs an
  absolute `proto` path.

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
and Postman can list and call it without the proto. `@grpc/reflection` answers the
requests, but its `addToServer` loads `reflection.proto` from a path that bun bakes into
the binary from the build machine, so the daemon registers the service from proto text
it carries. `list` names the proto's services, not the reflection service itself.

**Recording.** `mocksys record --proto FILE --target host:port` works against a gRPC
upstream, without TLS, for unary, server streaming and client streaming calls. A client
stream's request body is already the array of its messages, so it forwards as one call.
A bidirectional call is refused with `UNIMPLEMENTED`, since its turns are separate
requests and would not replay as one stream. `record` copies the proto and its imports
into the scenario directory. The daemon forwards each call with a grpc-js client built
from the descriptor and records it as a stub. The status becomes the HTTP status that
`google.rpc.Code` gives it and a `grpcCode` body, so the recorded stub replays through
the existing mapping. An upstream that does not connect fails the call at once and is
not recorded. `freeze` lifts a grpc imposter to a grpc contract (`protocol`, `proto`,
`rpc` operations).

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

As built:
- **The CLI issues, at post time.** `compile!` records `tls` in `imposter.json` as
  declared (`true`, or the relative paths), like `modules`. `ensure-running!` swaps in
  PEM text: a new certificate from the CA for `true`, the files for a pair. Not the
  daemon: `tls ca` and `run`'s trust lines name the CLI's store, and a daemon on the
  admin port may run with another `MOCKSYS_HOME`. Not `compile!`: `imposter.json` is
  copied by pack, publish and push, so it must hold no key. The daemon reads no key
  files, checks the pair with `createSecureContext`, and shows `tls: true` without it.
- **The CA.** `$MOCKSYS_HOME/tls/` at 0700, `ca.key` at 0600, both re-tightened on use,
  and a `.gitignore` of `*`, because `publish` runs `git add -A` over the store and a
  project may commit `./.mocks`. Created in a temp directory and renamed into place.
  Ten years. Replaced when it expires within 30 days or a file is missing:
  the old directory is renamed to `tls.bak-<time>`, never deleted, and a stderr line
  says that clients must trust the new CA and running mocks must be run again. A file
  that is there but unusable (unreadable, not RSA, encrypted, a key of another
  certificate) is an error naming it, since replacing it would lose the user's CA. A mock's certificate lasts at most 397 days and is new on every run, so it
  needs no renewal. node-forge signs RSA keys from node's crypto.
- **Trust.** `tls ca` prints only the path on stdout (`NODE_EXTRA_CA_CERTS=$(mocksys
  tls ca)`), and the curl, grpc-js, gRPC C core and grpcurl forms on stderr. `run`
  prints the CA and the commands; `env` and `up` export `NODE_EXTRA_CA_CERTS` unless
  it already names another file; `env --json` adds `caCert`. `conform` and `clock`
  reach a TLS twin, skipping the certificate check on the CLI's own calls.

## E. The effect interpreter

Effects compile to JavaScript source strings (`inject.cljs`), which the daemon
evaluates. Mountebank needed that form. It is now gone, so the daemon can evaluate
effect maps directly.

- Stubs carry the operation's effect map, `{effect: {...}}`, instead of an `inject`
  string. The settings every effect shares (seeds, collections, the clock's start, the
  id stream's seed, the fallback issuer) go once into the imposter's `state`, not into
  each stub. Signing keys stay in the `sign` verb's spec, where the contract has them.
  The clock control stub answers with `{clock: true}`. The daemon validates effects when
  an imposter is posted. Bad effects get 400, naming the stub and the field.
- One interpreter namespace (`mocksys.effect`) evaluates effects over the per-imposter
  state, from a table of verbs that also holds each verb's field checks. Custom verbs
  run from it directly. The verbs a module must export are read off the effects, so the
  imposter's `calls` field goes away.
- `MOCKSYS_NO_INJECTION` refuses modules only. An effect is data, so a stateful
  scenario without modules runs no code from the scenario.
- **Proof before deletion.** While both exist, run every effect case in the unit suite
  through codegen and through the interpreter, and compare the responses and the
  resulting state. Run `conform` from a daemon built at the base commit to the new
  one over `examples/okta-corpus.json`. Both must show 0 differences.
  `scripts/conform-builds.sh <base-ref>` reruns the second, plus a byte-for-byte replay
  of the corpus and of stateful sequences.
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

**As built.** Streams are effect answers, not a new imposter response type, so a
stream gets `bind`, `limit`, `lookup` and `find` in front of it (a 404 for an unknown
job, a 401 for a bad token) and names its channel with the same `{var}` interpolation
as everything else.
- **Answers.** `sse` and `websocket` are terminal verbs beside the collection verbs:
  an effect ends in exactly one terminal, or in `store` then `respond`.
  `sse: {events, channel, retry, keepAlive, headers}` needs `events`, a `channel` or
  both. `websocket: {messages, channel}` accepts the upgrade. An event is
  `{data | dataVar, event, id, delay}`; a message is `{data | dataVar, delay}`. A
  string `data` is sent as written after interpolation, any other value as JSON, and
  `dataVar` sends a variable. `delay` is whole milliseconds of wall time after the
  previous event. Both need `protocol: http`; `lower` and the daemon refuse them in a
  grpc imposter.
- **SSE on the wire.** `200 text/event-stream`, then `retry:` when set, then the
  scripted events, then whatever the channel carries. A stream without a channel ends
  after its last event; one with a channel stays open until the client leaves. A
  `: keep-alive` comment goes out every `keepAlive` ms (default 15000, 0 turns it off).
  `Last-Event-ID` resumes the script after the event with that id; channel events are
  live only, never replayed.
- **WebSockets.** The imposter's server takes the `upgrade` event. The upgrade request
  goes through the imposter as `GET <path>`; an answer other than `websocket` is
  written back as a plain HTTP response, so a gate refuses the upgrade with its own
  status. A plain GET that reaches a `websocket` answer gets 426. Each inbound message
  goes through the imposter, in order, as `MESSAGE <path>` with the upgrade's query
  and headers and the message text as the body, so `request: {method: MESSAGE}`
  operations, body predicates, effects, state and `assert --saw 'MESSAGE /path'`
  apply. A 1xx–3xx reply sends a JSON array body as one message per element, any
  other non-empty body as one message, an empty body as none. A 4xx/5xx reply closes
  the socket with code 4000 + status and the body as the reason. `ws` serves the
  upgrade; it runs under node, bun and the bun binary.
- **`publish`.** `publish: {channel, data | dataVar, event, id}`, or a list, runs
  after the terminal and only when the effect's answer is below 400, so a miss or a
  rejected write publishes nothing; it sees the variables the terminal set (`as`).
  An SSE subscriber gets the whole event; a WebSocket subscriber gets the data.
- **Channels.** Per imposter, a null-prototype map from name to subscribers that the
  imposter holds next to its `state`; reposting starts empty. A stream subscribes
  before its headers or its 101 go out, so a client that has its headers misses
  nothing published after. A closed SSE response or socket unsubscribes and clears
  its timers, and an empty channel is removed. Deleting the imposter cuts its sockets,
  which runs the same cleanup.
- **Recorded.** The SSE GET (with its `Last-Event-ID`), the upgrade GET, every inbound
  message as `MESSAGE`, and every publishing request. Outbound events are not.
- **Clock and limits.** A connect is one request against its operation's `limit`, as
  is each inbound message against its own. Delays and keep-alives are wall time; the
  virtual clock neither drives nor is moved by a stream. `{now}` in a scripted event
  reads the clock at connect, in a publish at the publishing request.

## Verification for every work item

`npm test`, `npm run build` with 0 warnings, and `scripts/smoke.sh` on node. A change
that adds a dependency or touches serving also runs smoke against the bun binary:
`scripts/build-binary.sh && MOCKSYS_BIN=dist/mocksys scripts/smoke.sh` (smoke resolves a
relative `MOCKSYS_BIN` before it changes directory). Use a distinct
`SMOKE_PORT` per worktree. Each item adds tests and an example, updates the docs it
changes, and gets an independent review before it merges.

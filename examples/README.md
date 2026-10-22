# examples

Runnable contracts and a conform corpus that back the
[walkthrough](../docs/walkthrough.md). All commands assume a built `mocksys`
(`npm run build`) on your `PATH` (or use `node bin/mocksys`).

| File                                         | What it shows                                                                                                                                                            |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`users.yaml`](users.yaml)                   | A hand-authored **resource collection**: CRUD + SCIM-ish filter + cursor pagination — the building block behind `kit okta`.                                              |
| [`login-throttle.yaml`](login-throttle.yaml) | The three **time-aware primitives** by hand: a `limit` (rate limit → 429), `store.ttl` (an expiring session token), and `state.clock` (the deterministic virtual clock). |
| [`directory.yaml`](directory.yaml)           | A twin built **only from genes**: `crud-collection` for users and groups plus `membership`, with no hand-written operations.                                              |
| [`kits/directory.yaml`](kits/directory.yaml) | A **user kit**: the directory twin as a template with flags (`--users-base`, `--rate-limit`, ...). Copy it to `$MOCKSYS_HOME/kits/` and run `mocksys kit directory`. |
| [`webhooks/`](webhooks/)                     | A **custom verb**: a webhook receiver whose `verifySignature` verb (in `verbs.js`) checks an HMAC signature header and rejects a forged delivery with 401. Copy the whole directory: the module travels with the scenario. |
| [`kits/webhooks/`](kits/webhooks/)           | A **kit that ships a module**: the webhook receiver as a directory kit, `kit.yaml` with a `--secret` flag and `verbs.js` beside it. Copy the directory to `$MOCKSYS_HOME/kits/` and run `mocksys kit webhooks`; `kit` copies `verbs.js` into the scenario. |
| [`grpc-todos/`](grpc-todos)                  | A stateful **gRPC** twin of `todo.proto`: create, get (`NOT_FOUND` on a miss), an update that keeps the fields it does not set, filtered list, an explicit `grpcCode`, and server, client and bidirectional streams. `client.js` calls it with grpc-js; `reflect.js` lists and describes it through server reflection.                     |
| [`https.yaml`](https.yaml)                   | An **HTTPS** mock: `tls: true` serves it with a certificate from the mocksys CA, which `mocksys tls ca` prints for the client to trust. Add the same line to `grpc-todos/contract.yaml` for gRPC over TLS. |
| [`chat.yaml`](chat.yaml)                     | **Server-sent events and WebSockets**: a chat room served as an SSE stream and as a WebSocket. A POST, or a `say` message on the socket (a `MESSAGE` operation), stores the message and `publish`es it to the room's channel, which both receive. |
| [`okta-corpus.json`](okta-corpus.json)       | A request corpus for `mocksys conform` — golden expectations for the Okta twin.                                                                                          |
| [`bifrost/`](bifrost/)                       | The **Bifrost LLM-gateway twin** from `mocksys kit bifrost`. `client.js` walks it as an app and an admin would: a chat completion with a virtual key, 401s for a missing or inactive key, a key created on the control plane and used at once, then revoked, and a per-key rate limit running out. `virtual-keys.json` seeds your own keys with `--virtual-keys`; `corpus.json` is its `conform` corpus. |
| [`llm-rate-limits.yaml`](llm-rate-limits.yaml) | **Rate limits shaped like an OpenAI-style API**, by hand: a `limit` that counts per `x-api-key` and renames its headers to `x-ratelimit-*-requests`, and a `crud-collection` gene whose `rate-limit` sets `key-source`, `miss` and `headers`. |

## Quick start

These examples scope the store to this repo with `MOCKSYS_HOME=./.mocks` so they never
touch your shared `~/.mocksys`. That store gets its own daemon on its own admin port.

```sh
export MOCKSYS_HOME=./.mocks

# the okta twin + the conform corpus
mocksys kit okta --port 9200
mocksys run okta/okta --port 9200
mocksys conform okta/okta --corpus examples/okta-corpus.json

# the hand-authored collection
mkdir -p .mocks/demo/users && cp examples/users.yaml .mocks/demo/users/contract.yaml
mocksys run demo/users --port 9100
curl -G localhost:9100/api/v1/users --data-urlencode 'filter=status eq "ACTIVE"'

# the gene-only directory twin
mkdir -p .mocks/demo/directory && cp examples/directory.yaml .mocks/demo/directory/contract.yaml
mocksys run demo/directory --port 9130
curl -X PUT localhost:9130/api/v1/groups/g1/users/u2        # 204, assign
curl localhost:9130/api/v1/groups/g1/users                  # [bob], hydrated

# the same twin as a user kit, with flags
mkdir -p .mocks/kits && cp examples/kits/directory.yaml .mocks/kits/
mocksys kits                                                # lists it next to the built-ins
mocksys kit directory --users-base /scim/v2/Users --port 9140
mocksys run directory/directory
curl localhost:9140/scim/v2/Users

# the webhook receiver with a custom verb
mkdir -p .mocks/demo/webhooks && cp examples/webhooks/* .mocks/demo/webhooks/
mocksys run demo/webhooks --port 9150

# the same receiver as a kit that ships its verb
cp -R examples/kits/webhooks .mocks/kits/
mocksys kit webhooks --secret whsec_mine --port 9160      # copies verbs.js into webhooks/webhooks
mocksys run webhooks/webhooks
BODY='{"action":"opened","number":7}'
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac whsec_test | sed 's/^.* //')"
curl -i -X POST localhost:9150/webhooks -H "X-Hub-Signature-256: $SIG" -d "$BODY"           # 202, stored
curl -i -X POST localhost:9150/webhooks -H 'X-Hub-Signature-256: sha256=00' -d "$BODY"      # 401
curl localhost:9150/deliveries                                                             # the one accepted

# the rate-limit + expiring-session demo
mkdir -p .mocks/demo/login-throttle && cp examples/login-throttle.yaml .mocks/demo/login-throttle/contract.yaml
mocksys run demo/login-throttle --port 9120
T=$(curl -s -X POST localhost:9120/login -d 'username=alice' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl localhost:9120/me -H "Authorization: Bearer $T"           # 200
mocksys clock demo/login-throttle advance 1h
curl -i localhost:9120/me -H "Authorization: Bearer $T"        # 401 (session expired)
```

```sh
# the gRPC todo twin (the proto sits next to the contract in the scenario directory)
mkdir -p .mocks/demo/todos && cp examples/grpc-todos/contract.yaml examples/grpc-todos/todo.proto .mocks/demo/todos/
mocksys run demo/todos --port 9150                                  # at localhost:9150 (gRPC)
node examples/grpc-todos/client.js localhost:9150 GetTodo '{"id":"t1"}'
node examples/grpc-todos/client.js localhost:9150 GetTodo '{"id":"nope"}'   # {"code":"NOT_FOUND",...}
node examples/grpc-todos/client.js localhost:9150 WatchTodos '{}'           # one JSON line per todo
node examples/grpc-todos/client.js localhost:9150 CreateTodos '[{"title":"a"},{"title":"b"}]'
node examples/grpc-todos/reflect.js localhost:9150 todo.v1.Todos             # rpcs, via reflection
mocksys assert demo/todos --saw 'POST /todo.v1.Todos/GetTodo'
```

```sh
# a chat room over SSE and a WebSocket, fed by one channel
mkdir -p .mocks/demo/chat && cp examples/chat.yaml .mocks/demo/chat/contract.yaml
mocksys run demo/chat --port 9180
curl -N localhost:9180/rooms/lobby/events &                         # event: joined, then the room
curl -X POST localhost:9180/rooms/lobby/messages -d '{"from":"ann","text":"hi"}'   # the stream prints it
node -e 'const w=new WebSocket("ws://localhost:9180/rooms/lobby/socket");
  w.onmessage=e=>console.log(e.data); w.onopen=()=>w.send(JSON.stringify({type:"ping"}));
  setTimeout(()=>w.close(),500)'                                     # welcome, pong
mocksys assert demo/chat --saw 'MESSAGE /rooms/lobby/socket'
```

```sh
# the Bifrost LLM-gateway twin, with a 3-request budget per virtual key
mocksys kit bifrost --rate-limit 3 --port 9300
mocksys run bifrost/bifrost
node examples/bifrost/client.js http://localhost:9300       # 200, 401s, create/use/revoke a key, then 429
mocksys conform bifrost/bifrost --corpus examples/bifrost/corpus.json
mocksys clock bifrost/bifrost advance 1m                     # a new window: the budget resets

# the same twin seeded with your own virtual keys
mocksys kit bifrost --scenario bifrost/custom --virtual-keys examples/bifrost/virtual-keys.json --port 9310
mocksys run bifrost/custom
curl -X POST localhost:9310/v1/chat/completions -H 'x-bf-vk: sk-bf-ci-000000000000' -d '{"model":"gpt-4o-mini"}'

# OpenAI-style rate limits, hand-authored
mkdir -p .mocks/demo/llm-limits && cp examples/llm-rate-limits.yaml .mocks/demo/llm-limits/contract.yaml
mocksys run demo/llm-limits --port 9190
curl -si -X POST localhost:9190/v1/embeddings -H 'x-api-key: team-a' -d '{"model":"m"}' | grep -i x-ratelimit
```

```sh
# an HTTPS mock, trusted through the mocksys CA
mkdir -p .mocks/demo/https && cp examples/https.yaml .mocks/demo/https/contract.yaml
mocksys run demo/https --port 9160                                  # at https://localhost:9160
curl --cacert "$(mocksys tls ca)" https://localhost:9160/whoami
export NODE_EXTRA_CA_CERTS="$(mocksys tls ca)"                      # node clients trust it too

# the gRPC todo twin over TLS: add `tls: true` to its contract
mkdir -p .mocks/demo/todos-tls && cp examples/grpc-todos/todo.proto .mocks/demo/todos-tls/
{ echo 'tls: true'; cat examples/grpc-todos/contract.yaml; } > .mocks/demo/todos-tls/contract.yaml
mocksys run demo/todos-tls --port 9170                              # at localhost:9170 (gRPC over TLS)
NODE_EXTRA_CA_CERTS="$(mocksys tls ca)" node examples/grpc-todos/client.js --tls localhost:9170 GetTodo '{"id":"t1"}'
```

See [docs/walkthrough.md](../docs/walkthrough.md) for the narrated tour and
[docs/effects.md](../docs/effects.md) for the full effect-language reference.

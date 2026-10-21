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
| [`grpc-todos/`](grpc-todos)                  | A stateful **gRPC** twin of `todo.proto`: create, get (`NOT_FOUND` on a miss), filtered list, and an explicit `grpcCode`. `client.js` calls it with grpc-js.                     |
| [`okta-corpus.json`](okta-corpus.json)       | A request corpus for `mocksys conform` — golden expectations for the Okta twin.                                                                                          |

## Quick start

These examples scope the store to this repo with `MOCKSYS_HOME=./.mocks` so they never
touch your shared `~/.mocksys`.

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
mocksys assert demo/todos --saw 'POST /todo.v1.Todos/GetTodo'
```

See [docs/walkthrough.md](../docs/walkthrough.md) for the narrated tour and
[docs/effects.md](../docs/effects.md) for the full effect-language reference.

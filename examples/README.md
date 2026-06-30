# examples

Runnable contracts and a conform corpus that back the
[walkthrough](../docs/walkthrough.md). All commands assume a built `mocksys`
(`npm run build`) on your `PATH` (or use `node bin/mocksys`).

| File | What it shows |
|---|---|
| [`users.yaml`](users.yaml) | A hand-authored **resource collection**: CRUD + SCIM-ish filter + cursor pagination — the building block behind `kit okta`. |
| [`login-throttle.yaml`](login-throttle.yaml) | The three **time-aware primitives** by hand: a `limit` (rate limit → 429), `store.ttl` (an expiring session token), and `state.clock` (the deterministic virtual clock). |
| [`okta-corpus.json`](okta-corpus.json) | A request corpus for `mocksys conform` — golden expectations for the Okta twin. |

## Quick start

These examples scope the store to this repo with `MOCKSYS_HOME=./.mocks` so they never
touch your shared `~/.mocksys`.

```sh
# the okta twin + the conform corpus
mocksys kit okta --port 9200
mocksys run okta/okta --port 9200
mocksys conform okta/okta --corpus examples/okta-corpus.json

# the hand-authored collection
export MOCKSYS_HOME=./.mocks
mkdir -p .mocks/demo/users && cp examples/users.yaml .mocks/demo/users/contract.yaml
mocksys run demo/users --port 9100
curl 'localhost:9100/api/v1/users?filter=status eq "ACTIVE"'

# the rate-limit + expiring-session demo
mkdir -p .mocks/demo/login-throttle && cp examples/login-throttle.yaml .mocks/demo/login-throttle/contract.yaml
mocksys run demo/login-throttle --port 9120
T=$(curl -s -X POST localhost:9120/login -d 'username=alice' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl localhost:9120/me -H "Authorization: Bearer $T"           # 200
mocksys clock demo/login-throttle advance 1h
curl -i localhost:9120/me -H "Authorization: Bearer $T"        # 401 (session expired)
```

See [docs/walkthrough.md](../docs/walkthrough.md) for the narrated tour and
[docs/effects.md](../docs/effects.md) for the full effect-language reference.

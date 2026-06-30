# Digital twins: kits, genes, and conform

A **digital twin** is a stateful behavioral clone of a whole external service — not a
single fixture, but a running system you can drive at volume, deterministically, and
validate against the real thing. mocksys builds twins from composable **genes** via a
one-command **kit**, and keeps them honest with **conform**.

This mirrors the
[Digital-Twin-Universe](https://factory.strongdm.ai/techniques/dtu) and
[gene-transfusion](https://factory.strongdm.ai/techniques/gene-transfusion) techniques:
clone the observable behavior at the API boundary; build by composing proven patterns;
validate until the diff disappears.

## Kits

A **kit** generates a complete, runnable contract — often stateful — in one command:

```sh
mocksys kit okta                          # Okta twin: users & groups CRUD + OIDC
mocksys kit oauth2 --provider github      # OAuth2 provider: picker, single-use codes, userinfo
```

| Kit | Options | Produces |
|---|---|---|
| `okta` | `--users FILE.json` `--rate-limit N` `--rate-window S` `--token-ttl S` `--scenario NAME` `--port N` | users + groups CRUD (SCIM filter, pagination), Okta error envelope, OIDC authorize/token/userinfo — all on one stateful imposter. `--rate-limit` adds 429s, `--token-ttl` makes OIDC tokens expire on the virtual clock. |
| `oauth2` | `--provider github\|codeberg` `--users FILE.json` `--scenario NAME` `--port N` | an OAuth2 provider: account picker, single-use authorization codes, a token table, a bound userinfo endpoint. |

The default scenario id is `oauth/<provider>` for `oauth2` and `okta/okta` for `okta`;
override with `--scenario`. After generation, treat the twin like any scenario: `run`,
`env`, `inspect`, `conform`, `stack`/`up`.

```sh
mocksys kit okta --port 9200
mocksys run okta/okta --port 9200
eval "$(mocksys env okta/okta)"           # exports BASE_URL=http://localhost:9200
```

## Genes

A **gene** is a small, parameterized contract fragment — a proven behavioral pattern — that
a kit composes. A twin is *composed from genes* rather than written from scratch; that is
gene transfusion in practice.

```sh
mocksys genes        # list the genes (and kits) available
```

| Gene | Behavior |
|---|---|
| `crud-collection` | A REST resource: create / list / get / update / delete, SCIM-ish filter + cursor pagination, over a seeded [collection](effects.md#collections-crud-resources). |
| `oidc-handshake` | OAuth2/OIDC: authorize (account picker + single-use code), token exchange, bearer userinfo. Optional token TTL. |
| `error-envelope` | Provider-shaped error bodies for misses (e.g. Okta `errorCode`/`errorSummary`). |
| `rate-limit` | A per-client request budget over a [virtual-clock](effects.md#the-virtual-clock) window → `429` + `X-Rate-Limit-*` headers. |
| `virtual-clock` | Deterministic, advanceable time (TTL expiry, rate-limit windows, `now` binds); driven by `mocksys clock`. |

Genes live in the `kits` module. Each is a function returning `{:operations :collections
:seed}`; a kit merges fragments with `compose`. To extend mocksys with a new twin, write a
builder that composes the genes it needs (and add any new gene there), then register it in
the `kits` map — `oauth2` and `okta` are the worked examples.

### Anatomy of the `okta` twin

`kit okta` composes:

- `crud-collection` for **users** at `/api/v1/users` (id prefix `00u`, default `status:
  STAGED`), seeded with Okta-shaped accounts.
- `crud-collection` for **groups** at `/api/v1/groups` (id prefix `00g`, default `type:
  OKTA_GROUP`).
- `oidc-handshake` at `/oauth2/v1/authorize|token|userinfo`.
- the Okta `error-envelope` on every CRUD miss.

So you get, on one stateful imposter:

```sh
B=http://localhost:9200
curl "$B/api/v1/users?filter=status%20eq%20%22ACTIVE%22"            # SCIM filter
curl -X POST "$B/api/v1/users" -d '{"profile":{"login":"x@y.com"}}' # 201, id minted
curl "$B/api/v1/users?limit=50"                                     # paginated; Link: rel="next"
curl "$B/api/v1/users/missing"                                      # 404 Okta error envelope
# OIDC on the same twin: authorize -> token -> userinfo -> the bound user
```

> v1 note: OIDC `userinfo` resolves a seeded `oidc_users` array (derived from the same
> accounts at seed time), not the live SCIM `users` collection — so you can log in as
> seeded accounts but not yet as SCIM-created ones.

### Seeding your own accounts

```sh
mocksys kit okta --users my-users.json     # a JSON array of user objects, replacing the defaults
```

## conform — the fidelity loop

`conform` replays a request corpus at the twin and, when you have credentials, at the
**live** service, then diffs the responses — normalized by the service's volatile fields
so request-ids and timestamps don't read as drift. It exits non-zero on any divergence, so
it drops straight into CI. This is how you drive a twin toward zero observable delta.

```sh
# golden mode — each status must match its `expect` (or be < 500)
mocksys conform okta/okta --corpus calls.json

# diff mode — twin vs live, field-masked
mocksys conform okta/okta --corpus calls.json --against https://your.okta.com
mocksys conform okta/okta --corpus calls.json --against URL --ignore created,lastUpdated

# replay what the twin already received (omit --corpus; run it and drive traffic first)
mocksys conform okta/okta
```

### Corpus format

A JSON array of requests (or `{"requests":[...]}`):

```json
[ { "method": "GET",  "path": "/api/v1/users",            "expect": 200 },
  { "method": "GET",  "path": "/api/v1/users/missing",    "expect": 404 },
  { "method": "POST", "path": "/api/v1/users",
    "headers": { "Content-Type": "application/json" },
    "body": { "profile": { "login": "x@y.com" } } } ]
```

- `body` may be a JSON value (sent as JSON) or a string.
- `expect` (a status) is used only in golden mode (no `--against`).
- Omit `--corpus` to replay the running twin's recorded requests.

### What gets diffed

| | Default | With `--headers` |
|---|---|---|
| Status code | ✓ | ✓ |
| Body | ✓ (JSON canonicalized: keys sorted; `--ignore` keys masked) | ✓ |
| Headers | — | ✓ (volatile headers dropped) |

`--ignore k1,k2` masks those JSON keys (recursively) in both bodies before comparing — for
server-generated timestamps and ids. Volatile headers come from the scenario's
[service profile](architecture.md#module-map).

Each `conform` run reposts the imposter, so the twin starts from a fresh, reseeded,
deterministic state.

## Determinism: the virtual clock + rate limits

A twin is deterministic: fixed seed, fresh state per run, no real network. Time is
deterministic too, via the **virtual clock** — a "now" that only moves when you advance it.
So token expiry, session lifetimes, and rate-limit windows are reproducible:

```sh
mocksys kit okta --rate-limit 100 --token-ttl 3600   # 429s + tokens that expire on the clock
mocksys run okta/okta
# ... obtain a token, confirm it works ...
mocksys clock okta/okta advance 2h                   # the token is now expired (401)
```

- `--token-ttl S` makes OIDC tokens expire after `S` virtual seconds (`store.ttl` under the
  hood). A `lookup` of an expired token misses → `401`.
- `--rate-limit N [--rate-window S]` gives each client `N` requests per `S`-second window
  (default 60); past the budget, `429` + `X-Rate-Limit-*`. Advancing the clock past the
  window resets the budget.

Both compose — the rate-limit window is keyed on the virtual clock — and both are exposed
as genes (`rate-limit`, `virtual-clock`) for your own twins. See
[effects.md](effects.md#the-virtual-clock) for the primitives and `mocksys clock` for
driving time.

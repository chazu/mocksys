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
| `okta` | `--users FILE.json` `--rate-limit N` `--rate-window S` `--token-ttl S` `--issuer URL` `--signing-key FILE.pem` `--scenario NAME` `--port N` | users + groups CRUD (SCIM filter, pagination), group membership (assign/unassign + list either side, hydrated & paginated), Okta error envelope, OIDC authorize/token/userinfo minting **signed RS256 JWTs** with published JWKS + discovery — all on one stateful imposter. `--rate-limit` adds 429s, `--token-ttl` sets the OIDC token lifetime on the virtual clock (default 3600 s), `--signing-key` pins the JWT key, `--issuer` sets the JWT `iss`/`aud` for requests that carry no `Host` header. |
| `oauth2` | `--provider github\|codeberg` `--users FILE.json` `--token-ttl S` `--scenario NAME` `--port N` | an OAuth2 provider: account picker, single-use authorization codes, a token table, a bound userinfo endpoint. A YAML kit over the `oauth2-provider` gene. |
| yours | the flags your kit declares, plus `--scenario NAME` `--port N` | whatever its contract template describes. See [Writing a kit](#writing-a-kit). |

The default scenario id is `oauth/<provider>` for `oauth2` and `<kit>/<kit>` for any
other kit, such as `okta/okta`; override with `--scenario`. After generation, treat the twin like any scenario: `run`,
`env`, `inspect`, `conform`, `stack`/`up`.

```sh
mocksys kit okta --port 9200
mocksys run okta/okta --port 9200
eval "$(mocksys env okta/okta)"           # exports BASE_URL=http://localhost:9200
```

`kit` rejects any option the kit does not declare, apart from `--scenario` and `--port`.
`mocksys kits` lists every kit with its flags and their defaults.

### Writing a kit

A kit is a YAML file: a contract template plus the flags it accepts, each with a default.

```yaml
name: directory
doc: users, groups and memberships composed from genes
flags:                     # flag -> default; `kit directory --<flag> VALUE` overrides it
  users-base: /api/v1/users
  rate-limit: 100
contract:                  # any contract; usually a list of genes
  seed:
    users: [ { id: u1, status: ACTIVE, profile: { login: alice@example.com } } ]
  genes:
    - crud-collection: { coll: users, base: "{users-base}", seed-name: users,
                         rate-limit: { max: "{rate-limit}" } }
```

- A string that is **exactly** `"{flag}"` becomes the flag's value with its type, so
  `"{rate-limit}"` above becomes the number `100`. A flag inside a longer string, such as
  `"/v2{users-base}"`, is left as written, and so is a template that names no flag, such
  as an effect's `"{token}"`.
- A command-line value takes the type of the flag's default. A number default parses the
  value as a number, and a `true`/`false` default as a boolean. `--users` and
  `--signing-key` always read a file (a JSON array, a PEM).
- `kit` sets `service`, `scenario` and `origin: kit` on the generated contract. Without a
  `summary` in the template, the kit's `doc` is used. `contract.yaml` keeps the template's
  `genes:` form.

Put your kits in `$MOCKSYS_HOME/kits/`, as `*.yaml` or `*.yml` files. A kit without
`name` is named after its file. A user kit with a built-in's name replaces the built-in,
and `kit` and `kits` print a warning saying so. When two user files declare one name, the
file that sorts last wins, and the warning names both files. A kit file that cannot be
read or parsed is skipped with a warning.
[`examples/kits/directory.yaml`](../examples/kits/directory.yaml) is a complete user kit.

The built-in `oauth2` kit is a YAML file (`src/mocksys/kits/oauth2.yaml`), inlined into the
CLI at build time. Its provider-shaped paths (`/github/login/oauth/...`), default accounts
and account index come from the `oauth2-provider` gene, because whole-value substitution
cannot build `"/{provider}/login/..."`. The `okta` kit stays a builder function in
`src/mocksys/kits.cljs`. It generates an RSA signing key on each `kit` run and publishes the
derived JWK, which a fixed template cannot do.

## Genes

A **gene** is a small, parameterized contract fragment. It is a proven behavioral pattern
such as a REST resource or an OAuth handshake. A twin is *composed from genes* rather than
written from scratch, which is gene transfusion in practice. Any contract can list genes
under `genes:` (see [contracts.md](contracts.md#genes)), and a kit composes them the same
way.

```yaml
genes:
  - crud-collection: { coll: users, base: /api/v1/users, seed-name: users, id-prefix: u }
  - membership: { users-coll: users, groups-coll: groups }
```

```sh
mocksys genes        # every gene with its params, and what is built in rather than a gene
```

| Gene | Behavior | Required params |
|---|---|---|
| `crud-collection` | A REST resource: create / list / get / update / delete, SCIM filter + cursor pagination, over a seeded [collection](effects.md#collections-crud-resources). `rate-limit: {max, window}` adds a per-client budget. | `coll`, `base` |
| `oidc-handshake` | OAuth2/OIDC: authorize (account picker + single-use code), token exchange, bearer userinfo. Users come from a **seed array** (`seed-name`, `users`) or, with `user-source: {collection, login-field, active-only, claims}`, from a **live collection** with a dynamic picker and `status`-gated issuance. Optional `token-ttl`. | `authz`, `token`, `userinfo` |
| `membership` | A many-to-many relation between two collections: assign/unassign (idempotent, foreign-key-guarded) + list either side, [hydrated and cursor-paginated](effects.md#joins-lookups-signing-rendering-extended-verbs). | none |
| `oauth2-provider` | A GitHub- or Codeberg-shaped OAuth2 provider: `oidc-handshake` over seed accounts at `/<provider>/login/oauth/...`, the provider's userinfo path, its default accounts (or `users`), and an account index at `/`. The `oauth2` kit is this gene plus flags. | none |

Some behavior is not a standalone gene. `mocksys genes` lists where each piece lives:

| Capability | Where it lives |
|---|---|
| `rate-limit` | `crud-collection`'s `rate-limit` param, or a hand-written [`limit`](effects.md#limit--rate-limiting). A budget over a virtual-clock window, then `429` + `X-Rate-Limit-*`. |
| `error-envelope` | The default `not-found` and `invalid-filter` responses of `crud-collection` and `membership` (Okta `errorCode`/`errorSummary`). Override them with those params. |
| `virtual-clock` | The contract's `state.clock`, or implied by `store.ttl`, `limit`, `sign` or a `now` bind ([effects.md](effects.md#the-virtual-clock)). `mocksys clock` moves it. |
| `jwt-signing` | The [`sign`](effects.md#sign--issue-a-signed-jwt-non-terminal) verb. `kit okta` wires it with an RSA key generated at kit time, so it is an option only code can pass to `oidc-handshake`, not a YAML param. |

Genes are functions in `src/mocksys/kits.cljs` that return a fragment `{:operations
:collections :seed}`, registered in `kits/genes` with their `:params`. `compose` merges
fragments, and earlier entries win. A new gene needs a function and a registry entry. The
`every-declared-param-changes-the-gene` test fails when a declared param has no effect,
which keeps `mocksys genes` truthful.

### Anatomy of the `okta` twin

`kit okta` composes:

- `crud-collection` for **users** at `/api/v1/users` (id prefix `00u`, default `status:
  STAGED`), seeded with Okta-shaped accounts.
- `crud-collection` for **groups** at `/api/v1/groups` (id prefix `00g`, default `type:
  OKTA_GROUP`).
- `membership` relating **users** and **groups** at `/api/v1/groups/{gid}/users/{uid}`.
- `oidc-handshake` at `/oauth2/v1/authorize|token|userinfo`, keyed on the **live** `users`
  collection (dynamic picker, `userinfo` shaped from the mutable row). The token endpoint
  mints **signed RS256 JWTs** (access + id token), published at `/oauth2/v1/keys` (JWKS)
  and `/.well-known/openid-configuration` (discovery).
- the Okta `error-envelope` on every CRUD miss.

So you get, on one stateful imposter:

```sh
B=http://localhost:9200
curl "$B/api/v1/users?filter=status%20eq%20%22ACTIVE%22"            # SCIM filter
curl -X POST "$B/api/v1/users" -d '{"profile":{"login":"x@y.com"}}' # 201, id minted
curl "$B/api/v1/users?limit=50"                                     # paginated; Link: rel="next"
curl "$B/api/v1/users/missing"                                      # 404 Okta error envelope
curl -X PUT "$B/api/v1/groups/00g2/users/00u1"                      # 204, assign to a group (idempotent)
curl "$B/api/v1/groups/00g2/users"                                  # the group's users, hydrated
curl "$B/api/v1/users/00u1/groups"                                  # the user's groups, hydrated
curl "$B/oauth2/v1/keys"                                            # JWKS (verify the twin's JWTs)
curl "$B/.well-known/openid-configuration"                         # OIDC discovery
# OIDC on the same twin: authorize -> token (signed RS256 access + id JWTs) -> userinfo
```

The token endpoint issues real RS256 JWTs that verify against the twin's published JWKS,
so third-party validators (`jose`, `jsonwebtoken`, …) accept them. The keypair is generated
once per `kit okta` invocation; pass `--signing-key FILE.pem` to pin one across
invocations, or `--issuer URL` to fix the JWT `iss`/`aud` when there's no `Host` header.
The discovery documents always template their host from the request `Host` header.

> **Security:** the private signing key is embedded in the contract JSON and shipped with
> the twin — this is a *mock*; the key is not secret and must never be reused for anything
> real. There is also no client-secret/PKCE/nonce enforcement: the JWT is well-formed and
> verifiable, but the *flow* is still the permissive mock flow.

> OIDC reads the **live** `users` collection: the account picker, the login→uid step, and
> `userinfo` all read the same mutable collection the CRUD ops write — so a user created via
> `POST /api/v1/users` can log in (once `ACTIVE`). The picker and issuance are gated to
> `status eq "ACTIVE"`, matching Okta's refusal of non-ACTIVE users (a freshly-created user
> is `STAGED` until you PATCH it `ACTIVE`).

### Endpoint reference

The full surface, on one stateful imposter. `4xx` misses return the Okta error envelope
(`errorCode`/`errorSummary`); with `--rate-limit` every CRUD op can also return `429` +
`X-Rate-Limit-*`.

**Users** (SCIM CRUD over a live collection)

| Method | Path | Behavior | Status |
|---|---|---|---|
| `POST` | `/api/v1/users` | Create a user (id minted `00u…`; defaults to `STAGED`, request body merged in). | `201` |
| `GET` | `/api/v1/users` | List / filter / paginate (`?filter=` SCIM, `?after=`, `?limit=`; `Link: rel="next"`). | `200` |
| `GET` | `/api/v1/users/{id}` | Get one user. | `200` · `404` |
| `POST` | `/api/v1/users/{id}` | Merge-patch a user (e.g. `{"status":"ACTIVE"}` to activate). | `200` · `404` |
| `DELETE` | `/api/v1/users/{id}` | Delete a user. | `204` · `404` |

**Groups** (same CRUD shape)

| Method | Path | Behavior | Status |
|---|---|---|---|
| `POST` | `/api/v1/groups` | Create a group (id minted `00g…`; `type: OKTA_GROUP`). | `201` |
| `GET` | `/api/v1/groups` | List / filter / paginate groups. | `200` |
| `GET` | `/api/v1/groups/{id}` | Get one group. | `200` · `404` |
| `POST` | `/api/v1/groups/{id}` | Merge-patch a group. | `200` · `404` |
| `DELETE` | `/api/v1/groups/{id}` | Delete a group. | `204` · `404` |

**Group membership** (a many-to-many relation)

| Method | Path | Behavior | Status |
|---|---|---|---|
| `PUT` | `/api/v1/groups/{gid}/users/{uid}` | Assign a user to a group (idempotent; both ids must exist). | `204` · `404` |
| `DELETE` | `/api/v1/groups/{gid}/users/{uid}` | Unassign (miss-tolerant — a non-member is still `204`). | `204` · `404` |
| `GET` | `/api/v1/groups/{gid}/users` | List a group's users, hydrated + paginated. | `200` · `404` |
| `GET` | `/api/v1/users/{uid}/groups` | List a user's groups, hydrated + paginated. | `200` · `404` |

**OIDC** (over the live `users` collection; issues signed RS256 JWTs)

| Method | Path | Behavior | Status |
|---|---|---|---|
| `GET` | `/oauth2/v1/authorize` | No `?login`: HTML account picker of `ACTIVE` users (dynamic, reflects runtime creates). | `200` |
| `GET` | `/oauth2/v1/authorize?login=&uid=&…` | With `?login`: mint a single-use code and redirect to `redirect_uri` (gated to `ACTIVE`). | `302` · `403` |
| `POST` | `/oauth2/v1/token` | Exchange the code for a signed `access_token` + `id_token` (RS256). | `200` · `400` |
| `GET` | `/oauth2/v1/userinfo` | Bearer access token → OIDC claims shaped from the live user row. | `200` · `401` |

**Discovery & keys** (static; discovery host-templated from the request `Host`)

| Method | Path | Behavior | Status |
|---|---|---|---|
| `GET` | `/oauth2/v1/keys` | JWKS — the RS256 public signing key that verifies the twin's tokens. | `200` |
| `GET` | `/.well-known/openid-configuration` | OIDC discovery document (issuer + endpoints). | `200` |
| `GET` | `/.well-known/oauth-authorization-server` | OAuth authorization-server discovery (same shape). | `200` |

**Control** (built in because the twin carries a [virtual clock](effects.md#the-virtual-clock))

| Method | Path | Behavior | Status |
|---|---|---|---|
| `POST` | `/_mocksys/clock` | Read / advance / set the virtual clock (drives `iat`/`exp`, TTLs, rate-limit windows). Use via `mocksys clock okta/okta [advance 1h \| set <ISO>]`. | `200` |

### Seeding your own accounts

```sh
mocksys kit okta --users my-users.json     # a JSON array of user objects, replacing the defaults
```

## conform — the fidelity loop

`conform` replays a request corpus at the twin and, when you have credentials, at the
**live** service, then diffs the responses. Bodies are canonicalized and `--ignore` keys
masked; with `--headers`, the service's volatile headers are dropped. Server-generated body
fields such as timestamps need `--ignore`. It exits non-zero on any divergence, so
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
| Body | ✓ (JSON canonicalized: keys sorted; `--ignore` keys masked; compact JWTs compared by decoded claims minus `jti`/`iat`/`exp`/`auth_time`/`nbf`) | ✓ |
| Headers | — | ✓ (volatile headers dropped) |

`--ignore k1,k2` masks those JSON keys (recursively) in both bodies before comparing — for
server-generated timestamps and ids. Volatile headers come from the scenario's
[service profile](architecture.md#module-map).

Each `conform` run reposts the imposter, so the twin starts from a fresh, reseeded,
deterministic state.

## Determinism: the virtual clock + rate limits

A twin is deterministic: fixed seed data, fresh state per run, no real network, and minted
ids drawn from a seeded stream (`state.randomSeed`, see
[effects.md](effects.md#state-buckets-and-mint)), so the same requests get the same ids. Time is
deterministic too, via the **virtual clock** — a "now" that only moves when you advance it.
So token expiry, session lifetimes, and rate-limit windows are reproducible:

```sh
mocksys kit okta --rate-limit 100 --token-ttl 3600   # 429s + tokens that expire on the clock
mocksys run okta/okta
# ... obtain a token, confirm it works ...
mocksys clock okta/okta advance 2h                   # the token is now expired (401)
```

- `--token-ttl S` sets the OIDC token lifetime to `S` virtual seconds (`store.ttl` under the
  hood, plus `expires_in` and the JWT `exp`). The `okta` kit defaults to 3600; `oauth2`
  tokens never expire unless a TTL is set. A `lookup` of an expired token misses → `401`.
- `--rate-limit N [--rate-window S]` gives each client `N` requests per `S`-second window
  (default 60); past the budget, `429` + `X-Rate-Limit-*`. Advancing the clock past the
  window resets the budget.

Both compose — the rate-limit window is keyed on the virtual clock. For your own twins,
`rate-limit` is `crud-collection`'s `:rate-limit` option (or a hand-written `limit`), and
the clock is `state.clock` or any clock-using verb. See
[effects.md](effects.md#the-virtual-clock) for the primitives and `mocksys clock` for
driving time.

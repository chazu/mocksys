# The effect language (stateful mocks)

A static operation always returns the same thing. A **stateful** operation *remembers*:
mint a code here, consume it there; create a user, list it back; bind a bearer token to a
user. You express this declaratively with an operation's `effect` — and mocksys compiles
it to a generated, dependency-free JavaScript injection that runs over a persistent
per-imposter `state` object. **You never write JavaScript.**

```yaml
operations:
  - id: get-user
    request: { method: GET, path: "/api/v1/users/{id}" }
    effect:
      bind: { id: { pathRegex: "/users/([^/]+)$" } }
      get:  { collection: users, key: "{id}", miss: { status: 404, json: { errorCode: "E0000007" } } }
```

There are two families of verbs:

- **Handshake verbs** operate on flat `state` **buckets** (`string → string`) — an OAuth
  code/token flow.
- **Collection verbs** operate on named **collections** of *objects* with CRUD, filtering
  and pagination — a REST resource like Okta users.

An operation ends **either** in a `respond` (handshake) **or** in one terminal collection
verb (`create`/`get`/`update`/`remove`/`list`).

## Execution model

Within one request the generated function runs in **read-then-write** order, regardless of
the order you write the keys:

1. `bind` — pull request values into variables.
2. `consume` / `lookup` — read (and for `consume`, delete) bucket entries. A miss
   short-circuits with the verb's `miss` response.
3. `resolve` — find a seed-array item. A miss short-circuits.
4. `mint` — generate fresh random ids (after reads, so a minted id can't be clobbered).
5. **Terminal:** either a collection verb (`create`/`get`/`update`/`remove`/`list`) which
   forms and returns its own response, **or** `store` (write a bucket) followed by
   `respond`.

`state` persists for the imposter's lifetime. Reposting the imposter (every `run`/`conform`)
starts fresh and reseeds.

## Variables and interpolation

`bind`/`mint`/`consume`/`lookup`/`resolve` write into named variables. Anywhere a spec
takes a template string, `{name}` (single brace) is replaced with that variable's value.
Object templates (e.g. a `create` body, a `respond` json) are interpolated deeply.

```yaml
mint:    { code: { prefix: "code_" } }
respond: { status: 302, headers: { Location: "{ruri}?code={code}&state={state}" } }
```

> `{name}` (effect-time) is **not** the same as `{{request.x}}` (static response
> templating, [contracts.md](contracts.md)). In an `effect`, pull request values with
> `bind`, then reference them as `{name}`.

## `bind` — request → variables

`bind` is a map of `varName → source`:

```yaml
bind:
  login: { query: login }                       # ?login=...
  scope: { body: scope }                         # JSON field, or form field, named "scope"
  incoming: { bodyJson: true }                   # the whole parsed JSON body as an object
  id:    { pathRegex: "/users/([^/]+)$" }        # first capture group of a regex on the path
  auth:  { header: authorization, bearer: true } # a header; bearer strips "Bearer "/"token "
  tier:  { const: "free" }                       # a literal constant
```

| Source | Pulls |
|---|---|
| `query: <k>` | query parameter `k` (or `""`). |
| `body: <k>` | request body field `k` — parsed JSON object field, else `application/x-www-form-urlencoded` field. |
| `bodyJson: true` | the entire request body parsed as a JSON object (`{}` if absent/unparseable). For `create`/`update` merges. |
| `header: <k>` | header `k`, case-insensitive. Add `bearer: true` to strip a `Bearer `/`token ` prefix. |
| `pathRegex: <re>` | the first capture group of `re` matched against the request path. The path-segment idiom is `"/users/([^/]+)$"`. |
| `now: true` | the current [virtual time](#the-virtual-clock) as epoch seconds. `now: { add: N }` offsets it; `now: { iso: true }` formats it as an ISO string. |
| `const: <v>` | a literal value. |

## State buckets and `mint`

Flat buckets (`string → string`) are declared implicitly — any bucket named by a `store`/
`consume`/`lookup` is auto-initialized. `mint` makes a random 24-hex-char id with an
optional prefix:

```yaml
mint: { token: { prefix: "mockoauth_" } }   # -> token = "mockoauth_<24 hex>"
```

## Handshake verbs

### `store` — write a bucket entry

```yaml
store: { bucket: codes, key: "{code}", value: "{login}" }   # or  valueVar: <var>  to store a var verbatim
store: { bucket: tokens, key: "{token}", value: "{login}", ttl: 3600 }   # expires after 3600 virtual seconds
```

With `ttl` (seconds), the entry carries an expiry on the [virtual clock](#the-virtual-clock);
once the clock passes it, `consume`/`lookup` treat the entry as a miss (an expiring code or
token).

### `consume` / `lookup` — read a bucket entry

Both read `bucket[key]` into `as`. `consume` also **deletes** it (single-use). A miss — or
an entry that has **expired** (TTL) — returns the `miss` response and stops.

```yaml
consume: { bucket: codes,  key: "{code}", as: login, miss: { status: 400, json: { error: "bad_verification_code" } } }
lookup:  { bucket: tokens, key: "{auth}", as: login, miss: { status: 401, json: { message: "Bad credentials" } } }
```

### `resolve` — find a seed item

Find the first item in a `seed` array whose `by` field equals `eq`, into `as`. Miss
short-circuits.

```yaml
seed: { users: [ { id: 1, login: octocat, email: o@x } ] }   # top-level
# ...
resolve: { seed: users, by: login, eq: "{login}", as: user, miss: { status: 401, json: { message: "Bad credentials" } } }
```

### `respond` — the success response

```yaml
respond:
  status: 200                       # default 200
  headers: { X-Foo: "{bar}" }       # interpolated; Content-Type defaults to application/json when json/jsonVar present
  json:    { access_token: "{token}", scope: "{scope}" }   # a template object, deeply interpolated
  # jsonVar: user                   # OR serialize a whole variable as the body
  # text:   "{login}"               # OR an interpolated text body
```

A `miss` on `consume`/`lookup`/`resolve` (and on collection verbs) takes the same shape as
`respond`.

### Worked example — the OAuth handshake

```yaml
seed: { users: [ { id: 1001, login: octocat, email: octocat@x } ] }
operations:
  - id: authorize
    request: { method: GET, path: "/authorize", query: { login: { present: true } } }
    effect:
      bind:    { login: { query: login }, state: { query: state }, ruri: { query: redirect_uri } }
      mint:    { code: { prefix: "code_" } }
      store:   { bucket: codes, key: "{code}", value: "{login}" }
      respond: { status: 302, headers: { Location: "{ruri}?code={code}&state={state}" } }
  - id: token
    request: { method: POST, path: "/token" }
    effect:
      bind:    { code: { body: code }, scope: { body: scope } }
      consume: { bucket: codes, key: "{code}", as: login, miss: { status: 400, json: { error: "bad_verification_code" } } }
      mint:    { token: { prefix: "tok_" } }
      store:   { bucket: tokens, key: "{token}", value: "{login}" }
      respond: { status: 200, json: { access_token: "{token}", token_type: bearer, scope: "{scope}" } }
  - id: userinfo
    request: { method: GET, path: "/userinfo" }
    effect:
      bind:    { auth: { header: authorization, bearer: true } }
      lookup:  { bucket: tokens, key: "{auth}", as: login, miss: { status: 401, json: { message: "Bad credentials" } } }
      resolve: { seed: users, by: login, eq: "{login}", as: user, miss: { status: 401, json: { message: "Bad credentials" } } }
      respond: { status: 200, jsonVar: user }
```

## `limit` — rate limiting

A `limit` runs early (right after `bind`) and short-circuits with `429` +
`X-Rate-Limit-*` headers when a per-key request budget for the current window is exceeded.
The window is keyed on the [virtual clock](#the-virtual-clock), so the counter resets when
the clock crosses a window boundary — deterministic, no wall-clock dependence.

```yaml
effect:
  bind:  { client: { header: authorization, bearer: true } }   # what to limit on
  limit:
    bucket: rl
    key:    "{client}"                 # per-client budget (use a constant for a global one)
    max:    100                        # requests per window
    window: 60                         # window seconds (virtual clock)
    miss:   { status: 429, json: { errorCode: "E0000047", errorSummary: "rate limit exceeded" } }
  # ... then the operation's real verb (get/list/respond/...) ...
```

When the budget is exceeded the `miss` response is returned with
`X-Rate-Limit-Limit`/`-Remaining`/`-Reset` headers added. A `limit` is *not* terminal — on
pass-through the operation continues to its real response.

## The virtual clock

A twin can carry a **virtual clock**: a "now" that is *frozen* until you advance it, so any
time-based behavior (TTL expiry, rate-limit windows, `iat`/`exp` claims) is perfectly
deterministic. Enable it explicitly, or it turns on automatically whenever an op uses
`store.ttl`, `limit`, or a `now` bind.

```yaml
state:
  clock: { start: "2024-01-01T00:00:00Z" }   # epoch number or ISO string; default a fixed epoch
```

Read or move it on the **running** twin (this never reposts/resets it):

```sh
mocksys clock <twin>                 # show the current virtual time
mocksys clock <twin> advance 1h      # 1h / 30m / 45s / 2d / <seconds>
mocksys clock <twin> set 2024-06-01T00:00:00Z   # an epoch or ISO timestamp
```

Under the hood mocksys adds a built-in control endpoint at `POST /_mocksys/clock` to any
clock-using twin; the `clock` command drives it. Effects read the clock via the `now` bind
and via `store.ttl`/`limit`.

## Collections (CRUD resources)

A **collection** is a named, seedable set of *objects* keyed by an id field, with
create/list/get/update/delete, SCIM-ish filtering, and cursor pagination. Declare it once
under `state.collections`, seeded from a top-level `seed` array:

```yaml
seed:
  users:
    - { id: "00u1", status: ACTIVE, profile: { login: "a@x", email: "a@x" } }
state:
  collections:
    users: { idField: id, seed: users }      # seed names the array in `seed`
```

Each collection verb is **terminal** — it forms and returns its own response — so a CRUD
endpoint is one verb.

### `create`

Insert a row. The id is whatever the resulting object's `idField` holds, so mint it in
`body` (a client-sent id can't override it). `merge` shallow-merges named variable objects
*under* the `body` template (so `body` wins).

```yaml
effect:
  bind:   { incoming: { bodyJson: true } }
  mint:   { newid: { prefix: "00u" } }
  create:
    collection: users
    merge: [incoming]                  # the request body object
    body:  { id: "{newid}", status: "STAGED" }   # template; id minted, status defaulted
    status: 201                        # default 201
    # as: created                      # optionally bind the created object
```

### `get`

```yaml
get: { collection: users, key: "{id}", as: user, miss: { status: 404, json: { errorCode: "E0000007" } }, status: 200 }
```

### `update`

Read the row, merge a patch, write it back, return it. `mergeVar` is a bound object (e.g.
`incoming`); `patch` is a template object. The merge is one level deep (a nested `profile`
object is merged, not replaced).

```yaml
update: { collection: users, key: "{id}", mergeVar: incoming, as: user, miss: { ... }, status: 200 }
```

### `remove`

```yaml
remove: { collection: users, key: "{id}", miss: { status: 404 }, status: 204 }   # 204, empty body
```

### `list` — filter + paginate

Returns the (filtered, paged) array. `filter` is a SCIM-ish expression; `after`+`limit`
drive cursor pagination; `link` is an optional `Link`-header template emitted only when a
next page exists.

```yaml
effect:
  bind: { q: { query: filter }, after: { query: after }, lim: { query: limit } }
  list:
    collection: users
    filter: "{q}"
    after:  "{after}"
    limit:  "{lim}"                    # parsed to int; default 200
    link:   '</api/v1/users?after={next}>; rel="next"'
```

#### The filter grammar

Bounded and SCIM-ish — enough for the common provider shape `filter=field op "value"`:

- **Operators:** `eq` (equals), `sw` (starts-with), `co` (contains), `pr` (present /
  non-empty — no value).
- **Clauses** are joined by ` and ` / ` or ` (no parenthesized groups).
- **Field paths** are dotted (`profile.email`).
- **Values** are double-quoted (`"ACTIVE"`); an empty value `""` is allowed; a missing
  value for `eq`/`sw`/`co` matches nothing (treated as malformed, never errors).

```
status eq "ACTIVE"
profile.email sw "alice"
status eq "ACTIVE" and profile.lastName eq "Lee"
profile.firstName eq "Alice" or profile.firstName eq "Bob"
profile.login pr
```

#### Pagination

Items are sorted by id. `limit` (default 200) caps the page; `after=<id>` returns items
after that id. `list.link` interpolates `{next}` (the last id of the page) into a `Link`
header, emitted only when more items remain. The cursor in the response and the `after`
query parameter are the same id value.

## The generated injection (under the hood)

You don't need this to use effects, but for the curious: `inject/gen` emits one
self-contained JS function per stateful operation. A hand-written, version-controlled
**prelude** carries all the heavy logic (the SCIM filter parser, the `Coll`
create/get/del/list helper, JSON/form body parsing, deep interpolation, one-level merge);
the per-operation code is thin glue that binds, reads/writes, and returns. The seed and
collection declarations are embedded as literals, so the function is dependency-free.
Collections are seeded once into `state` (idempotent), so every operation on the imposter
sees the same data. Stateful imposters require Mountebank's `--allowInjection`, which
mocksys turns on automatically (`MOCKSYS_NO_INJECTION=1` opts out).

## Building twins this way

You rarely author a whole twin by hand — a **kit** composes reusable **genes**
(`crud-collection`, `oidc-handshake`, ...) into one. See [twins.md](twins.md). The genes
*are* these effects, parameterized.

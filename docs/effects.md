# The effect language (stateful mocks)

A static operation always returns the same thing. A **stateful** operation *remembers*:
mint a code here, consume it there; create a user, list it back; bind a bearer token to a
user. You express this declaratively with an operation's `effect`, a map of verbs that the
daemon evaluates over a persistent per-imposter `state` object. **You never write JavaScript**, unless a rule needs logic
the verbs cannot express, such as checking an HMAC signature. For that, write a
[custom verb](#custom-verbs-call).

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

An operation ends **either** in a `respond` (handshake) **or** in exactly one terminal
collection verb (`create`/`get`/`update`/`remove`/`list`, plus the relation/render verbs
`link`/`unlink`/`listJoin`/`render`). Read-phase verbs that populate a variable and then
fall through to a `respond` — `resolve`, `find`, `sign`, `call` — are *not* terminal, so an op may
use several. The relation, lookup, signing, and render verbs are covered in
[Joins, lookups, signing, rendering](#joins-lookups-signing-rendering-extended-verbs).

## Execution model

Within one request the daemon runs an effect in **read-then-write** order, regardless of
the order you write the keys:

1. `bind` — pull request values into variables.
2. `limit` — the rate-limit guard. Over budget short-circuits with `429`.
3. `consume` / `lookup` — read (and for `consume`, delete) bucket entries. A miss
   short-circuits with the verb's `miss` response.
4. `resolve` — find a seed-array item. A miss short-circuits.
5. `find` — read collection rows, in list order. A miss short-circuits.
6. `call` runs [custom verbs](#custom-verbs-call), in list order. A verb can end the
   request with its own response.
7. `mint` — generate fresh ids (after reads, so a minted id can't be clobbered).
8. `sign` — build JWTs (after `mint`, so a minted `jti` can be signed).
9. **Terminal:** either a collection verb (`create`/`get`/`update`/`remove`/`list`/
   `link`/`unlink`/`listJoin`/`render`) which forms and returns its own response, **or**
   `store` (write a bucket) followed by `respond`.

`state` persists for the imposter's lifetime. Reposting the imposter (every `run`/`conform`)
starts fresh and reseeds.

## Variables and interpolation

`bind`/`mint`/`consume`/`lookup`/`resolve`/`find`/`sign`/`call` write into named variables. Anywhere a spec
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
| `baseUrl: true` | the twin's own origin — `http://{Host}` when a `Host` header is present, else the configured issuer. For `iss`/`aud` and self-referential URLs. |
| `const: <v>` | a literal value. |

## State buckets and `mint`

Flat buckets (`string → string`) are declared implicitly — any bucket named by a `store`/
`consume`/`lookup` is auto-initialized. `mint` makes a 24-hex-char id with an optional
prefix:

```yaml
mint: { token: { prefix: "mockoauth_" } }   # -> token = "mockoauth_<24 hex>"
```

Ids are drawn from a seeded pseudo-random stream kept in the imposter's shared state, not
from `Math.random`. A freshly started twin mints the same ids for the same sequence of
requests on every run. Set `state.randomSeed` (a number) to pick a different stream:

```yaml
state:
  randomSeed: 42
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

Every response carries `X-Rate-Limit-Limit`/`-Remaining`/`-Reset` (the `Remaining` count
decrements as the budget is spent); when the budget is exceeded the `miss` response is
returned with `Remaining: 0`. A `limit` is *not* terminal — on pass-through the operation
continues to its real response, which inherits the budget headers.

## The virtual clock

A twin can carry a **virtual clock**: a "now" that is *frozen* until you advance it, so any
time-based behavior (TTL expiry, rate-limit windows, `iat`/`exp` claims) is perfectly
deterministic. Enable it explicitly, or it turns on automatically whenever an op uses
`store.ttl`, `limit`, `sign`, or a `now` bind.

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

Returns the (filtered, paged) array. `filter` is a SCIM filter expression; `after`+`limit`
drive cursor pagination; `link` is an optional `Link`-header template emitted only when a
next page exists; `invalid` is the response for a malformed filter.

```yaml
effect:
  bind: { q: { query: filter }, after: { query: after }, lim: { query: limit } }
  list:
    collection: users
    filter: "{q}"
    after:  "{after}"
    limit:  "{lim}"                    # parsed to int; default 200
    link:   '</api/v1/users?after={next}>; rel="next"'
    invalid: { status: 400, json: { errorCode: E0000031, errorSummary: "Invalid search criteria." } }
```

#### The filter grammar

The filter language of SCIM ([RFC 7644 §3.4.2.2](https://www.rfc-editor.org/rfc/rfc7644#section-3.4.2.2)),
without complex attribute filters (`emails[type eq "work"]`):

- **Comparisons:** `eq` `ne` `co` (contains) `sw` (starts with) `ew` (ends with) `gt` `ge`
  `lt` `le`, and `pr` (present and non-empty; takes no value). Operators and keywords are
  case-insensitive.
- **Ordering** (`gt`/`ge`/`lt`/`le`) compares numerically when both sides are numbers,
  otherwise as strings. ISO-8601 timestamps therefore order correctly, which is what
  incremental sync (`lastUpdated gt "…"`) relies on.
- **Logic:** `and` binds tighter than `or`; `not ( … )` negates; parentheses group.
- **Field paths** are dotted (`profile.email`). A missing field matches nothing, except
  under `ne`.
- **Values** are JSON literals: double-quoted strings (with `\"` escapes, so a value may
  contain `and` or `or`), numbers, `true`, `false`, `null`.

```
status eq "ACTIVE"
profile.email sw "alice"
profile.lastName eq "Smith and Sons"
lastUpdated gt "2024-01-01T00:00:00.000Z"
status eq "ACTIVE" and (profile.lastName eq "Lee" or profile.lastName eq "Ng")
not (status eq "DEPROVISIONED")
profile.login pr
```

A malformed filter (unknown operator, unbalanced parentheses, an unterminated string, a
missing value) never yields an empty page. `list`, `find` and `render` answer with their
`invalid` response, which defaults to a SCIM `400`:

```json
{"schemas": ["urn:ietf:params:scim:api:messages:2.0:Error"],
 "scimType": "invalidFilter", "detail": "Invalid filter: unknown operator zz", "status": "400"}
```

`{filterError}` interpolates the parser's message into a custom `invalid` body. The
`crud-collection` gene uses Okta's `E0000031` envelope.

#### Pagination

Items are sorted by id. `limit` (default 200) caps the page; `after=<id>` returns items
after that id. `list.link` interpolates `{next}` (the last id of the page) into a `Link`
header, emitted only when more items remain. The cursor in the response and the `after`
query parameter are the same id value.

## Joins, lookups, signing, rendering (extended verbs)

The verbs above cover a single-resource CRUD twin and an OAuth handshake. Four more verbs
plus one interpolation upgrade cover *relations between* resources (group membership), a
non-terminal collection read (used both as a foreign-key guard and to shape a projection),
and issuing real signed JWTs. Like everything else, they are declarative — you never write
JavaScript.

### Dotted paths in templates

`{name}` now also resolves **dotted paths** into a bound object: `{user.profile.email}`
walks `user → profile → email` (missing links interpolate to `""`). This works anywhere a
template string is interpolated (`respond.json`, `create.body`, a `Location` header). It is
what lets you *shape* a response from a bound row without a dedicated projection verb:

```yaml
respond:
  json:
    sub:   "{user.id}"                    # dotted read from the bound `user` object
    email: "{user.profile.email}"
    name:  "{user.profile.firstName}"
```

> Interpolation still yields **strings**. For non-string claim values (numbers, arrays) in
> a JWT, use `sign`'s typed claim directives (below), not a template string.

### `find` — read a collection row into a variable (non-terminal)

`find` reads one row from a collection into a variable and continues; a miss short-circuits
with `miss` (like `get`, but **not** terminal — the op goes on to `respond`/`sign`/a
terminal verb). It reads either by id (`key`) or by a secondary (dotted) `field`:

```yaml
find: { collection: users, key: "{uid}",              as: user, miss: { status: 401, json: { errorCode: "E0000007" } } }
find: { collection: users, field: profile.login, eq: "{login}", as: user, miss: { status: 404 } }
# optional: pick a single sub-field instead of the whole row
find: { collection: users, field: profile.login, eq: "{login}", as: uid, pick: id, miss: { status: 404 } }
# optional: require the located row to also satisfy a SCIM filter, else miss
find: { collection: users, key: "{uid}", filter: 'status eq "ACTIVE"', as: user, miss: { status: 403 } }
```

An optional `filter` (the same grammar as `list`) constrains the match: the row must
satisfy the key/field lookup **and** the filter, otherwise it's a miss. This is how an
op gates on a row's state — e.g. only issue a token to a `status eq "ACTIVE"` user.

`find` runs in the read phase (after `bind`/`consume`/`lookup`, before `mint` and the
terminal), so several `find`s compose. Field lookups are a linear scan (small collections);
`key` lookups are O(1).

**The foreign-key guard idiom.** Because a `find` miss returns `miss` and otherwise falls
through, a `find` you don't consume *is* a presence check. To require that a group and a
user both exist before writing a membership, `find` each with a `404` miss:

```yaml
find: { collection: groups, key: "{gid}", as: _g, miss: { status: 404, json: { errorCode: "E0000007" } } }
find: { collection: users,  key: "{uid}", as: _u, miss: { status: 404, json: { errorCode: "E0000007" } } }
# ... reaching here means both exist; the bound _g/_u may be ignored ...
```

### `link` / `unlink` — set membership (terminal)

A **membership** is a many-to-many relation modeled as its own collection whose rows are
`{ id: "{a}:{b}", … }`. `link` inserts one idempotently; `unlink` removes one and is
**miss-tolerant**. Both return an empty body (default `204`) and take the composite id from
bound vars — they do *not* mint an id (unlike `create`) and `unlink` does *not* 404 on a
non-member (unlike `remove`).

```yaml
# PUT /api/v1/groups/{gid}/users/{uid}  — assign (idempotent)
effect:
  bind: { gid: { pathRegex: "/groups/([^/]+)/users/[^/]+$" }, uid: { pathRegex: "/users/([^/]+)$" } }
  find: [ { collection: groups, key: "{gid}", as: _g, miss: { status: 404, json: { errorCode: "E0000007" } } },
          { collection: users,  key: "{uid}", as: _u, miss: { status: 404, json: { errorCode: "E0000007" } } } ]
  link: { collection: memberships, id: "{gid}:{uid}", row: { gid: "{gid}", uid: "{uid}" }, status: 204 }

# DELETE /api/v1/groups/{gid}/users/{uid} — unassign (204 even if not a member)
effect:
  bind:   { gid: {...}, uid: {...} }
  find:   [ {groups…}, {users…} ]          # 404 only for a bad id
  unlink: { collection: memberships, id: "{gid}:{uid}", status: 204 }
```

`find` accepts a single map or a list of maps, so the two guards read as one key.
`link` re-inserting an existing row is a no-op (the composite id dedupes) → idempotent.

### `listJoin` — filter a relation, hydrate the other side, paginate (terminal)

`listJoin` lists a membership collection filtered to one side, replaces each row with the
*hydrated* object from a target collection, and cursor-paginates over the returned objects'
ids (dropping rows whose target no longer exists). It reuses the same `after`/`limit`/`link`
cursor contract as `list`, but the cursor ranges over the **hydrated ids**, not the
membership rows:

```yaml
# GET /api/v1/groups/{gid}/users  → [User]
effect:
  bind: { gid: { pathRegex: "/groups/([^/]+)/users$" }, after: { query: after }, lim: { query: limit } }
  find: { collection: groups, key: "{gid}", as: _g, miss: { status: 404, json: { errorCode: "E0000007" } } }
  listJoin:
    via:      memberships        # the relation collection
    byField:  gid                # keep rows where row.gid == byValue
    byValue:  "{gid}"
    hydrate:  users              # target collection
    hydrateVia: uid              # membership field naming the target id
    after: "{after}"
    limit: "{lim}"
    link:  '</api/v1/groups/{gid}/users?after={next}>; rel="next"'
```

Swap `byField`/`hydrate`/`hydrateVia` to walk the relation the other way
(`GET /api/v1/users/{uid}/groups` → `[Group]`).

### `sign` — issue a signed JWT (non-terminal)

`sign` builds a compact **RS256** JWT into a variable, which a later `respond` returns.
Claims are **typed** (a template string alone can only make string claims):

```yaml
sign:
  as:     access_token                 # the compact JWT lands in this var
  key:    "<PEM private key literal>"  # supplied by the kit/gene (see below)
  header: { alg: RS256, kid: "<kid>", typ: JWT }
  claims:
    iss: { baseUrl: true }             # issuer string (see the baseUrl bind source)
    aud: { baseUrl: true }
    sub: "{login}"                     # string claim (interp; dotted paths allowed)
    cid: "{client_id}"
    scp: { split: "{scope}", on: " " } # ARRAY claim  → ["openid","email"]
    iat: { now: true }                 # NUMBER claim (epoch seconds, virtual clock)
    exp: { now: { add: 3600 } }        # NUMBER claim (now + 3600s)
    amr: ["pwd"]                        # literal (used as-is)
```

Claim value forms: a **string** (interpolated, dotted paths allowed) → string; `{now: …}`
→ number (or ISO string with `{now: {iso: true}}`); `{split: <str>, on: <sep>}` → array;
`{var: <name>}` → the bound variable verbatim (keeps its JS type); a literal
number/bool/array → itself.

Signing uses Node's built-in `crypto` in the daemon.
`iat`/`exp` read the [virtual clock](#the-virtual-clock); a twin that signs is treated as
clock-using automatically (so `iat`/`exp` are seeded and deterministic even without a
`--token-ttl`). The signing key and its published JWKS are generated once at kit-build time
and embedded as literals, so the same contract always verifies against the same JWKS.

A **`baseUrl`** bind source gives an effect the twin's own origin (from the request
`Host`, else a configured issuer), for `iss`/`aud` and any self-referential URL:

```yaml
bind: { origin: { baseUrl: true } }    # e.g. "http://localhost:9200"
```

### `render` — an HTML page from live collection state (terminal)

`render` lists a collection (with the same `filter`) and renders each row through an HTML
`row` template into a full page, returning `text/html` — the one collection response that
is not JSON. It exists so a human-facing page (e.g. an OAuth account picker) reflects
*runtime* state, not a snapshot baked at build time:

```yaml
effect:
  render:
    collection: users
    filter: 'status eq "ACTIVE"'       # optional (same grammar as list)
    row:  '<a href="/authorize?login={profile.login}&uid={id}">{profile.login}</a>'
    wrap: '<!doctype html><h2>Sign in</h2>{rows}'   # {rows} = concatenated rendered rows
    status: 200                         # default 200; Content-Type: text/html
```

`row` interpolates each listed row with dotted paths; `{rows}` in `wrap` is the joined
output. `render` is terminal and returns `text/html` (the one collection verb that isn't
JSON).

### Adding a verb

Every verb is one entry in the `verbs` table in `src/mocksys/effect.cljs`: its name,
`:check` (its field checks, which both `validate` and the daemon's post run), `:run`, and
for a terminal verb `:terminal`. The table's order is the order a request runs the
verbs. A new verb is that entry and a section in this doc.

## Custom verbs (`call`)

When no verb above expresses a rule, write the rule in JavaScript. A contract lists
CommonJS files under `modules:`, with paths relative to the scenario directory. An effect
runs one of their verbs with `call`.

```yaml
modules: [./verbs.js]
operations:
  - id: receive
    request: { method: POST, path: /webhooks }
    effect:
      bind: { signature: { header: X-Hub-Signature-256 } }
      call:
        verb: verifySignature
        args: { signature: "{signature}", secret: whsec_test }
        as: event
      mint: { delivery: { prefix: dlv_ } }
      create: { collection: deliveries, merge: [event], body: { id: "{delivery}" }, status: 202 }
```

```js
// verbs.js, next to contract.yaml
const crypto = require("node:crypto");
module.exports = {
  verbs: {
    verifySignature({ request, args, respond }) {
      const expected = "sha256=" +
        crypto.createHmac("sha256", args.secret).update(request.body).digest("hex");
      if (args.signature !== expected) {
        return respond({ status: 401, json: { error: "signature does not match" } });
      }
      return JSON.parse(request.body);
    },
  },
};
```

The complete receiver, with a constant-time comparison and a `GET /deliveries` to read
back what it accepted, is in [`examples/webhooks/`](../examples/webhooks/).

### The `call` spec

`call` is one map, or a list of maps that run in order. A later call sees the variables
an earlier one bound.

| Key | Meaning |
|---|---|
| `verb` | Required. The name of a function in a module's `verbs`. |
| `args` | Optional object, passed as `ctx.args`. It interpolates deeply like a `respond` json, so a `"{name}"` value arrives as a string. Numbers and booleans written in the contract stay as they are. |
| `as` | Optional. The variable that receives the verb's return value. It cannot start with `__` or be a key of `Object.prototype` (`constructor`, `toString`, `__proto__` and so on) or `prototype`. |

### The verb

A verb is a function that takes one argument, `ctx`:

| Field | Value |
|---|---|
| `request` | `{ method, path, query, headers, body }`. `body` is the raw request text, so a signature check hashes exactly the bytes the client sent. Header names keep the client's case. |
| `state` | The imposter's shared state object. It lasts until the scenario is run again. Keep your own keys on it. Keys that start with `__` belong to mocksys. It has no prototype, so test a key with `Object.hasOwn(ctx.state, k)` or `k in ctx.state`, not `ctx.state.hasOwnProperty(k)`. |
| `vars` | A copy of the variables bound so far, with their types. A row bound by `find` is an object here, where `args` would have flattened it to a string. Variables that start with `__` belong to mocksys and are left out. |
| `args` | The interpolated `args`. |
| `now` | The [virtual clock](#the-virtual-clock), in epoch seconds. A contract that uses `call` has the clock on, so `mocksys clock` moves it. |
| `respond` | A function that builds a response. Return what it returns to end the request. |

A verb ends the request only by returning `ctx.respond(spec)`. Any other return value,
including a plain object with a `respond` key, is bound to `as`, and the effect
continues. Without `as`, the value is dropped. Because `call` runs before `mint` and
the terminal verb, a request that a verb ends creates, updates and stores nothing.

The spec takes these keys:

| Key | Meaning |
|---|---|
| `status` | Optional. An HTTP status code from 100 to 599. The default is 200. |
| `headers` | Optional. An object of response headers. |
| `json` | A value to send as JSON. It gets `Content-Type: application/json` unless `headers` names a content type. |
| `text` | A string to send as it is, with no content type unless `headers` names one. |
| `body` | A string to send as it is. Any other value is sent as JSON, with no content type. |

Give at most one of `json`, `text` and `body`. With none, the body is empty. A spec that
is not an object, has another key, sets `json` to `undefined`, has a status outside
100 to 599, or has a `json` that does not serialize (a circular object) fails the
request with a 500 that names the verb:

```json
{ "errors": [{ "code": "response error", "message": "verb 'verifySignature': ctx.respond got unknown key(s) jsn (use status, headers, json, text or body)" }] }
```

### Synchronous verbs only

A verb must return its result directly. A verb that returns a Promise, including any
`async` function, fails the request. The reason is shared state: mocksys runs a
request's effect as one uninterrupted step, so no other request can read or write
`state` partway through it. An awaited verb would break that guarantee. Node's
`crypto`, `Buffer` and `URL` cover the usual signing and parsing work synchronously.

A promise that a verb starts and does not return, or an exception thrown later from a
timer, does not fail the request. The daemon writes it with its stack to
`$MOCKSYS_HOME/daemon.log` and keeps serving. Look there when a verb seems to do
nothing.

### Deterministic verbs

A twin mints the same ids for the same request sequence on every run, and its clock
starts at a fixed time. A verb keeps that guarantee only if it reads time from
`ctx.now`. `Date.now()`, `new Date()` and `Math.random()` read the machine, so a verb
that calls them answers differently on each run. Derive any value that must vary from
`ctx.now`, `ctx.args` or the request.

### When a verb fails

The request answers 500, and the message names the verb:

```json
{ "errors": [{ "code": "response error", "message": "verb 'verifySignature' threw: <the error's message>" }] }
```

The same 500 reports a verb that returned a Promise (`verb 'x' returned a Promise; verbs
must be synchronous`) and an invalid `ctx.respond` spec. Return `ctx.respond(...)` for an
error your API defines, such as the 401 above. Let a throw mean a bug in the verb.

### Modules

- **Format.** A module is CommonJS: `module.exports = { verbs: { name(ctx) { ... } } }`.
  ESM `export` syntax does not load. `require` inside a module resolves relative to the
  module's own file, and Node built-ins such as `node:crypto` are available.
- **Location.** A module must be a file inside the scenario directory, after following
  symlinks. `validate` and `compile` reject a path outside it. The scenario directory is
  what `pack`, `publish` and `push` carry, so a module outside it would not travel with
  the scenario.
- **When module code runs.** Only the daemon runs a module, and only when `mocksys run`
  posts the scenario to it. `validate`, `compile`, `pack`, `unpack` and the authoring
  commands never load a module. The daemon reads and runs each module file again on
  every post. It never uses the require cache for that file, so an edited module applies
  on the next `mocksys run`. Files that a module itself `require`s use Node's normal
  cache; after editing one of those, restart the daemon with `mocksys daemon stop`.
  `run --watch` reloads when `contract.yaml` changes, not when a module changes.
- **Top-level code.** A module's top level runs on every post. Keep it free of side
  effects.
- **Paths.** `imposter.json` records each module relative to the scenario directory,
  and `run` resolves them just before it posts. Entries that name one file, such as
  `./verbs.js` and `verbs.js`, are one module.
- **Checks.** `validate` and `compile` check that each module is a file inside the
  scenario directory and that each `call` is well formed. They print that verbs are
  checked when the scenario runs. `run` then fails, before the mock serves anything,
  with a 400 from the daemon that names the module or verb, if a module does not load,
  exports no `verbs` object, or exports a verb that is not a function. It also fails if
  two modules export one verb name, or if a `call` names a verb that no module exports.
  A load error gives the file and line where the module failed.
- **Trust.** A verb runs inside the daemon with your user's access. Modules are the only
  code a scenario can run: effects are data. `MOCKSYS_NO_INJECTION=1` makes `run`
  refuse a scenario that lists modules, before it compiles or posts anything, so no
  module code runs. Stateful scenarios without modules still run. `unpack` names each
  unpacked scenario that lists modules. Read a module from someone else before you run
  its scenario.

## How the daemon runs an effect (under the hood)

You don't need this to use effects, but for the curious: `lower` copies each operation's
effect into its stub unchanged, as `{effect: {...}}`, and puts what every effect shares
into the imposter's `state` settings once: seeds, collections with their seed rows, the
clock's start, the id stream's seed and the fallback issuer. The daemon checks each
effect when the imposter is posted, so a bad one is a 400 naming the stub and field.

On a request, `effect/run` seeds the collections into `state` if they are not there yet
(so every operation on the imposter sees the same data), then runs the verbs in a fixed
order whatever order the effect lists them in: `bind`, `limit`, `consume`, `lookup`,
`resolve`, `find`, `call`, `mint`, `sign`, then the one terminal verb (a collection verb,
or `store` and `respond`). A read that misses ends the request with its `miss`
response. A `call` reaches the verbs the daemon loaded from the imposter's modules.

## Building twins this way

You rarely author a whole twin by hand. List reusable **genes** (`crud-collection`,
`oidc-handshake`, ...) under a contract's `genes:` ([contracts.md](contracts.md#genes)), or
let a **kit** compose them for you. See [twins.md](twins.md). The genes *are* these effects,
parameterized.

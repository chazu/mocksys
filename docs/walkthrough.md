# A walkthrough: building a digital twin of Okta

This is the friendly tour. We'll stand up a working clone of Okta — users, groups,
SCIM-style filtering, an OAuth/OIDC login flow, rate limits, expiring tokens — and as we
go, explain *what each part is and why it works*. By the end you'll understand the whole
system and be able to build your own twin.

No real Okta account, no credentials, no network. Everything runs locally.

> Prerequisites: a built `mocksys` (`npm run build`) on your `PATH`, or use `node
> bin/mocksys`. Commands print a port; yours may differ.

---

## The one idea behind mocksys

You write a **contract** — a YAML description of an API. mocksys *compiles* it and serves
it as a local mock (using Mountebank under the hood). Your app or test hits the mock
instead of the real service.

A contract operation comes in two flavors:

- **Static** — always returns the same thing (a fixed JSON body, maybe with an error
  variant you can switch to). Great for simple fixtures.
- **Stateful** — *remembers*. Create a user and list it back; mint a login code and
  exchange it for a token; expire that token an hour later. You describe this with a
  declarative `effect`, and mocksys writes the code for you. You never touch JavaScript.

A **digital twin** is just a contract with enough stateful operations to behave like a
whole service. We won't write that contract by hand — a **kit** generates it.

---

## Step 1 — Stand up the twin

```sh
mocksys kit okta --rate-limit 100 --token-ttl 3600 --port 9200
```

```
✦ generated 'okta/okta' from kit 'okta'
  (stateful — served via Mountebank injection, managed automatically by `run`)
  · POST /api/v1/users        · GET /api/v1/users      · GET /api/v1/users/{id}
  · POST /api/v1/users/{id}    · DELETE /api/v1/users/{id}
  · POST /api/v1/groups       · GET /api/v1/groups     · ... groups CRUD ...
  · GET /oauth2/v1/authorize  · POST /oauth2/v1/token  · GET /oauth2/v1/userinfo
```

One command produced ~14 endpoints on one stateful service: users CRUD, groups CRUD, and
an OIDC login flow — with rate limiting (`--rate-limit 100`) and tokens that expire after
an hour (`--token-ttl 3600`). Now run it:

```sh
mocksys run okta/okta --port 9200
#  ▶ running 'okta/okta' at http://localhost:9200
```

Leave it running; we'll point `curl` at `localhost:9200` (call it `$B`).

---

## Step 2 — It's a real resource system, not canned responses

Okta's defining surface is its resource API: users and groups you create, list, filter,
page, update, delete. The twin does all of it, against in-memory state seeded with a few
accounts.

```sh
curl "$B/api/v1/users"                                    # the seeded users
curl "$B/api/v1/users/00u1"                               # one user by id
curl "$B/api/v1/users/nope"                               # 404 — Okta's error envelope
#   {"errorCode":"E0000007","errorSummary":"Not found: Resource not found",...}
```

**Filter** with Okta's SCIM-style syntax — and combine clauses:

```sh
curl "$B/api/v1/users?filter=status eq \"ACTIVE\""
curl "$B/api/v1/users?filter=profile.email sw \"alice\""               # starts-with
curl "$B/api/v1/users?filter=status eq \"ACTIVE\" and profile.lastName eq \"Lee\""
```

**Create** one — the server mints the id (a client-sent `id` is ignored, just like the
real thing), and merges your request body in:

```sh
curl -X POST "$B/api/v1/users" -H 'content-type: application/json' \
     -d '{"profile":{"login":"dana@example.com","email":"dana@example.com"}}'
#   {"profile":{...},"id":"00u9a0…","status":"STAGED"}     <- id minted, status defaulted
```

**Page** through results — `limit` + a cursor, with a `Link: rel="next"` header, exactly
as Okta does:

```sh
curl -i "$B/api/v1/users?limit=2"
#   Link: </api/v1/users?after=00u2>; rel="next"
curl "$B/api/v1/users?limit=2&after=00u2"                 # the next page
```

That's a **collection**: a named, seeded set of objects with create/list/get/update/delete,
filtering and pagination. It's the heart of the twin.

---

## Step 3 — A peek under the hood (you can skip this)

How does `GET /api/v1/users/{id}` work? Here's the contract operation the kit generated —
this is the whole thing:

```yaml
- id: get-user
  request: { method: GET, path: "/api/v1/users/{id}" }
  effect:
    bind: { id: { pathRegex: "/users/([^/]+)$" } }     # pull the id out of the URL
    get:  { collection: users, key: "{id}",            # look it up in the `users` collection
            miss: { status: 404, json: { errorCode: "E0000007" } } }   # ...or 404
```

You declare *what* happens — bind the id, get from the collection, 404 on a miss — and
mocksys generates the JavaScript that runs inside the mock server. The "verbs" (`bind`,
`get`, `create`, `list`, ...) are a small declarative language; the full set is in
[effects.md](effects.md). The point: **you describe behavior, not code.**

---

## Step 4 — The login flow (shared state across endpoints)

Okta is also an identity provider. The twin runs a real OAuth/OIDC handshake where three
endpoints *share state*:

```sh
# 1. authorize as a seeded user -> a 302 redirect carrying a single-use code
LOC=$(curl -s -D - -o /dev/null \
  "$B/oauth2/v1/authorize?redirect_uri=http://app/cb&state=s&login=alice@example.com" \
  | grep -i location)
CODE=$(echo "$LOC" | sed -n 's/.*code=\([^&]*\).*/\1/p' | tr -d '\r')

# 2. exchange the code for a token (the code is single-use — try it twice and the 2nd 400s)
TOK=$(curl -s -X POST "$B/oauth2/v1/token" -d "code=$CODE&scope=openid")
#   {"access_token":"mockoauth_…","token_type":"bearer","scope":"openid","expires_in":3600}
ACCESS=$(echo "$TOK" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

# 3. use the bearer token -> the bound user
curl "$B/oauth2/v1/userinfo" -H "Authorization: Bearer $ACCESS"
#   {"id":"00u1","login":"alice@example.com","email":"alice@example.com","name":"Alice Ng"}
```

The code minted in step 1 is consumed in step 2; the token minted in step 2 is recognized
in step 3. That cross-endpoint memory lives in the mock's **state** — flat key→value
*buckets* here (`codes`, `tokens`), as opposed to the object *collections* from Step 2.
Same mechanism, two shapes.

---

## Step 5 — Time, expiry, and rate limits (the deterministic clock)

Real services expire tokens and throttle clients. Testing that against the real Okta is
slow and non-reproducible. The twin has a **virtual clock**: a clock that is *frozen* until
*you* move it — so time-based behavior is perfectly deterministic.

We passed `--token-ttl 3600`, so our token expires after an "hour". Watch it happen — by
advancing the clock, not by waiting:

```sh
mocksys clock okta/okta                     # 🕐 virtual time 2023-11-14T22:13:20Z
curl -s -o /dev/null -w '%{http_code}\n' "$B/oauth2/v1/userinfo" -H "Authorization: Bearer $ACCESS"
#   200

mocksys clock okta/okta advance 2h          # move the clock forward two hours
curl -s "$B/oauth2/v1/userinfo" -H "Authorization: Bearer $ACCESS"
#   {"message":"Bad credentials"}            <- the token has expired
```

We passed `--rate-limit 100`, so each client gets 100 requests per 60-second window. Every
response carries the real `X-Rate-Limit-*` headers — `Remaining` ticks down as you spend the
budget — and past it you get a `429` with `Remaining: 0`. Advancing the clock past the
window resets it (the window is keyed on the virtual clock, which is why it's
deterministic):

```sh
# (with a small --rate-limit you can see it immediately)
for i in $(seq 1 101); do curl -s -o /dev/null "$B/api/v1/users"; done
curl -i "$B/api/v1/users"
#   HTTP/1.1 429 ...
#   X-Rate-Limit-Limit: 100
#   X-Rate-Limit-Remaining: 0
#   X-Rate-Limit-Reset: 1700000040
mocksys clock okta/okta advance 60          # cross the window boundary -> budget resets
```

`mocksys clock <twin> [advance <1h|30m|45s> | set <iso-or-epoch>]` is your time machine. It
talks to the running twin and never resets its data.

> Want to see these primitives by hand, without a kit? The committed
> [`examples/login-throttle.yaml`](../examples/login-throttle.yaml) is a tiny
> rate-limited login that issues a 30-minute session token — the same three pieces
> (`limit`, `store.ttl`, `state.clock`) authored directly.

---

## Step 6 — Is the twin faithful? `conform`

A twin is only useful if it behaves like the real thing. `conform` replays a request
corpus at the twin and (when you have credentials) at the *live* service, then diffs the
responses — ignoring volatile noise like timestamps and request-ids. It exits non-zero on
any drift, so it drops into CI.

```sh
mocksys conform okta/okta --corpus examples/okta-corpus.json
#   ✓ GET /api/v1/users            →  200 (expect 200)
#   ✓ GET /api/v1/users/...        →  404 (expect 404)
#   ✓ conformant — 6 request(s), 0 diffs
```

Point it at a real tenant to drive the twin toward zero observable delta:

```sh
mocksys conform okta/okta --corpus examples/okta-corpus.json \
  --against https://your-tenant.okta.com --ignore created,lastUpdated
```

This is the loop that keeps a twin honest over time.

---

## Step 7 — How it's all composed: genes

You never wrote any of those 14 endpoints. The `okta` kit is *composed from* reusable
**genes** — proven behavioral patterns:

```sh
mocksys genes
#   · crud-collection  — REST resource: create/list/get/update/delete, filter + pagination
#   · oidc-handshake   — authorize (picker + single-use code), token exchange, bearer userinfo
#   · error-envelope   — provider-shaped error bodies
#   · rate-limit       — per-client budget over a virtual-clock window -> 429
#   · virtual-clock    — deterministic, advanceable time
```

The Okta twin is just: `crud-collection` (users) + `crud-collection` (groups) +
`oidc-handshake` + `error-envelope`, with `rate-limit` and `virtual-clock` switched on.
Building a twin of another service is the same move — compose the genes it needs. (The
same `oidc-handshake` gene powers the standalone `kit oauth2`.) That's how you propagate a
working pattern from one twin to the next.

---

## Where to go next

- **Author your own** — start from [`examples/users.yaml`](../examples/users.yaml) (a
  resource collection) or [`examples/login-throttle.yaml`](../examples/login-throttle.yaml)
  (clock + rate limit + expiring token), and read the verb reference in
  [effects.md](effects.md).
- **The contract format** (static side) — [contracts.md](contracts.md).
- **Kits, genes, conform in depth** — [twins.md](twins.md).
- **Every command** — [cli.md](cli.md).
- **How the pieces fit** — [architecture.md](architecture.md).

You now understand the whole system: contracts compile to mocks; effects give them memory;
collections make them resource systems; the clock makes time deterministic; genes compose
into twins; and `conform` keeps them honest.

# PLAN — Deepening the Okta twin: group membership, JWT/JWKS/discovery, live userinfo

Status: revised after adversarial review (rev 2); all §7 open questions resolved (rev 3)
Scope: three extensions to the `okta` kit (`src/mocksys/kits.cljs`) and the effect
runtime (`src/mocksys/inject.cljs`, `docs/effects.md`).

> **Rev 3** — the seven §7 open questions were resolved via a recorded dlktk dialectic.
> Decisions, bases, and the losing arguments: `design/dialectics/okta-twin-deepening.md`
> (machine-checkable graph: `design/dialectics/okta-twin-deepening.ndjson`, `dlktk check`).
> That doc also fixes the **mocksys-feature / gene / okta-kit boundary** for every new
> capability (the three-tier taxonomy). Scope deltas from the decisions: conform's
> JWT-normalizer moves to "now, with feature 2" (Q5); picker/issuance gains a
> `status==ACTIVE` filter (Q7). See §7 for the resolved list.

> **Rev 2 changelog** — an adversarial review (verdict: *needs revision*) found the rev-1
> design under-scoped feature 3 and got two JWT claims wrong. Confirmed against code and
> folded in:
> - **B1/B2 (blocker):** the account picker is *static HTML baked at build time* from the
>   seed (`kits.cljs:137-143`), so a SCIM-created user never appears in it — rev-1's
>   headline ("SCIM users can log in") was undeliverable. Feature 3 rewritten (§4) and
>   **re-sequenced** (§5): it is no longer "the smallest, ship first."
> - **B3 (blocker):** new verbs would be *rejected by the validator* — the terminal
>   whitelist is hard-coded (`contract.cljs:428-431`). Mandatory compiler edits now
>   enumerated in §1.4.
> - **M1:** access-token `aud`/`sub` were wrong for an org server — fixed (§3.1/§3.2).
> - **M2/M3/M6:** array `scp` claim, clock-gating of `now`-in-`sign`, and `client_id`
>   binding all need explicit runtime support — added (§3.2, §3.4).
> - **M4:** the shared-gene fork touches authorize+token+userinfo (three paths), not just
>   userinfo — corrected (§4.3).
> Full findings retained inline as `[R:Bn/Mn]` markers.

## 0. Why these three

Today's `okta` twin clones the *shape* of three narrow slices (Users/Groups CRUD,
SCIM-ish list filtering, an OIDC code flow) but not Okta's identity *semantics*. The
three highest-value gaps, each cheap relative to its payoff:

1. **Group membership** — groups exist but are hollow: no way to add/list/remove
   members. This is most of what the Groups API is used for.
2. **JWT tokens + JWKS + discovery** — tokens are opaque random strings; any client
   that validates a signature, reads token claims, or fetches JWKS/discovery breaks.
3. **Live `userinfo`** — `userinfo` resolves a frozen seed array, not the live `users`
   collection, so a SCIM-created user can't log in (documented v1 gap, `twins.md:83`).

Each section states Okta's **canonical behavior (with citations)**, the **mocksys
design** (what composes from existing genes vs. what needs a new primitive), the
**effect definitions**, **edge cases**, and **tests / conform additions**.

A recurring theme: **all three** features require *new effect verbs and edits to the
effect compiler*, not just gene composition — including feature 3, whose live-collection
picker and login→uid lookup have no existing verb `[R:B2]`. The spec is explicit about
that boundary — it is the real work. Every new terminal verb also requires the three
compiler edits in §1.4, or the contract validator rejects it `[R:B3]`.

---

## 1. Shared prerequisites (cross-cutting)

### 1.1 Base URL / issuer resolution

JWT `iss`, the discovery document, and `Link`/`_links` all need the twin's own base
URL. Okta's org-authorization-server issuer is the org URL itself
(`https://{yourOktaDomain}`), and discovery lives at
`https://{yourOktaDomain}/.well-known/openid-configuration`
([Okta: auth servers](https://developer.okta.com/docs/concepts/auth-servers/)).

mocksys has no configured public base URL today. Add one:

- New kit option `--issuer URL` (default `http://localhost:{port}`), threaded into the
  `okta` builder and stored on the contract as `"issuer"`.
- In injections, prefer deriving the host from the request `Host` header at request
  time (`hv("host")`) with the configured issuer as fallback, so a twin reached on any
  port still self-describes correctly. Add a prelude helper `baseUrl()` that returns
  `"http://" + hv("host")` when a Host header is present, else the embedded issuer.

> Decision to confirm in review: request-derived host (works on any port, but scheme is
> always `http`) vs. a fixed configured issuer (stable, but wrong if the port changes).
> Recommendation: request-derived with configured fallback.

### 1.2 Confirmed runtime capability — `require` in injections

`node_modules/mountebank/src/models/responseResolver.js:52` evaluates each injection as
`eval("(" + fn + ")(config, injectState, logger, done, imposterState);")` **inside the
module scope**, so Node's `require` is lexically available to injected code. Verified
against the pinned `mountebank@^2.9.1` (`package.json:35`). Therefore feature 2 may call
`require('crypto')` for RS256 signing without a bundled crypto implementation. This
coupling to mb's eval-in-module-scope is a **pinned-version dependency** — see §5.

### 1.3 New prelude helpers (added once, shared)

- `b64url(buf|str)` — base64url encode (JWT + JWKS `n`/`e`).
- `signJwt(headerObj, claimsObj, pemPrivateKey)` — compact RS256 JWT via
  `require('crypto').createSign('RSA-SHA256')`. Deterministic given inputs.
- `joinList(state, coll, field, value)` — return all rows of `coll` whose `field ===
  value` (membership scans; small N, linear is fine).
- `hydrate(state, coll, ids)` — map an array of ids to their objects in `coll`, dropping
  misses. Backs the member/group list joins.

All remain dependency-free except `signJwt` (Node built-in `crypto`).

### 1.4 Mandatory compiler edits for every new terminal verb `[R:B3]`

Adding `link`/`unlink`/`listJoin`/`sign`/`project` (or a collection-by-field lookup) is
**not** just codegen — three sites hard-code the known-verb set and will otherwise reject
or mis-route the new ops:

1. **Validator whitelist** — `src/mocksys/contract.cljs:428-431` errors unless an effect
   has `respond` or one of `create/get/update/remove/list`. Add the new terminal verbs
   here, or ops ending in them fail validation with "effect has no `respond` or collection
   action."
2. **Terminal dispatch vector** — `inject.cljs:68` (`collection-verbs`) and the
   `collection-terminal-js` dispatch (`inject.cljs:174-180`) decide which key is the
   terminal and how it compiles. New collection-shaped terminals must be registered here.
3. **Docs** — `docs/effects.md` enumerates the verb families; keep it in sync.

Non-terminal prechecks (`exists`, and any `sign`/`project` that is *followed* by
`respond`) do not need whitelist entries — they run in the read phase before the terminal.
But `link`/`unlink`/`listJoin` **are** terminal and do. Note `sign`/`project` are designed
as read-phase steps that populate a variable, then a normal `respond` returns it — so they
stay off the terminal whitelist by construction (see §3.2, §4.2).

---

## 2. Feature 1 — Group membership

### 2.1 Okta canonical behavior (cited)

From Okta's published management API (Go SDK `GroupAPI.md`, generated from the
management OpenAPI spec —
[okta/okta-sdk-golang](https://github.com/okta/okta-sdk-golang/blob/master/okta/docs/GroupAPI.md);
reference: [Group API](https://developer.okta.com/docs/api/openapi/okta-management/management/tag/Group/)):

| Operation | Method + path | Success | Body |
|---|---|---|---|
| Assign user to group | `PUT /api/v1/groups/{groupId}/users/{userId}` | `204 No Content` | none |
| Unassign user from group | `DELETE /api/v1/groups/{groupId}/users/{userId}` | `204 No Content` | none |
| List group members | `GET /api/v1/groups/{groupId}/users` | `200` | `[]User` (paginated via `after`/`limit`) |
| List a user's groups | `GET /api/v1/users/{userId}/groups` | `200` | `[]Group` |
| List groups | `GET /api/v1/groups?q=&search=&filter=&after=&limit=` | `200` | `[]Group` |

Canonical details to honor:
- **Assign is idempotent and returns no content** — `PUT` an already-member is still
  `204`. ([GroupAPI.md `AssignUserToGroup`](https://github.com/okta/okta-sdk-golang/blob/master/okta/docs/GroupAPI.md))
- **Members list returns full `User` objects**, not just ids —
  `ListGroupUsers → []User`. Same for `ListUserGroups → []Group`.
- Assigning/unassigning a **non-existent group or user** → `404` with the Okta error
  envelope (`E0000007`).
- The built-in **`Everyone` group** contains all users implicitly in real Okta. The twin
  will treat `Everyone` (`00g1`) as a normal explicit-membership group in v1 and note
  the divergence (see §2.5) rather than special-casing it.

### 2.2 mocksys design — a new `membership` gene + set verbs

Membership is a many-to-many relation; the current collection verbs are single-object
CRUD and cannot express it. Model membership as its own keyed set and add three verbs.

**State:** a `memberships` collection, rows `{ "id": "{gid}:{uid}", "gid": ..., "uid": ... }`,
`idField: id`. Seed empty (or with a couple of rows for demo — see §2.4).

**New effect verbs** (in `inject.cljs`, documented in `effects.md`):

- `link` — idempotent set insert. Binds `gid`/`uid` from the path, writes the composite
  row, returns an empty `204` (configurable status). Unlike `create`, it does **not**
  mint an id and returns **no body**. Guards existence of both endpoints first (`get`
  on `groups` and `users`, each `miss → 404 E0000007`).
- `unlink` — set delete by composite key; `204`; miss → `404`.
- `listJoin` — list rows of a membership collection filtered by one side, hydrate the
  other side against a target collection, return the hydrated array with the same
  cursor-pagination + `Link` header machinery `list` already has.

`listJoin` is the one genuinely new capability; `link`/`unlink` are thinner but **not**
just `create`/`remove` variants: `create` always mints an id and returns a body
(`inject.cljs:66-74`), whereas `link` takes a composite id from the path and returns an
empty `204`; `remove` 404s on miss (`inject.cljs:153-160`), whereas `unlink` must return
`204` even on a non-member to match Okta `[R:M5]`. So both need their own codegen paths,
not flags. All three are terminal → all three need the §1.4 edits.

### 2.3 Operations added by the `membership` gene

```
PUT    /api/v1/groups/{groupId}/users/{userId}   → link    → 204
DELETE /api/v1/groups/{groupId}/users/{userId}   → unlink  → 204
GET    /api/v1/groups/{groupId}/users            → listJoin(memberships by gid → users) → 200 []User
GET    /api/v1/users/{userId}/groups             → listJoin(memberships by uid → groups) → 200 []Group
```

Effect sketch for `link` (YAML-shaped; the gene emits this):

```yaml
- id: assign-user-to-group
  request: { method: PUT, path: "/api/v1/groups/{gid}/users/{uid}" }
  effect:
    bind: { gid: { pathRegex: "/groups/([^/]+)/users/[^/]+$" },
            uid: { pathRegex: "/groups/[^/]+/users/([^/]+)$" } }
    # foreign-key prechecks (both must exist)
    exists: [ { collection: groups, key: "{gid}", miss: { status: 404, json: <okta-error E0000007> } },
              { collection: users,  key: "{uid}", miss: { status: 404, json: <okta-error E0000007> } } ]
    link: { collection: memberships, id: "{gid}:{uid}",
            row: { gid: "{gid}", uid: "{uid}" }, status: 204 }
```

`exists` is a non-terminal precheck (like `limit`): it short-circuits on miss, else falls
through. Alternatively fold the checks into `link` as `requires: [...]`. Review to pick.

Effect sketch for the member list:

```yaml
- id: list-group-users
  request: { method: GET, path: "/api/v1/groups/{gid}/users" }
  effect:
    bind: { gid: { pathRegex: "/groups/([^/]+)/users$" },
            after: { query: after }, lim: { query: limit } }
    listJoin:
      via: memberships          # membership collection
      byField: gid              # match rows where row.gid == {gid}
      byValue: "{gid}"
      hydrate: users            # target collection to expand the other side (uid)
      hydrateVia: uid           # membership field naming the target id
      after: "{after}"
      limit: "{lim}"
      link: '</api/v1/groups/{gid}/users?after={next}>; rel="next"'
```

> `[R:M5]` `listJoin` **cannot reuse** `Coll.list`'s pagination — that logic
> (`inject.cljs:350-356`) paginates over a single collection's id-keyed map, but a joined,
> hydrated array is not a `Coll`. The sort/cursor/slice must be reimplemented over the
> hydrated result. Also decide the cursor domain: Okta cursors over the **returned user
> list** (the `id` of the last returned user), not over membership rows — implement
> `after`/`{next}` against the hydrated user ids.

### 2.4 `okta` kit wiring & seed

- Register the `membership` gene call in `okta` after the users/groups CRUD genes.
- Seed a couple of demo memberships so `GET .../users` is non-empty out of the box, e.g.
  `alice→Admins`, `alice→Everyone`, `bob→Everyone` (rows `00g2:00u1`, `00g1:00u1`,
  `00g1:00u2`).
- `_embedded.stats.usersCount` on group reads (Okta returns it under `?expand=stats`):
  out of scope for v1; note it (§2.5).

### 2.5 Edge cases & known divergences

- **Idempotent assign**: re-`PUT` returns `204`, no duplicate row (composite key dedupes).
  Confirmed: Okta's live integration test double-assigns and asserts the member count stays
  1 (`okta-sdk-dotnet/GroupApiTests.cs:549-551`).
- **Unassign non-member**: `unlink` returns `204` even when there is no membership row —
  **confirmed** against Okta's live integration test (`GroupApiTests.cs:554-555`,
  `"Idempotency - Unassign user not in group (should not throw)"`). A `404` is returned
  *only* for a bad group/user id, which the `exists` precheck (§2.3) already handles. So
  `unlink` must be miss-tolerant (always 204), distinct from `remove` (which 404s on miss).
- **`Everyone` auto-membership**: NOT emulated — membership is explicit only. Document.
- **Cascade on user/group delete**: deleting a user/group leaves orphan membership rows.
  v1: leave orphans (list joins drop unhydratable ids, so they're invisible); note it.
  v2 option: cascade in `remove`.
- **`?expand=stats` usersCount**: not emulated.

### 2.6 Tests & conform corpus

Add to `examples/okta-corpus.json` (golden mode):

```json
{ "method": "PUT",    "path": "/api/v1/groups/00g2/users/00u1", "expect": 204 },
{ "method": "GET",    "path": "/api/v1/groups/00g2/users",       "expect": 200 },
{ "method": "GET",    "path": "/api/v1/users/00u1/groups",       "expect": 200 },
{ "method": "DELETE", "path": "/api/v1/groups/00g2/users/00u1", "expect": 204 },
{ "method": "PUT",    "path": "/api/v1/groups/nope/users/00u1", "expect": 404 }
```

Unit-level: assert `link` dedupes, `listJoin` hydrates to full objects and paginates,
foreign-key misses return the Okta envelope.

---

## 3. Feature 2 — JWT access/ID tokens, JWKS, discovery

### 3.1 Okta canonical behavior (cited)

**Signing**: Okta signs OIDC tokens with **RS256**; the JWT header carries `alg: RS256`
and a `kid` naming the key
([OIDC & OAuth API](https://developer.okta.com/docs/reference/api/oidc/)).

**Access token payload** (org-server example claims —
[OIDC Primer pt.3](https://developer.okta.com/blog/2017/08/01/oidc-primer-part-3),
[OIDC & OAuth API](https://developer.okta.com/docs/reference/api/oidc/)):
`ver, jti, iss, aud, sub, iat, exp, cid, uid, scp` (array), `auth_time`. Example:

```json
{ "ver": 1, "jti": "AT.upPJqU...", "iss": "https://micah.okta.com/oauth2/aus...",
  "aud": "test", "iat": 1501528201, "exp": 1501531801,
  "cid": "0oa2yrbf35Vcbom491t7", "uid": "00u2yulup4eWbOttd1t7",
  "scp": ["openid","email"], "sub": "okta_oidc_fun@okta.com" }
```

> **`[R:M1]` Read the access-token claims carefully — they differ from the id_token:**
> - `sub` = the **login/username** (`okta_oidc_fun@okta.com`), *not* the user id. The id
>   lives in a separate `uid` claim.
> - `aud` = the **resource/API audience** (org URL for the org server), *not* the
>   `client_id`. The client id is carried in `cid`.
> - `client_id`→`cid`, not `aud`. (In the **id_token**, by contrast, `aud` *is* the
>   client_id and `sub` *is* the user id — see below.) Getting these backwards mints tokens
>   Okta never emits and that resource servers keying on `aud` reject.
> `[R:m4]` The cited `iss` here (`.../oauth2/aus…`) is a *custom* auth server; the twin is
> the **org** server, whose issuer is the bare org URL (no `/oauth2/ausXXX`). Use
> `iss = baseUrl()`.

**ID token payload**: `sub, ver, iss, aud, iat, exp, jti, amr, idp, nonce, auth_time`
plus scope claims (`name, email, email_verified, ...`)
([OIDC Primer pt.3](https://developer.okta.com/blog/2017/08/01/oidc-primer-part-3)):

```json
{ "sub": "00u2yulup4eWbOttd1t7", "email": "okta_oidc_fun@okta.com", "ver": 1,
  "iss": "https://micah.okta.com/oauth2/aus...", "aud": "0oa2yrbf35Vcbom491t7",
  "iat": 1501528456, "exp": 1501532056, "jti": "ID.4Mmzy...",
  "amr": ["pwd"], "idp": "00o1zyyqo9bpRehCw1t7", "nonce": "...",
  "email_verified": true, "auth_time": 1501528157 }
```

**JWKS** at `GET /oauth2/v1/keys`
([OIDC Primer pt.3](https://developer.okta.com/blog/2017/08/01/oidc-primer-part-3)):

```json
{ "keys": [ { "alg": "RS256", "e": "AQAB", "kid": "cbkhWG0Ym...",
              "kty": "RSA", "n": "g2XQgdyc5P6F4K26...", "use": "sig" } ] }
```

**Token endpoint** `POST /oauth2/v1/token` returns `access_token, token_type: "Bearer",
expires_in, scope, id_token` (id_token present when `openid` scope requested).

**Discovery** at `GET /.well-known/openid-configuration` (and
`/.well-known/oauth-authorization-server`), issuer = org URL
([auth servers](https://developer.okta.com/docs/concepts/auth-servers/)); standard fields:
`issuer, authorization_endpoint, token_endpoint, userinfo_endpoint, jwks_uri,
registration_endpoint, response_types_supported, grant_types_supported,
subject_types_supported, id_token_signing_alg_values_supported (["RS256"]),
scopes_supported, token_endpoint_auth_methods_supported, claims_supported`.

### 3.2 mocksys design — keypair at build time, sign in-injection

**Keypair**: generate one RSA-2048 keypair at **kit-build time** (in the CLJS process via
`node:crypto` `generateKeyPairSync('rsa', {modulusLength: 2048})`). Compute a stable
`kid` (e.g. base64url of a SHA-256 over the DER public key — Okta uses a thumbprint-style
kid). Embed:
- the **private key PEM** as a literal in the token op's injection (used by `signJwt`),
- the **public key** `n`/`e` (base64url) + `kid` as the literal JWKS response body.

A fixed embedded keypair keeps the twin **deterministic** (same JWKS every run) and lets
`conform` diff stably. Generation happens once per `kit` invocation; the key is baked into
the contract JSON, so `run`/`conform` reuse it.

> Determinism caveat: `generateKeyPairSync` is **not** seeded, so two separate `kit okta`
> invocations produce different keys. Within a single generated contract the key is fixed,
> which is what determinism requires (a twin is deterministic across `run`/`conform` of
> the *same* contract). If cross-invocation reproducibility is wanted, add
> `--signing-key FILE.pem` to pin one. Recommend shipping that option.

**New effect verb `sign`** (terminal-adjacent; produces a variable, then `respond`/`store`
use it):

```yaml
bind:
  client_id: { body: client_id }    # `[R:M6]` token endpoint form field; must be bound
  scope:     { body: scope }
mint: { jti: { prefix: "AT." } }
sign:
  as: access_token                 # variable to hold the compact JWT
  key: "<embedded private PEM>"    # literal, injected by the gene
  header: { alg: RS256, kid: "<kid>", typ: JWT }
  claims:                          # `[R:M1]` org-server access-token shape
    ver: 1
    jti: "{jti}"
    iss: { baseUrl: true }         # bare org URL — NOT a {var} string
    aud: { baseUrl: true }         # resource audience = org URL (NOT client_id)
    sub: "{login}"                 # login/username, NOT the id
    cid: "{client_id}"             # the client id goes here
    uid: "{uid}"                   # the user id goes here
    scp: { splitVar: scope, on: " " }   # `[R:M2]` typed array claim, not a string
    iat: { now: true }
    exp: { now: { add: ttl } }
```

`sign` calls the `signJwt` prelude helper. Two runtime capabilities this exposes:

- **`[R:M2]` Typed (non-string) claims.** `interp`/`deepInterp` (`inject.cljs:325-328`) do
  *string* substitution only — `{scpArray}` would yield `"openid,email"` (a string), not
  `["openid","email"]`. `sign` therefore needs structured claim directives that emit real
  JSON types: `{ splitVar: <var>, on: " " }` → array, `{ now: … }` → number, and plain
  `{var}` → string. `amr: ["pwd"]` is a literal and needs nothing.
- **`[R:M3]` Clock activation.** `iat`/`exp` read the virtual clock, but the clock is only
  auto-enabled when `op-uses-clock?` sees `store.ttl`/`limit`/`now`-**in-bind**
  (`inject.cljs:216-221`). A `now` inside `sign.claims` is invisible to it → without
  `--token-ttl`, `state.__now` is never seeded and `now(state)` returns `0`, so every JWT
  dates to **1970**. Fix: extend `op-uses-clock?` to detect `now`/`baseUrl` inside `sign`,
  **or** have `sign` unconditionally force the clock on. Do this or the tokens are broken
  in the default (no-ttl) build. (§3.4 revised accordingly.)

`iat`/`exp` stay clock-driven, so `--token-ttl` continues to work — now against a *real*
`exp` claim inside a verifiable JWT. The **id_token** uses the mirror-image mapping:
`aud = {client_id}`, `sub = {uid}`, plus `amr: ["pwd"]`, `auth_time`, and `email`/`name`
when the `email`/`profile` scopes are present.

**New static ops** (no state; plain responses with `baseUrl()` interpolation):

```
GET /oauth2/v1/keys                        → 200 JWKS (embedded literal)
GET /.well-known/openid-configuration      → 200 discovery (issuer/endpoints via host)
GET /.well-known/oauth-authorization-server→ 200 discovery (same, OAuth subset)
```

> `[R:m2]` These are **not** injections, so they can't call the `baseUrl()` prelude helper
> (the prelude only exists inside generated injections). The JWKS body is a pure static
> literal (fine). The discovery docs need the host interpolated — use mb **response
> templating** `{{request.headers.host}}` (already supported, `contract.cljs:164-178`),
> not `baseUrl()`. So: JWKS = static example; discovery = static response with `{{request.headers.host}}`
> in the endpoint URLs. `baseUrl()` is used only by `sign` (an injection).

### 3.3 Token endpoint changes

The `oidc-handshake` gene's `access-token` op changes from "mint opaque string + store"
to:
1. `consume` the code → `login`/`uid` (store `uid` alongside login at authorize time — see
   §4).
2. `sign` an **access_token** JWT (claims above).
3. If `openid` in scope, `sign` an **id_token** JWT (ID-token claims: `sub=uid`, `aud=
   client_id`, `amr:["pwd"]`, `auth_time`, plus `email`/`name` when `email`/`profile`
   scopes present).
4. `store` the access_token → `uid` in the `tokens` bucket **with `ttl`** so bearer
   lookups at `userinfo`/CRUD still work and still expire on the clock. (Dual model:
   the JWT is self-describing for external validators; the bucket remains the twin's
   internal source of truth for `userinfo` and rate-limit keying.)
5. `respond` `{ access_token, token_type: "Bearer", expires_in: ttl, scope, id_token? }`.

> Design tension to resolve in review: with real JWTs, should `userinfo`/CRUD validate the
> **signature/exp of the presented JWT** rather than a bucket lookup? v1 recommendation:
> keep the bucket as source of truth (simplest, preserves current behavior); the JWT is
> for *external* consumers. v2: add a `verifyJwt` verb so the twin rejects tampered/expired
> tokens by signature. Note the divergence: v1 twin would accept a structurally-valid but
> unknown JWT only if it's in the bucket — i.e. it still gates on the bucket, which is
> actually *stricter* than Okta. Acceptable.

### 3.4 Determinism, and what stays reproducible

- Same contract → same key → same JWKS → stable `conform` diffs.
- **`[R:M3]` Clock gating is a correctness bug, not just determinism:** as above, `now`
  used only in `sign.claims` does not trip `op-uses-clock?`, so a no-`--token-ttl` build
  seeds no clock and stamps `iat=0`/`exp=ttl` (1970). Must fix `op-uses-clock?` (or force
  the clock in `sign`) — otherwise `iat`/`exp` are neither correct *nor* deterministic in
  the sense intended.
- `iat`/`exp`/`jti`: `iat`/`exp` are clock-driven (deterministic, once the clock is on).
  `jti` uses `rnd()`
  (`Math.random`) — **non-deterministic**, and the signature therefore varies run-to-run.
  For `conform` diffing, `jti`, `access_token`, and `id_token` must be added to the
  default volatile/ignore set for the okta service profile (like request-ids/timestamps).
  Document that JWTs are inherently volatile under diff; conform should compare *decoded
  claims minus jti/iat/exp/signature*, not the raw compact string. **This needs a conform
  enhancement** (a JWT-aware normalizer) — flag as follow-up, or simply `--ignore` the
  three token fields for now.

### 3.5 Security caveats (documented, intentional)

- The private key is **embedded in the contract JSON** and shipped with the twin. This is
  a *mock*; the key is not secret and must never be reused for anything real. Document
  loudly in `docs/twins.md`.
- No client authentication (`client_secret`), no PKCE verification, no `nonce` round-trip
  enforcement in v1 — the JWT is well-formed and verifiable, but the *flow* is still the
  permissive mock flow. List these as explicit non-goals.

### 3.6 Tests & conform

```json
{ "method": "GET", "path": "/oauth2/v1/keys",                     "expect": 200 },
{ "method": "GET", "path": "/.well-known/openid-configuration",   "expect": 200 }
```

Behavioral test (script, not pure corpus): run authorize→token, take the `access_token`,
fetch `/oauth2/v1/keys`, and verify the RS256 signature with a standard JWT library
(e.g. `jose`) against the JWKS; assert `iss`, `aud`, `sub`, `scp`, `exp` claims. Advance
the clock past `exp` and assert `exp` is in the past. This is the acceptance test that
proves the twin is a *real* OIDC issuer to third-party validators.

---

## 4. Feature 3 — `userinfo` over the live `users` collection

### 4.1 Okta canonical behavior (cited)

`GET /oauth2/v1/userinfo` with a bearer access token returns the **full set of claims for
the requested scopes**
([/userinfo](https://developer.okta.com/docs/api/openapi/okta-oauth/oauth/orgas/userinfo),
[OAuth claims & scopes](https://developer.okta.com/docs/concepts/oauth-claims/)):
- `openid` → `sub`.
- `profile` → `name, family_name, given_name, middle_name, nickname,
  preferred_username, profile, picture, website, gender, birthdate, zoneinfo, locale,
  updated_at`.
- `email` → `email, email_verified`.

`sub` is the Okta user id.

### 4.2 mocksys design — the picker is the hard part `[R:B1][R:B2]`

**This feature is larger than rev 1 claimed. It is not "mostly rewiring."** The blocker:
the account picker is *static HTML baked at build time* from the seed array
(`picker-html`, `kits.cljs:104-122`, embedded as a static `responses` example at
`kits.cljs:137-143`). A user created at runtime via `POST /api/v1/users` is written to the
live `users` collection but **never appears in that frozen HTML** — so the headline
outcome ("SCIM-created users can log in") is *undeliverable* by rev 1's design. There is no
existing verb that (a) renders HTML from live collection state, or (b) finds a collection
row by a non-id field (`resolve` reads seed arrays only, `inject.cljs:283-290`; `get` is
by id only, `inject.cljs:126-135`; `list` is terminal). Delivering feature 3 requires
**both** new capabilities. Concretely:

1. **Dynamic picker** — convert `authorize-picker` from a static response into a stateful
   op that `list`s the live `users` collection and renders the picker HTML at request time.
   This needs a way to emit **HTML from collection state**: today every collection response
   forces `Content-Type: application/json` (`collResp`, `inject.cljs:361-362`). Options:
   (a) a `render` terminal verb that maps a listed array through an HTML-row template, or
   (b) let `list` take a `format: html` + row template. Either is net-new codegen (§1.4).
   Each rendered link carries `&login=<profile.login>` exactly as the static one does, so
   the downstream authorize/token flow is unchanged.
2. **Login→uid at authorize** — the `authorize` op (with `?login=`) must map the chosen
   `login` to its `uid` from the live collection to store `uid` in the code bucket. No verb
   finds a row by `profile.login` today. Add a **collection-by-field lookup**
   (`find: { collection: users, field: "profile.login", eq: "{login}", as: uid, pick: id, miss: … }`)
   — a non-terminal read-phase verb (so it needs no §1.4 whitelist entry, but does need the
   `dget` dotted-path support the filter already has, `inject.cljs:338`). *Alternatively*,
   the dynamic picker (step 1) can embed each user's `uid` directly in the link
   (`&login=…&uid=…`), making authorize a pure `bind` — this **avoids the new lookup verb
   entirely** and is the recommended path since step 1 is already listing the collection.
3. At **token** time, carry `uid` (and `login`) into the `tokens` bucket. (This also
   supplies the JWT `sub`/`uid`/`cid` in §3 — the shared plumbing that motivates the
   sequencing in §5.)
4. At **userinfo** time: `lookup(tokens) → uid`, then `get(users, key={uid})`, then
   **project** OIDC claims from the stored SCIM `profile`.

**Claim projection** (`project`) is a read-phase step that maps a bound user object to an
OIDC claim set, then a normal `respond` returns it (so it stays off the terminal
whitelist). v1: return the full profile-derived claim set (Okta's userinfo "always contains
a full set of claims for the requested scopes"; scope enforcement is a separate non-goal).
Mapping:

```
sub               ← user.id
name              ← trim(profile.firstName + " " + profile.lastName)
given_name        ← profile.firstName
family_name       ← profile.lastName
preferred_username← profile.login
email             ← profile.email
email_verified    ← (profile.email present) → true
updated_at        ← user.lastUpdated (if present) else omit
```

Implement as either (a) a new `project` verb (map collection object → claims via a
template with `{user.profile.login}` deep refs), or (b) extend `respond` with a
`fromCollection` + template. Recommendation: a `claims`/`project` step that takes the
`get` result variable and a template object, reusing deep interpolation. This same
projection feeds the id_token/userinfo, so build it once.

### 4.3 Forking the shared `oidc-handshake` gene `[R:M4]`

Rev 1 framed this as a `userinfo`-only source parameter. It is **three** ops that fork on
`:user-source`: **authorize** (dynamic-picker + login→uid vs. static picker), **token**
(store `uid` vs. store only `login`), and **userinfo** (`get`+`project` from collection vs.
`resolve` from seed). The `oauth2` kit uses flat GitHub/Codeberg seed users with no
`profile` (`kits.cljs:132-164, 188-210`) and **must keep the seed path exactly as-is**. So
the gene emits two structurally different effect chains per op:

- `:user-source {:collection "users" :login-field "profile.login"}` → okta path
  (dynamic picker, `find`/embedded-uid, `get`+`project`).
- `:user-source {:seed "users"}` → oauth2 path (today's static picker,
  `resolve`+`respond{jsonVar}`). **Unchanged behavior — regression-test oauth2.**

Once the okta path lands, the derived `oidc_users` seed (`kits.cljs:228`, `okta-oidc-users`)
is dead and is removed from the okta seed. **Removing `oidc_users` does not affect
`oauth2`** (it never used it — okta-only, `kits.cljs:252,258`), but the removal must be
**atomic with the rewire** or okta OIDC 401s in between. This closes the `twins.md:83` gap:
SCIM-created users can log in because the picker, the login→uid step, and userinfo all read
the same mutable collection the CRUD ops write.

### 4.4 Edge cases

- **Picker after SCIM create**: create a user via `POST /api/v1/users`, then the account
  picker should list them and login should succeed. This is the headline acceptance test.
- **Deleted user mid-session**: token in bucket but user removed from collection →
  `userinfo` `get` misses → `401`. Matches "token for a gone user is invalid".
- **STAGED users**: real Okta won't issue tokens for non-`ACTIVE` users. v1: the picker
  lists all users regardless of status (permissive mock). Optionally filter picker to
  `status == ACTIVE`. Note the divergence.

### 4.5 Tests & conform

Behavioral: `POST /api/v1/users` (new user) → confirm it appears at `GET
/oauth2/v1/authorize` picker → drive authorize→token→userinfo → assert `sub` equals the
new user's minted id and `email` matches. Golden corpus can't cover the full flow (needs
the minted id), so this is a scripted integration test.

---

## 5. Cross-cutting risks & sequencing

**Risks**
- **mb eval-in-module-scope** (`require` availability). Verified true: `responseResolver.js:52`
  runs `eval(injected)` directly in the CommonJS module wrapper, so `require` is lexically
  in scope both at the eval site and inside the eval'd function. **`[R:m1]` But
  `package.json:35` is `"^2.9.1"` — a caret *range*, not a pin.** Any `2.x` bump could
  silently sandbox injections and break `signJwt`. Mitigation is therefore **required, not
  optional**: an acceptance test that fails loudly if `require('crypto')` is unavailable;
  document the coupling; keep a fallback (vendored pure-JS RS256 signer in the prelude)
  noted but unbuilt. Consider pinning mb exactly if feature 2 ships.
- **Injection size**: embedding a PEM + JWKS + claim templates enlarges the generated JS.
  Should be fine (a few KB) but worth a sanity check against mb's request-size limits.
- **conform + JWTs**: raw compact tokens differ every run (random `jti`, and `iat`/`exp`
  move with the clock). Needs the JWT-aware normalizer or a documented `--ignore`
  (§3.4). Do not ship feature 2 without addressing conform, or CI diffs will be noise.
- **Scope creep into a state machine**: features here deliberately stop short of user
  lifecycle transitions and factor/MFA. Keep them out.

**Suggested sequencing** (revised — `[R:B2]` feature 3 is *not* the smallest):
1. **Feature 1 (membership)** first — the most self-contained. `link`/`unlink`/`listJoin`
   touch only new state and the §1.4 compiler edits; nothing else depends on it. Good
   vehicle for landing the §1.4 verb-registration pattern once.
2. **Feature 3 (live userinfo)** — mid-weight, *not* "rewiring": needs a dynamic-picker
   render path and a login→uid step (§4.2) plus the three-op gene fork (§4.3). Do it before
   feature 2 because its authorize/token `uid`/`login` plumbing supplies the JWT
   `sub`/`uid`/`cid`.
3. **Feature 2 (JWT/JWKS/discovery)** last — largest, and builds on feature 3's `uid`
   plumbing and the §1.4 pattern.

> If feature 3's dynamic-picker work (§4.2) proves too large, a **de-scoped feature 3** is
> still worth shipping: wire `userinfo` to `get` from the live collection using a `uid`
> that the token flow already has, and accept that the *picker* still lists only seeded
> users (i.e. programmatic authorize with `?login=` works for SCIM-created users, but the
> HTML picker doesn't show them). That delivers "SCIM users can log in via the API" without
> the HTML-render verb. Decide in review.

## 6. Out of scope (explicit non-goals)

- User lifecycle state machine (`/lifecycle/*`), status transitions on create.
- Factors / MFA, apps, policies, sessions, roles, system log, group rules.
- `client_secret` auth, PKCE verification, `nonce` enforcement, refresh tokens,
  `/introspect`, `/revoke`, `/logout`.
- Custom authorization servers (`/oauth2/{authServerId}/v1/*`) — org server only.
- `_embedded.stats.usersCount`, `?expand=`.
- `_links` HAL sections on user/group objects (separate, orthogonal fidelity task).

## 7. Resolved questions (dlktk dialectic `jivog-lisas`)

All seven resolved. Full reasoning + rejected alternatives:
`design/dialectics/okta-twin-deepening.md`. Re-check for drift: `dlktk check --all`.

| # | Question | Decision | Basis |
|---|----------|----------|-------|
| Q1 | Base URL / `iss` (§1.1) | **Request-derived host `http://{Host}`, `--issuer` fallback** | zero-config-portability |
| Q2 | `link`/`unlink` verbs vs flags (§2.2) | **Distinct new terminal verbs** (+`listJoin`) | separation-and-reuse |
| Q3 | Unassign non-member (§2.5) | **204 always** (idempotent) — *confirmed via Okta live integration test* | okta-fidelity |
| Q4 | Token source-of-truth (§3.3) | **v1: bucket** (bucket ttl enforces exp); `verifyJwt` parked as v2 | v1-simplicity |
| Q5 | conform JWT diffing (§3.4) | **Build the JWT-aware normalizer now**, alongside feature 2 | conform-fidelity |
| Q6 | Pin signing key (§3.2) | **Yes: `--signing-key` opt-in**, default per-invocation | reproducibility |
| Q7 | Restrict issuance to ACTIVE (§4.4) | **Yes: picker + issuance filter `status==ACTIVE`** | okta-fidelity |

Two decisions changed the plan's scope: **Q5** (normalizer is no longer "later" — it lands
with feature 2, because `--ignore` would mask the very claim errors the review caught) and
**Q7** (add a `status==ACTIVE` filter to the picker/issuance path). Apply both when
implementing §3–§4.

## 8. Feature / gene / kit boundary

The decision record's Part 2 is the authoritative taxonomy. Summary rule:

> A capability is a **mocksys core feature** (Tier 1, in `inject.cljs`/`contract.cljs`/
> `conform.cljs`) or a **reusable gene** (Tier 2, in `kits.cljs`) if a non-Okta twin would
> reuse it unchanged; it is **okta-kit** (Tier 3) only if it encodes Okta's specific paths,
> claim values, JWKS/discovery bodies, error codes, lifecycle policy, or seed.

- **Tier 1 (features):** every new verb — `link`, `unlink`, `listJoin`, `exists`, `find`,
  `sign`, `project`, HTML-from-collection render — plus `baseUrl()`, the RSA/JWKS signing
  subsystem + `--signing-key`, the `op-uses-clock?` fix, the conform JWT normalizer, and
  the §1.4 validator/dispatch registration. `verifyJwt` joins here in v2.
- **Tier 2 (genes):** the new `membership` gene; the `oidc-handshake` extension
  (`:user-source` seed-vs-collection + `:active-only?`). `oauth2` keeps the seed path.
- **Tier 3 (okta kit):** URL paths, claim values/shapes, JWKS + discovery bodies, error
  codes (`E0000007`/`E0000047`), the ACTIVE policy, and the default seed.

This is why Q2 (new verbs) and Q5/Q6 (signing + conform) land as **features**, not okta
code: they pay their cost once and any future JWT/many-to-many twin reuses them.

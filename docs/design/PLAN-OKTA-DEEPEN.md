# PLAN — Deepening the Okta twin: group membership, JWT/JWKS/discovery, live userinfo

Status: rev 4 — verb set finalized against the compiler; docs drafted; ready to implement
Scope: three extensions to the `okta` kit (`src/mocksys/kits.cljs`) and the effect
runtime (`src/mocksys/inject.cljs`, `contract.cljs`, `conform.cljs`, `docs/effects.md`).

> **The new effect verbs are specified in [`docs/effects.md`](docs/effects.md) →
> "Joins, lookups, signing, rendering".** This plan references them; that doc is the
> contract. Final set (down from rev-1's eight, after reading the compiler):
>
> | Verb | Kind | Replaces / note |
> |---|---|---|
> | `find` | read-phase | collection read → var; **absorbs** the old `exists` FK-guard (a `find` you don't consume *is* a presence check) |
> | dotted-path `interp` | interp upgrade | **absorbs** the old `project` verb — `{user.profile.email}` shapes claims in a plain `respond` |
> | `sign` | read-phase | RS256 JWT → var, typed claims |
> | `link` / `unlink` | terminal | idempotent set insert / miss-tolerant delete |
> | `listJoin` | terminal | filter a relation, hydrate, paginate |
> | `render` | terminal | HTML page from live collection state (dynamic picker) |
>
> `exists` and `project` from rev 2/3 are **gone** — folded into `find` and dotted interp.
> Only the four terminal verbs need the §1.4 compiler edits; `find`/`sign` are read-phase.

> **Rev 3** — the seven §7 open questions were resolved via a recorded dlktk dialectic.
> Decisions, bases, and the losing arguments: `docs/design/dialectics/okta-twin-deepening.md`
> (machine-checkable graph: `docs/design/dialectics/okta-twin-deepening.ndjson`, `dlktk check`).
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
lookup (`find`) and dynamic picker (`render`) have no existing verb `[R:B2]`. The spec is
explicit about that boundary — it is the real work. Every new *terminal* verb also requires
the three compiler edits in §1.4, or the contract validator rejects it `[R:B3]`.

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
- A new **`baseUrl` bind source** (`bind: { origin: { baseUrl: true } }`) exposes the
  origin to `sign` and `respond`. It returns `"http://" + hv("host")` when a `Host` header
  is present, else the embedded issuer. (Documented in effects.md → `sign`.)

> **Resolved (Q1, §7): request-derived host with an `--issuer` fallback.** The http-only
> scheme is correct for a local non-secret mock issuer; `--issuer` covers any fixed-host
> need. Discovery docs (static ops, not injections) use mb templating
> `{{request.headers.host}}` instead — see §3.2 `[R:m2]`.

### 1.2 Confirmed runtime capability — `require` in injections

`node_modules/mountebank/src/models/responseResolver.js:52` evaluates each injection as
`eval("(" + fn + ")(config, injectState, logger, done, imposterState);")` **inside the
module scope**, so Node's `require` is lexically available to injected code. Verified
against the pinned `mountebank@^2.9.1` (`package.json:35`). Therefore feature 2 may call
`require('crypto')` for RS256 signing without a bundled crypto implementation. This
coupling to mb's eval-in-module-scope is a **pinned-version dependency** — see §5.

### 1.3 New prelude helpers (added once, shared)

- `dget(o,p)` — already exists (`inject.cljs:338`); reused by dotted-path `interp`, `find`
  field lookups, and `render`/claim templates.
- `b64url(buf|str)` — base64url encode (JWT + JWKS `n`/`e`).
- `signJwt(headerObj, claimsObj, pemPrivateKey)` — compact RS256 JWT via
  `require('crypto').createSign('RSA-SHA256')`. Deterministic given inputs.
- `findBy(state, coll, field, value)` — first row of `coll` whose `dget(row,field) ===
  value` (backs `find` by secondary field; `find` by `key` reuses `coll(...).get`).
- `joinPage(state, viaColl, byField, byValue, hydrateColl, hydrateField, after, limit)` —
  filter the relation, hydrate the other side (dropping misses), sort + cursor-paginate
  over the **hydrated** ids; returns `{items, next}` like `Coll.list`. Backs `listJoin`.
- `renderPage(rows, rowTpl, wrapTpl)` — join `interp(rowTpl,row)` over rows into
  `wrapTpl` at `{rows}`. Backs `render`.
- extend `interp` to accept dotted keys `{a.b.c}` (regex `\{([\w.]+)\}` + `dget`). One
  change; enables claim/response shaping everywhere without a `project` verb.

All remain dependency-free except `signJwt` (Node built-in `crypto`).

### 1.4 Mandatory compiler edits `[R:B3]`

Two of the new verbs are **read-phase** (`find`, `sign`): they populate a variable and fall
through to a normal `respond`, so they need **no** whitelist entry — only new emit branches
in `effect-body-js` (`inject.cljs:259-311`) at the right point in the read order (see the
execution model, effects.md).

Four are **terminal** (`link`, `unlink`, `listJoin`, `render`) and each touches three
hard-coded sites, or the op is rejected / mis-routed:

1. **Validator whitelist** — `src/mocksys/contract.cljs:428-431` errors unless an effect
   has `respond` or one of `create/get/update/remove/list`. Add the four terminal verbs
   here, else "effect has no `respond` or collection action."
2. **Terminal dispatch** — add the verbs to `collection-verbs` (`inject.cljs:68`) and a
   branch each in `collection-terminal-js` (`inject.cljs:174-180`) with its `*-js` emitter.
   `render` additionally needs a non-JSON responder (`collResp` forces
   `application/json`, `inject.cljs:361-362`) — emit `text/html`.
3. **Docs** — `docs/effects.md` is the verb contract; keep it in sync.

The **interp dotted-path** change and the **`baseUrl` bind source** are additive (a new
regex branch and a new `bind-js` case, `inject.cljs:75-96`) and touch no whitelist.

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

**Verbs used** (all specified in effects.md → "Joins, lookups, signing, rendering"):

- `find` (read-phase) — used twice as a **foreign-key guard**: `find` the group and the
  user by id, each `miss → 404 E0000007`. Reaching the terminal means both exist. This is
  why there is no separate `exists` verb — a `find` you don't consume *is* the presence
  check. Confirmed necessary: Okta 404s an assign/unassign to a bad group *or* user id
  (`GroupApiTests.cs:726,739`).
- `link` (terminal) — idempotent set insert; composite id `{gid}:{uid}` from bound vars;
  empty `204`. Unlike `create` it mints no id and returns no body.
- `unlink` (terminal) — miss-tolerant delete; empty `204` **even on a non-member** (§2.5,
  confirmed). Unlike `remove` (which 404s on miss) `[R:M5]`.
- `listJoin` (terminal) — filter the relation by one side, hydrate the other against a
  target collection, cursor-paginate over the hydrated ids (its own pagination, *not*
  `Coll.list`'s — see effects.md and `[R:M5]` below).

`link`/`unlink`/`listJoin`/`render` are terminal → each needs the §1.4 edits; `find` is
read-phase and needs none.

### 2.3 Operations added by the `membership` gene

```
PUT    /api/v1/groups/{groupId}/users/{userId}   → link    → 204
DELETE /api/v1/groups/{groupId}/users/{userId}   → unlink  → 204
GET    /api/v1/groups/{groupId}/users            → listJoin(memberships by gid → users) → 200 []User
GET    /api/v1/users/{userId}/groups             → listJoin(memberships by uid → groups) → 200 []Group
```

Effect sketches (the gene emits these; full syntax in effects.md):

```yaml
# PUT assign — find-guard both endpoints, then idempotent link
- id: assign-user-to-group
  request: { method: PUT, path: "/api/v1/groups/{gid}/users/{uid}" }
  effect:
    bind: { gid: { pathRegex: "/groups/([^/]+)/users/[^/]+$" },
            uid: { pathRegex: "/users/([^/]+)$" } }
    find: [ { collection: groups, key: "{gid}", as: _g, miss: { status: 404, json: <okta E0000007> } },
            { collection: users,  key: "{uid}", as: _u, miss: { status: 404, json: <okta E0000007> } } ]
    link: { collection: memberships, id: "{gid}:{uid}", row: { gid: "{gid}", uid: "{uid}" }, status: 204 }

# GET members — find-guard the group, then join memberships → users
- id: list-group-users
  request: { method: GET, path: "/api/v1/groups/{gid}/users" }
  effect:
    bind: { gid: { pathRegex: "/groups/([^/]+)/users$" }, after: { query: after }, lim: { query: limit } }
    find: { collection: groups, key: "{gid}", as: _g, miss: { status: 404, json: <okta E0000007> } }
    listJoin: { via: memberships, byField: gid, byValue: "{gid}",
                hydrate: users, hydrateVia: uid, after: "{after}", limit: "{lim}",
                link: '</api/v1/groups/{gid}/users?after={next}>; rel="next"' }
```

> `[R:M5]` `listJoin` has its **own** pagination (`joinPage`, §1.3), not `Coll.list`'s —
> that logic (`inject.cljs:350-356`) ranges over one collection's id-keyed map, but a
> joined/hydrated array is not a `Coll`. The cursor ranges over the **returned objects'
> ids** (the last returned user's `id`), matching Okta's cursor domain, not membership rows.

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
  *only* for a bad group/user id, which the `find`-guard (§2.3) already handles. So
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
    scp: { split: "{scope}", on: " " }  # `[R:M2]` typed array claim, not a string
    iat: { now: true }
    exp: { now: { add: 3600 } }         # build-time literal (the token TTL)
```

`sign` calls the `signJwt` prelude helper. Two runtime capabilities this exposes:

- **`[R:M2]` Typed (non-string) claims.** `interp`/`deepInterp` (`inject.cljs:325-328`) do
  *string* substitution only — a template `"{scope}"` yields `"openid,email"` (a string),
  not `["openid","email"]`. `sign` claim values are therefore typed directives: a **string**
  → interp (dotted paths allowed); `{ split: <str>, on: <sep> }` → array; `{ now: … }` →
  number; `{ var: <name> }` → the bound variable verbatim; a literal → itself. `amr:
  ["pwd"]` is a literal. `exp`'s `now.add` is a build-time literal (bind's `now.add` is
  emitted as a literal at `inject.cljs:91-92`), which is fine — the TTL is a kit CLI value.
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

> **Resolved (Q4, §7): the bucket is the token source-of-truth in v1.** `userinfo`/CRUD
> `lookup` the presented bearer in the `tokens` bucket (whose `ttl` enforces `exp`); the
> JWT is for *external* validators. This gates on the bucket — *stricter* than Okta (an
> unknown-but-valid JWT is rejected), which is acceptable. A `verifyJwt` verb that checks
> the presented JWT's signature/exp is a **v2** reusable feature, parked (see §8).

### 3.4 Determinism, and what stays reproducible

- Same contract → same key → same JWKS → stable `conform` diffs.
- **`[R:M3]` Clock gating is a correctness bug, not just determinism:** as above, `now`
  used only in `sign.claims` does not trip `op-uses-clock?`, so a no-`--token-ttl` build
  seeds no clock and stamps `iat=0`/`exp=ttl` (1970). Must fix `op-uses-clock?` (or force
  the clock in `sign`) — otherwise `iat`/`exp` are neither correct *nor* deterministic in
  the sense intended.
- `iat`/`exp`/`jti`: `iat`/`exp` are clock-driven (deterministic, once the clock is on).
  `jti` uses `rnd()` (`Math.random`) — **non-deterministic**, so the raw compact token
  varies run-to-run.
- **conform JWT-aware normalizer (Q5, §7 — build now, with feature 2).** conform must
  compare *decoded claims minus `jti`/`iat`/`exp`/signature*, not the raw compact string —
  a plain `--ignore` of the token masks the whole thing and would have hidden the
  `aud`/`sub`/`scp` errors the review caught (M1/M2). Add a small normalizer in
  `conform.cljs`: detect a compact-JWT-shaped value (`hdr.payload.sig`), base64url-decode
  the payload, drop the four volatile claims, and diff the rest. Reusable for any
  JWT-issuing twin. It lands **alongside** feature 2, not before it, so it is not a blocker.

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
(e.g. `jose`) against the JWKS; assert the org-server claim shape — `iss == baseUrl`,
`aud == baseUrl`, `sub == login`, `cid == client_id`, `uid`, `scp` (array), and `exp`.
Advance the clock past `exp`; a `lookup` of the (bucket-ttl'd) token now misses → `401`.
This is the acceptance test that proves the twin is a *real* OIDC issuer to third-party
validators. A second acceptance test asserts `require('crypto')` is reachable in an
injection (fails loudly if a future mb sandboxes it — `[R:m1]`, §5).

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

**This feature is larger than rev 1 claimed. It is not "mostly rewiring"** — but it is
smaller than rev 2 feared, because `find` + dotted-path `interp` do the work that rev 2
budgeted a `project` verb and a bespoke lookup for. The original blocker stands: the
account picker is *static HTML baked at build time* from the seed (`picker-html`,
`kits.cljs:104-122`, embedded at `kits.cljs:137-143`), so a runtime `POST /api/v1/users`
user **never appears in it**. Two capabilities close the gap; both now exist as general
verbs:

1. **Dynamic picker** (`render`) — replace the static `authorize-picker` response with a
   `render` op over the live `users` collection (filter `status eq "ACTIVE"` per Q7). Each
   row emits a link carrying `&login={profile.login}&uid={id}`, so **authorize needs no
   lookup** — it `bind`s `login`/`uid` straight from the query. `render` emits `text/html`
   (the one non-JSON collection responder, §1.4).
2. **userinfo over the live collection** (`find` + dotted `interp`) — `lookup(tokens) →
   uid`, then `find { collection: users, key: "{uid}", as: user, miss: 401 }`, then a
   plain `respond` whose `json` template shapes the OIDC claims with dotted paths. No
   `project` verb — dotted `interp` (§1.3) reads `{user.profile.email}` directly:

```yaml
respond:
  json:
    sub:                "{user.id}"
    name:               "{user.profile.firstName} {user.profile.lastName}"
    given_name:         "{user.profile.firstName}"
    family_name:        "{user.profile.lastName}"
    preferred_username: "{user.profile.login}"
    email:              "{user.profile.email}"
    email_verified:     true
```

(`email_verified` is a literal `true`; if you need it conditional on email presence, that
is a claim-shaping nicety deferred to v2 — Okta's userinfo returns the full claim set for
requested scopes, and scope enforcement is a separate non-goal.) The same dotted-template
shaping feeds the id_token claims in §3, so it is built once.

3. At **token** time, carry `uid` and `login` into the `tokens` bucket (supplying the JWT
   `sub`/`uid`/`cid` in §3 — the shared plumbing that motivates the §5 sequencing).

### 4.3 Forking the shared `oidc-handshake` gene `[R:M4]`

Rev 1 framed this as a `userinfo`-only source parameter. It is **three** ops that fork on
`:user-source`: **authorize-picker** (`render` over the live collection vs. static picker),
**token** (store `uid`+`login` vs. store `login` only), and **userinfo** (`find`+dotted
`respond` from the collection vs. `resolve` from seed). The `oauth2` kit uses flat
GitHub/Codeberg seed users with no `profile` (`kits.cljs:132-164, 188-210`) and **must keep
the seed path exactly as-is**. So the gene emits two structurally different effect chains
per op:

- `:user-source {:collection "users" :login-field "profile.login" :active-only? true}` →
  okta path (`render` picker, query-borne `uid`, `find`+dotted `respond`).
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
  `userinfo` `find` misses → `401`. Matches "token for a gone user is invalid".
- **STAGED users (Q7, §7)**: the `render` picker filters `status eq "ACTIVE"`, so the
  default seed's `carol` (STAGED) does not appear and cannot be issued a token — matching
  Okta, which refuses non-ACTIVE users. This is a `filter` clause, not a lifecycle state
  machine (which stays out of scope, §6).

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
  move with the clock). Addressed by the JWT-aware normalizer that ships *with* feature 2
  (Q5, §3.4) — not a `--ignore` mask. Do not ship feature 2 without it, or CI diffs are
  noise (and claim-level fidelity is lost).
- **Scope creep into a state machine**: features here deliberately stop short of user
  lifecycle transitions and factor/MFA. Keep them out.

**Suggested sequencing** (revised — `[R:B2]` feature 3 is *not* the smallest):
1. **Feature 1 (membership)** first — the most self-contained. `link`/`unlink`/`listJoin`
   touch only new state and the §1.4 compiler edits; nothing else depends on it. Good
   vehicle for landing the §1.4 verb-registration pattern once.
2. **Feature 3 (live userinfo)** — mid-weight: the `render` picker + `find`-based userinfo
   (§4.2) and the three-op gene fork (§4.3). Do it before feature 2 because its
   token-time `uid`/`login` plumbing supplies the JWT `sub`/`uid`/`cid`.
3. **Feature 2 (JWT/JWKS/discovery)** last — largest, and builds on feature 3's `uid`
   plumbing and the §1.4 pattern.

> **De-scope valve.** If `render` (the dynamic picker) proves too large, ship feature 3
> without it: keep the static seed picker, but still wire **userinfo** to `find` the live
> collection via the token's `uid`. Then a SCIM-created user authenticates via a direct
> `GET /oauth2/v1/authorize?login=…&uid=…` (the flow works end-to-end) but is not clickable
> in the HTML picker. That still closes the *semantic* gap ("SCIM users can log in") and
> drops only `render`. The headline picker acceptance test (§4.4) then becomes v2.

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
`docs/design/dialectics/okta-twin-deepening.md`. Re-check for drift: `dlktk check --all`.

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

- **Tier 1 (features):** the six new verbs — `find`, `link`, `unlink`, `listJoin`, `sign`,
  `render` — plus the dotted-path `interp` upgrade, the `baseUrl` bind source, the RSA/JWKS
  signing subsystem + `--signing-key`, the `op-uses-clock?` fix (detect `now` in `sign`),
  the conform JWT normalizer, and the §1.4 validator/dispatch registration. `verifyJwt`
  joins here in v2. (`exists`/`project` from earlier revs were folded into `find` and
  dotted `interp` — no longer separate.)
- **Tier 2 (genes):** the new `membership` gene; the `oidc-handshake` extension
  (`:user-source` seed-vs-collection + `:active-only?`). `oauth2` keeps the seed path.
- **Tier 3 (okta kit):** URL paths, claim values/shapes, JWKS + discovery bodies, error
  codes (`E0000007`/`E0000047`), the ACTIVE policy, and the default seed.

This is why Q2 (new verbs) and Q5/Q6 (signing + conform) land as **features**, not okta
code: they pay their cost once and any future JWT/many-to-many twin reuses them.

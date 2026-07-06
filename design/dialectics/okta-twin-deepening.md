# Okta twin deepening — decisions & the mocksys-feature / okta-kit boundary

Companion to `okta-twin-deepening.ndjson` (the machine-checkable dlktk argument graph,
discussion `jivog-lisas`). The `.ndjson` is the source of truth — labels there are
computed by Dung grounded semantics, not asserted here. Re-check with:

```sh
dlktk check --all          # exit 5 if any decision below has drifted (position no longer justified)
```

Each decision was argued under attributed personas (Shipper / Maintainer / Security /
Fidelity), every position defended by *objecting to its objector* (reinstatement), and
ties broken by an explicit `prefer … --basis`. Roster and full derivations are in the
graph; `dlktk explain <issue> -d jivog-lisas` replays any one round by round.

---

## Part 1 — The seven decisions

| # | Question | Decision | Basis | Rejected alternative (why it lost) |
|---|----------|----------|-------|-------------------------------------|
| Q1 | Base URL / `iss` source (§1.1) | **Request-derived host** `http://{Host}`, with a configured `--issuer` fallback | `zero-config-portability` | Fixed `--issuer` only — wrong the moment the twin runs on another port; conform/env/run vary ports. The "http-only scheme" objection was itself defeated: for a local non-secret mock issuer, http is expected, and `--issuer` covers any fixed-host need. |
| Q2 | `link`/`unlink` verbs vs flags on `create`/`remove` (§2.2) | **Distinct new terminal verbs** `link`/`unlink` (+`listJoin`) | `separation-and-reuse` | Flags on create/remove — `create` mints ids + returns a body, `remove` 404s on miss, `link` needs a composite path-id + empty 204, `unlink` must 204 even on a non-member; flags would balloon both with mode-conditionals. The "more compiler surface" objection was defeated: the surface is paid once and serves any many-to-many. |
| Q3 | Unassign non-member → 204 or 404 (§2.5) | **204 always** (idempotent) | `okta-fidelity` | 404 on non-member — an observable fidelity divergence. **CONFIRMED (not inferred):** Okta's own live integration test `okta-sdk-dotnet/GroupApiTests.cs:554-555` (`"Idempotency - Unassign user not in group (should not throw)"`) proves a non-member unassign returns **204**; success=204 at `:528-530`; `404` is only for a bad group/user *id* (OpenAPI `ErrorResourceNotFound404`). Bonus: `:549-551` confirms assign is idempotent, validating the `link` verb. |
| Q4 | userinfo/CRUD: token bucket vs verify presented JWT (§3.3) | **v1: bucket is source-of-truth** (JWT is for external consumers; bucket `ttl` already enforces `exp`) | `v1-simplicity` | In-twin `verifyJwt` — no v1 behavior gain (bucket already enforces exp; bucket-gating is *stricter* than Okta). Not rejected outright: `verifyJwt` is a valuable **v2 reusable feature**, deferred. |
| Q5 | conform: JWT-aware normalizer now vs `--ignore` (§3.4) | **Build a minimal JWT-aware normalizer now** (decode, drop sig/`jti`/`iat`/`exp`, diff claims), landing *alongside* feature 2 | `conform-fidelity` | Ship with `--ignore` — masks the *whole* token, so conform can no longer diff claims; it would have silently hidden the `aud`/`sub`/`scp` errors the review just caught (M1/M2), defeating conform's purpose. The "extra scope blocks the ship" objection was defeated: the normalizer is small and lands *with* feature 2, not before it. |
| Q6 | Pin the signing key (`--signing-key`) (§3.2) | **Yes** — default random-per-invocation (stable within a contract), plus opt-in `--signing-key FILE.pem` | `reproducibility` | Always-random — breaks any checked-in golden/JWKS across regenerations. The "committed key looks like a leaked secret" objection was defeated: the mock key is non-secret *by design* and documented as such; the pin is opt-in. |
| Q7 | Restrict picker + issuance to `status==ACTIVE` (§4.4) | **Yes** — only ACTIVE users appear/authenticate | `okta-fidelity` | Permissive — real Okta refuses non-ACTIVE users, and the default seed ships `carol=STAGED`, so permissive auth is an out-of-the-box divergence that could mask a bug in the code under test. The "inconsistent with the otherwise-permissive flow" objection was defeated: a `status eq ACTIVE` filter is one list clause, not the out-of-scope lifecycle state machine. |

**Net scope deltas vs. rev 2 of the plan:** Q5 upgrades the conform normalizer from
"later" to "now (with feature 2)". Q7 adds an ACTIVE filter to the picker/issuance. Q4 and
Q6 confirm the plan's recommendations; Q1/Q2/Q3 confirm with the losing side's reasoning
recorded. `verifyJwt` (Q4) is explicitly parked as v2.

---

## Part 2 — The mocksys-feature / okta-kit boundary

The decisions above only make sense against a clear rule for *where* each new capability
lives. mocksys already has a three-tier structure; every new capability slots into exactly
one tier. **The test:** *would a non-Okta twin (GitHub, Stripe, a generic OIDC IdP) reuse
this unchanged?* If yes → it is a **core feature** or a **gene**, never kit code.

### Tier 1 — Core runtime features (reusable everywhere)

Live in `src/mocksys/inject.cljs` (effect compiler + prelude), `contract.cljs`
(validator), `conform.cljs`, `core.cljs` (CLI), and are documented in `docs/effects.md`.
Nothing Okta-specific may leak here.

| Capability | Where | Reused by (examples) |
|---|---|---|
| `link` — idempotent set insert, composite id from path, empty 204 | inject.cljs + §1.4 whitelist | any many-to-many: GitHub team members, Stripe customer↔subscription |
| `unlink` — miss-tolerant set delete (always 204) | inject.cljs + §1.4 | same |
| `listJoin` — filter membership rows by one side, hydrate against a target collection, paginate over hydrated ids | inject.cljs + §1.4 | any join-list endpoint |
| `exists` — non-terminal foreign-key precheck | inject.cljs (read-phase) | any endpoint validating a referenced id |
| `find` — resolve a collection row by a non-id (dotted) field | inject.cljs (read-phase; reuses `dget`) | login→id, email→id, any secondary-key lookup |
| `sign` — RS256 compact-JWT signing via `require('crypto')`, with **typed claim directives** (`splitVar`→array, `now`→number, `baseUrl`→issuer string) | inject.cljs prelude (`signJwt`, `b64url`) | any JWT-issuing twin (Auth0, Cognito, a bare OIDC IdP) |
| `project` — map a bound object → an output/claims object via a dotted-ref template | inject.cljs (read-phase) | any userinfo-style projection |
| HTML-from-collection render (`render` verb, or `list format:html`) | inject.cljs (relaxes the JSON-only `collResp`) | any dynamic listing/picker rendered from live state |
| `baseUrl()` prelude helper + request-host derivation | inject.cljs | any twin that must self-describe (discovery, `_links`, redirect URIs) |
| Build-time RSA keypair generation + JWKS derivation + `--signing-key FILE.pem` (Q6) | core.cljs / kit-build path | any JWT-signing twin |
| `op-uses-clock?` detects `now`/`baseUrl` inside `sign` (Q1/Q3 correctness) | inject.cljs | any clock-stamped signing |
| conform **JWT-aware normalizer** (decode compact JWT, drop sig/`jti`/`iat`/`exp`, diff claims) (Q5) | conform.cljs | any JWT-issuing twin under `conform` |
| Validator-whitelist + terminal-dispatch registration for the new verbs (§1.4) | contract.cljs:428-431, inject.cljs:68/174-180 | mechanical prerequisite for every new terminal verb |
| `verifyJwt` — verify a presented JWT's signature+exp (Q4, **v2/deferred**) | inject.cljs (read-phase) | any twin that wants to reject forged/expired tokens |

### Tier 2 — Reusable genes (parameterized fragments in `kits.cljs`)

Sit between core and kit: parameterized contract fragments any kit composes. A gene is
Okta-agnostic; you hand it paths/collections/fields.

| Gene | Status | Parameterization |
|---|---|---|
| `crud-collection` | exists | `:coll :base :id-prefix :create-defaults …` |
| `oidc-handshake` | **extended (Q7 + feature 3)** | now takes `:user-source {:seed …}` **or** `{:collection … :login-field …}`, and an optional `:active-only?` issuance filter. `oauth2` keeps `:seed`; `okta` uses `:collection` + `:active-only? true`. |
| `membership` | **new** | `:link-base "/…/{gid}/users/{uid}"`, `:members-base`, `:owner-coll`, `:member-coll`, `:join-coll` — composes `link`/`unlink`/`listJoin`/`exists`. Okta calls it with Okta paths; a GitHub kit could call it with team paths. |
| `error-envelope` | exists | the miss-body shape (provider-shaped) |
| `rate-limit`, `virtual-clock` | exist | — |

> The **generic-vs-Okta split within a gene**: the `membership` gene is reusable; the fact
> that Okta assigns via `PUT /api/v1/groups/{groupId}/users/{userId}` returning 204 is a
> *kit argument* passed to it. The `sign` feature is reusable; the *Okta claim set* is a
> kit-supplied template.

### Tier 3 — Okta kit only (`okta` builder + seed in `kits.cljs`)

Everything genuinely specific to Okta's contract. None of this is reusable, and none of it
belongs in Tier 1/2.

- **URL paths**: `/api/v1/users`, `/api/v1/groups`, `/api/v1/groups/{gid}/users/{uid}`,
  `/oauth2/v1/authorize|token|userinfo|keys`, `/.well-known/openid-configuration`.
- **Claim values & shapes** (Q-none, but M1-corrected): access token `sub=login`,
  `aud=baseUrl` (org audience), `cid=client_id`, `uid`, `scp`; id_token `aud=client_id`,
  `sub=uid`, `amr:["pwd"]`. These fill the generic `sign`/`project` templates.
- **JWKS + discovery response bodies**: Okta's exact field set and org-server semantics
  (issuer = bare org URL, `id_token_signing_alg_values_supported: ["RS256"]`).
- **Error codes**: `E0000007` (not found), `E0000047` (rate limit) — the values, over the
  generic `error-envelope` gene.
- **Policy choices**: `status==ACTIVE` issuance filter (Q7); 204-idempotent unassign (Q3,
  realized by the generic `unlink` feature but *chosen* here for Okta fidelity).
- **Default seed**: alice/bob/carol, Everyone/Admins groups, demo memberships, the
  `00u`/`00g` id prefixes, `STAGED` create default.
- **Kit-config defaults**: default `--issuer` value, which genes+features to compose.

### One-line rule

> **A capability is a mocksys feature (Tier 1) or gene (Tier 2) if a non-Okta twin would
> reuse it unchanged; it is okta-kit (Tier 3) if it encodes Okta's specific paths, claim
> values, error codes, or lifecycle policy.** Every new verb from this work is Tier 1; the
> `membership` gene and the `oidc-handshake` extension are Tier 2; only paths, claim
> values, JWKS/discovery bodies, error codes, the ACTIVE policy, and the seed are Tier 3.

---

## Revisiting later

- `dlktk replay <issue> -d jivog-lisas --as-of <T> --diff` — was a decision load-bearing at T?
- `dlktk check --all` in CI — exit 5 means a decision's position is no longer justified
  (someone added a defeating argument); re-argue or `supersede … --basis`.
- Q3's non-member idempotency is now **confirmed** by Okta's live integration test
  (`okta-sdk-dotnet/GroupApiTests.cs:554-555`); no live check outstanding.

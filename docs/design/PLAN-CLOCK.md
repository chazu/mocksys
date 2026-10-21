# Plan: virtual clock + rate-limit gene + a human walkthrough

Two deferred DTU primitives from `PLAN-DTU.md`, plus a narrative doc. They compose: a
rate-limit window is keyed on the virtual clock, so advancing the clock resets the window —
deterministic, no wall-clock dependence.

## Design

**Virtual clock.** A per-imposter virtual "now" (epoch seconds) that is *frozen* until you
advance it — the determinism the DTU goals want. It lives in `state.__now`, so effects read
it and it survives across requests. You move it by hitting a built-in control endpoint
(`/_mocksys/clock`) that mocksys adds to any clock-using twin; the `mocksys clock` command
drives that endpoint on the *running* twin (never reposts → never resets state).

- `state.clock: { start: <epoch|ISO> }` enables it (default a fixed epoch).
- New `bind` source `{ now: true | { add: N } | { iso: true } }` → virtual time into a var.
- `store: { …, ttl: N }` stores an entry with an expiry; `consume`/`lookup` treat an
  expired entry as a miss — so tokens/codes expire when the clock is advanced.
- CLI: `mocksys clock <name> [now | advance <1h|30m|45s|2d|secs> | set <epoch|ISO>]`.

**Rate-limit.** A new `limit` effect verb: a per-key counter over a clock-windowed bucket,
short-circuiting with `429` + `X-Rate-Limit-*` headers when the budget is exceeded. Runs
early (after `bind`), before the operation's real response. Exposed as a **gene** by
wiring it into the `okta` kit behind flags.

- `limit: { bucket, key, max, window, miss }` — window seconds use the virtual clock; the
  counter key embeds the window so it auto-resets at the boundary.
- `kit okta --rate-limit N [--rate-window S]` rate-limits the CRUD ops; `--token-ttl S`
  gives OIDC tokens a TTL (expire via the clock).

## Guardrails (checked after each step)

1. **No regression** — the 21-check smoke oracle stays green; default `kit okta`/`oauth2`
   are unchanged (clock/limit are opt-in; a twin with no clock use gets no control endpoint).
2. **Clear code** — heavy logic stays in the hand-written prelude; generated glue stays thin.
3. **Low-friction UX** — `clock`/`limit` are one obvious command/verb, in `prime`/`usage`,
   with helpful errors.

## Steps

1. `inject.cljs`: prelude `now`/`readBucket`; `bind` `now` source; `store` ttl; `consume`/
   `lookup` expiry; `limit` verb; `clock-control` injection; `contract-uses-clock?` +
   `clock-start`. `gen` inits `__now`.
2. `contract.cljs` `lower`: append the clock-control stub when the contract uses the clock;
   pass `clock-start` to `gen`.
3. `core.cljs`: `cmd-clock` (+ duration parsing); dispatch/usage/prime.
4. `kits.cljs`: `--rate-limit`/`--rate-window`/`--token-ttl` on `okta`; `rate-limit` +
   `virtual-clock` in the `genes` listing.
5. Verify: hand-authored clock+ttl+limit contract; extend the smoke oracle.
6. Docs: new `docs/walkthrough.md` (human-friendly narration of the Okta example, now
   incl. clock + rate-limit); update `effects.md`/`twins.md`/`cli.md`/`index.md`.

**Done when:** clock + rate-limit work end-to-end on the Okta twin (token expiry and a 429
both demonstrated by advancing the clock), the oracle is green, and the walkthrough doc
reads like a friendly tour of the whole system.

---

## Status — shipped

All steps implemented and verified against a live Mountebank; the smoke oracle is now
**27/27** (the prior 21 + 6 new clock/rate-limit checks), with default `kit okta`/`oauth2`
unchanged (no clock endpoint, not rate-limited).

- `inject.cljs`: `now`/`readBucket` prelude; `now` bind source; `store.ttl` + TTL-expiry on
  `consume`/`lookup`; the `limit` verb (clock-windowed counter → 429 + `X-Rate-Limit-*`);
  `clock-control-stub`; `contract-uses-clock?`/`clock-start`. `gen` inits `__now`.
- `contract.cljs`: appends the `/_mocksys/clock` control stub when a contract uses the clock.
- `core.cljs`: `cmd-clock` (`now`/`advance <dur>`/`set <ts>`); duration/timestamp parsing.
- `kits.cljs`: `--rate-limit`/`--rate-window`/`--token-ttl` on the `okta` kit; `rate-limit`
  + `virtual-clock` genes listed.
- Verified: token issued with `expires_in`, valid before TTL, `401` after `clock advance 2h`;
  4th request over a budget of 3 → `429` with `X-Rate-Limit-*`; `clock advance` past the
  window resets it; per-client budgets are independent; `clock set <ISO>` works.
- Docs: new `docs/walkthrough.md` (human tour); `effects.md`/`twins.md`/`cli.md`/`index.md`
  updated; committed runnable `examples/` (`users.yaml`, `login-throttle.yaml`,
  `okta-corpus.json`).

Follow-up (done): `X-Rate-Limit-*` headers are now emitted on **every** response — the
`Remaining` count decrements as the budget is spent, then `0` on the `429`. A passing
`limit` stashes the budget into `V.__rl`; a `withRl` prelude helper merges it into whatever
response the operation returns (collection verbs and `respond`). A twin with no `limit`
carries no such headers (the helper is a no-op when `V.__rl` is unset).

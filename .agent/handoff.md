# Handoff — Bifrost LLM-gateway twin

**Last worked:** 2026-07-06 · **Branch:** main · **State:** committed as fcc6354 (working tree clean)

Building a mocksys digital twin of the **Bifrost LLM gateway** (maximhq/bifrost, Enterprise angle),
in the same TDD + gene-composition style as the okta twin. Full scoping + adversarial review live in
`PLAN-BIFROST-TWIN.md` (repo root): **§6** = build-order/slice status, **§8** = ranked engine/gene
backlog, **§9** = source-verified corrections.

**Streaming transport is ON HOLD** (user decision). Note: upstream main now ships native SSE + WebSockets (PLAN-RUNTIME-2 F), so the PLAN §8.9 blocker (no non-Mountebank responder) is gone — revisit.

## Done — Slices 1–3 (originally verified on Mountebank; rebased onto the native runtime and re-verified end-to-end)
1. **Chat contract** — `POST /v1/chat/completions`: OpenAI-shaped canned completion, `x-bf-vk` VK auth
   (`find` by `value` field + `status eq "active"` gate → 401), echoed `model`, minted `chatcmpl-` id,
   literal `usage`, `extra_fields` envelope.
2. **Control plane** — `crud-collection` for `/api/governance/virtual-keys` + `/api/providers`.
   Chat op reads the SAME live collection the control plane writes → create-a-key-then-use-it works.
3. **Rate limits** — per-VK budget on inference → `429` + Bifrost error envelope + OpenAI-style
   `x-ratelimit-*-requests` headers over the virtual clock (`--rate-limit N [--rate-window S]`).
   Required two engine/gene changes, both landed: **`limit` verb header names now configurable**
   via `headers {limit, remaining, reset}` (`effect.cljs` `run-limit`, okta defaults preserved) and **`crud-collection` rate-limit `"key-source"`/`"miss"`/`"headers"`** (string keys, like `"max"`/`"window"`)
   (PLAN §8.3 — fixes the latent bug where the gene always emitted a hardcoded Okta 429).

Impl: `src/mocksys/kits.cljs` (`bifrost` fn + entry in `builtin-kits`), `src/mocksys/effect.cljs` (`run-limit`).
Tests: `test/mocksys/bifrost_kit_test.cljs`. Suite (after rebase onto upstream native runtime): **335 tests / 1821 assertions green**.

## Pick up here
- **Finish Slice 3:** governance *hierarchy* — `teams` + `customers` collections + `membership` gene
  linking VK→Team and VK→Customer (the rate-limit half is done; this is the state-navigation half).
- **Slice 4:** drop-in provider prefixes (`/openai/v1/chat/completions`, `/anthropic/v1/messages`,
  `/genai/…`) — pure gene composition, no engine edit.
- **Slice 5:** canned SSE + `fault` reliability. **Slice 6:** enterprise (audit read-back via the
  proposed `append` verb, PLAN §8.1) + a `conform` corpus vs a real Bifrost.

## Verify
`npm test` (expect 335/1821 green). End-to-end: `npx shadow-cljs compile cli && node bin/mocksys kit
bifrost --scenario bifrost/twin [--rate-limit 2] && node bin/mocksys run bifrost/twin --port 9300`,
then curl `/v1/chat/completions` with `x-bf-vk: sk-bf-live-0000000000`. Clean up:
`node bin/mocksys stop bifrost/twin && rm -rf .mocks/bifrost`.

## Watch out for
- **Unverified shapes** (PLAN §7): `x-ratelimit-*` header names, `extra_fields` keys, and the error-body
  JSON are placeholders (mirror OpenAI) until conformed against a real Bifrost.
- **Carried gap:** created VKs use a client-supplied `value`, not an auto-minted `sk-bf-*` secret.

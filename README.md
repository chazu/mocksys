# mocksys

**Agent-native CLI that turns real API traffic into reusable, scrubbed mock fixtures.**

Point it at GitHub, Stripe, an internal service — anything HTTP — and it records the
real interactions, strips the secrets and the volatile noise, names the result, and
replays it offline. Built so an **agent** can stand up a faithful mock of an external
system in a few commands, then assert against it like a test oracle.

It's a thin, opinionated wrapper over Mountebank: Mountebank
is the runtime, kept entirely behind the CLI. You work in agent vocabulary —
*service, scenario, fixture, matcher, fault, assertion* — never in imposters and stubs.

```sh
mocksys init github
mocksys record github/create-issue          # starts a proxy → api.github.com
#   ...drive your app/curl at the printed proxy URL to capture traffic...
mocksys freeze github/create-issue           # scrub secrets + volatility, save a fixture
mocksys run github/create-issue              # host the mock — no real API involved
```

## Why

Recording real traffic gives you a *blob*. mocksys turns it into a **reusable capability**:

- **Secrets never hit disk.** Raw traffic lives only inside the running Mountebank
  daemon; `freeze` pulls it, redacts auth headers, and strips volatile headers
  (`Date`, `ETag`, request-ids) **in memory** before writing anything. The saved
  fixture is safe to commit and share.
- **Matchers don't overfit.** Recordings key on method + path only, and `doctor`
  flags anything that would make a fixture fire on fewer requests than it should.
- **Mocks are test oracles.** `assert` checks what the running mock actually received
  and exits non-zero on a miss, so it drops straight into a test script.
- **One shared library.** Every scenario lives in a git-backed store at `~/.mocksys`
  that's the same from every project, and shareable with a remote.

## Install

### Homebrew (standalone binary)

```sh
brew install chazu/mocksys/mocksys
```

A self-contained binary; the formula provisions Mountebank (it only needs `node`).

### npm

```sh
npm install -g mocksys      # ships prebuilt JS — no JVM needed to install
```

Requires **Node 18+** (for global `fetch`). Mountebank installs as a dependency.
`mocksys` starts and stops the Mountebank daemon for you; you never run `mb` directly.
Point `MOCKSYS_MB` at an `mb` executable to override how it's launched (otherwise it
uses `mb` on your `PATH`, falling back to `npx mb`).

## Develop / build

`mocksys` is ClojureScript compiled with [shadow-cljs](https://github.com/thheller/shadow-cljs).
Hacking on it needs **Node 18+** and a **JVM** (for shadow-cljs); installing it does not.

```sh
git clone https://github.com/chazu/mocksys.git
cd mocksys
npm install
npm run watch               # recompiles out/mocksys.js on save (keep running)
./bin/mocksys help          # node shim over the compiled output
```

One-shot build: `npm run build`. A standalone binary (no Node/JVM at runtime, but it
still drives Mountebank externally) is produced with [bun](https://bun.sh):

```sh
npm run binary             # -> dist/mocksys (native)
npm run binary all         # -> dist/mocksys-bun-{darwin,linux}-{arm64,x64}
```

Cut a release (build matrix + tarballs + checksums + patch the Homebrew formula):

```sh
scripts/release.sh v0.1.0 --publish   # also creates the GitHub release via gh
```

The Homebrew tap lives in `packaging/homebrew/` — see its README for publishing.

## The core loop

```sh
mocksys init github                          # one-time: load the service profile
mocksys record github/create-issue           # proxy that records → the real API
#   ... exercise the proxy URL it prints ...
mocksys inspect github/create-issue          # agent-readable summary of what was captured
mocksys freeze github/create-issue           # scrub + save as a replay fixture
mocksys run github/create-issue              # host the mock (prints a port)
```

Service templates (`github`, `gitlab`, `aws-s3`, `stripe`, `generic-http`) supply the
default target, which headers carry secrets, which are volatile, and which env var the
consumer reads — so a scenario named `service/name` inherits all of it.

## Wire a test or app to a mock

```sh
eval "$(mocksys env github/create-issue)"    # exports GITHUB_API_URL=http://localhost:PORT, etc.
# point your code at $GITHUB_API_URL, run it, then verify what it called:
mocksys assert github/create-issue --saw 'POST /repos/*/issues'   # exit 1 on miss
mocksys requests github/create-issue         # everything the mock received
```

## Author from a spec, docs, or source — no recording

Recording is one way in. The other is a **contract**: a human/agent-editable
`contract.yaml` that mocksys *compiles* to the Mountebank imposter. The contract is
the source of truth; `imposter.json` is a build artifact that `run` recompiles
automatically when the contract changes.

**From an OpenAPI/Swagger spec** — one scenario per operation, the formal schemas
kept primary, every spec example imported *by name* (plus a schema-sampled
`generated` example to fill gaps), and a service profile synthesized from the spec's
servers + security schemes:

```sh
mocksys import openapi ./stripe.yaml --service stripe
mocksys examples stripe/createCharge          # see the named examples; [x] = in play
mocksys use stripe/createCharge --op createCharge --example card_declined --only
mocksys run stripe/createCharge
```

**By hand, after reading API docs or source** — scaffold a contract and edit it, or
append endpoints with `add`:

```sh
mocksys new acme/get-widget                   # writes a contract.yaml to edit
mocksys add acme/list-widgets --request 'GET /widgets' --status 200 --body w.json
mocksys validate acme/list-widgets            # structural + example-vs-schema check
```

Selecting examples is how you author variants: each `select: true` example becomes a
response the mock serves (Mountebank round-robins multiple), so swapping the happy
path for an error is just `use ... --example <error> --only`. Transport faults layer
on top and are stored on the contract, so they survive recompiles:

```sh
mocksys fault github/create-issue --latency 2000   # or --status 500 / --timeout / --drop-connection / --clear
mocksys parameterize github/create-issue --path '/repos/{owner}/{repo}/issues'   # exact path -> {param} template
```

Recorded scenarios are contract-canonical too: `freeze` lifts a contract from the
scrubbed recording, and any legacy imposter-only scenario gets one the first time a
contract-aware command touches it.

## Shared store + sharing

All scenarios live in one git-backed library at `~/.mocksys` (override with
`MOCKSYS_HOME`; set it to `./.mocks` to scope mocks to a single project).

```sh
mocksys home                                 # store path + git status
mocksys status                               # which scenarios are new/edited/removed
mocksys log --n 10                           # recent store history
mocksys publish github/create-issue          # git-commit a frozen scenario (inits the repo on first use)
mocksys rm github/create-issue               # delete a scenario (or a whole service); commits the removal
mocksys restore github/create-issue          # discard uncommitted local edits
mocksys remote git@github.com:org/mocks.git  # one-time: set the share remote
mocksys push     /     mocksys pull          # sync with teammates
```

Need git-less sharing? Bundle a scenario as a single self-describing file:

```sh
mocksys pack github/create-issue             # → github-create-issue.mock.tgz (includes the service profile)
mocksys unpack github-create-issue.mock.tgz  # restores it into another store
```

## Command reference

| | |
|---|---|
| `init <service>` | materialize a service template |
| `record <service/name> [--target URL] [--port N]` | start a recording proxy |
| `freeze <name>` | scrub + save the recording as a fixture |
| `run <name> [--port N] [--env VAR]` | host a fixture as a mock |
| `env <name>` | `eval "$(...)"` — wire a shell to the mock |
| `inspect <name>` | agent-readable fixture summary |
| `doctor <name> [--fix]` | lint matchers/volatility; `--fix` cleans in place |
| `requests <name> [--clear]` | what the running mock received |
| `assert <name> --saw 'METHOD /path'` | verify a call happened (exit 1 on miss) |
| `import openapi <spec> [--service NAME] [--target URL]` | spec → one scenario per operation |
| `new <service/name> [--service S]` | scaffold a blank contract to hand-author |
| `compile <name>` | rebuild `imposter.json` from `contract.yaml` (usually automatic) |
| `validate <name>` | structural + example-vs-schema check (exit 1 on errors) |
| `examples <name> [--op ID]` | list examples and which are in play |
| `use <name> --op ID --example NAME [--only \| --off]` | select example(s) to serve |
| `add <name> --request 'METHOD /path' [--status N] [--body FILE \| --text STR]` | author an endpoint |
| `fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection] [--clear]` | inject failures |
| `parameterize <name> --path '/a/{var}/b'` | loosen exact paths to templates |
| `pack <name> [--out FILE] \| --stdout` / `unpack <file>` | portable bundles |
| `ls` / `stop <name> \| --all` | catalog of scenarios / tear down |
| `rm <scenario\|service>` (alias `delete`) | delete from the store (stops it if live; commits the removal) |
| `status` / `log [--n N]` | uncommitted changes / recent store history |
| `restore <name> \| --all` | discard uncommitted local changes |
| `home` / `publish` / `remote` / `push` / `pull` | the git-backed store |
| `prime` | one-screen orientation for an agent |

Run `mocksys prime` to drop the whole workflow into an agent's context.

## How it's built

ClojureScript compiled with [shadow-cljs](https://github.com/thheller/shadow-cljs) to a
Node script (`out/mocksys.js`), shipped as prebuilt JS over npm and as a
`bun build --compile` binary via Homebrew — same Node runtime as Mountebank. Focused
modules: `mb` (admin-API client + daemon lifecycle), `store` (disk layout), `scrub`
(redaction/volatility), `analyze` (matcher hygiene), `service` (templates), `contract`
(the canonical source format — compile/lift/validate/example selection), `openapi`
(spec import, via `@apidevtools/swagger-parser` + `openapi-sampler`), `bundle`
(pack/unpack), `git` (the shared store), `core` (CLI).

## License

[MIT](LICENSE) © 2026 Chaz Straney

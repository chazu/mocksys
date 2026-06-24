# mocksys

**Agent-native CLI that turns real API traffic into reusable, scrubbed mock fixtures.**

Point it at GitHub, Stripe, an internal service — anything HTTP — and it records the
real interactions, strips the secrets and the volatile noise, names the result, and
replays it offline. Built so an **agent** can stand up a faithful mock of an external
system in a few commands, then assert against it like a test oracle.

It's a thin, opinionated wrapper over [Mountebank](https://www.mbtest.org/): Mountebank
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

Requires **Node 18+** (for global `fetch`). Mountebank comes in as a dependency.

```sh
git clone https://github.com/chazu/mocksys.git
cd mocksys
npm install                 # pulls nbb, mountebank, js-yaml
```

Then run via the shim, and optionally put it on your `PATH`:

```sh
./bin/mocksys help
ln -s "$PWD/bin/mocksys" /usr/local/bin/mocksys   # optional
```

`mocksys` starts and stops the Mountebank daemon for you; you never run `mb` directly.

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

## Author without recording

For failure modes and edge cases you can't easily capture:

```sh
mocksys add github/rate-limited --request 'GET /rate_limit' --status 403 --body rl.json
mocksys fault github/create-issue --status 500          # or --latency 2000 / --timeout / --drop-connection
mocksys parameterize github/create-issue --path '/repos/{owner}/{repo}/issues'
```

## Shared store + sharing

All scenarios live in one git-backed library at `~/.mocksys` (override with
`MOCKSYS_HOME`; set it to `./.mocks` to scope mocks to a single project).

```sh
mocksys home                                 # store path + git status
mocksys publish github/create-issue          # git-commit a frozen scenario (inits the repo on first use)
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
| `add <name> --request 'METHOD /path' [--status N] [--body FILE \| --text STR]` | author an endpoint |
| `fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection]` | inject failures |
| `parameterize <name> --path '/a/{var}/b'` | loosen exact paths to templates |
| `pack <name> [--out FILE] \| --stdout` / `unpack <file>` | portable bundles |
| `ls` / `stop <name> \| --all` | catalog of scenarios / tear down |
| `home` / `publish` / `remote` / `push` / `pull` | the git-backed store |
| `prime` | one-screen orientation for an agent |

Run `mocksys prime` to drop the whole workflow into an agent's context.

## How it's built

ClojureScript on [nbb](https://github.com/babashka/nbb) (interpreted, Node runtime —
same runtime as Mountebank, instant startup, no build step). ~1.2k lines across focused
modules: `mb` (admin-API client + daemon lifecycle), `store` (disk layout), `scrub`
(redaction/volatility), `analyze` (matcher hygiene), `service` (templates), `bundle`
(pack/unpack), `git` (the shared store), `core` (CLI).

## License

None yet — add a `LICENSE` before depending on this externally.

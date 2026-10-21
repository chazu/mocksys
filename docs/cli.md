# CLI reference

Every command. Run `mocksys help` for the terse flag list, `mocksys prime` for an agent
orientation. A scenario id is `service/name`. Commands that start a mock recompile
`imposter.json` from the contract first.

Global: the store is `$MOCKSYS_HOME` (else `~/.mocksys`); user kits live in its `kits/`. Commands that serve a mock
start the mocksys daemon if it is not running. The daemon serves the store it was
started from, and writes an admin token to `$MOCKSYS_HOME/daemon-<port>.token`. Its
admin API is `127.0.0.1:2626` for the default store; any other store gets its own daemon
on a port in 26000–26999 derived from the store's path, which `mocksys home` prints.
`MOCKSYS_ADMIN_PORT` picks the port instead. A command that reaches a daemon of another
store, or of another user, fails with an error saying so. A daemon left running by another mocksys
version is stopped and replaced, with a line on stderr that names the mocks it was
serving; run them again. The daemon writes its output to `$MOCKSYS_HOME/daemon.log`,
and a failed start shows the end of that log. Each running mock keeps its newest 10000
requests. To change that, set `MOCKSYS_MAX_RECORDED_REQUESTS` to a whole number above 0
on the command that starts the daemon (run `mocksys daemon stop` first); the daemon reads
it only when it starts, and does not start on 0 or a value that is not a whole number.
`MOCKSYS_NO_INJECTION=1` refuses
stateful scenarios and scenarios that list `modules`.

## Twins — full clones in one command

| Command | Notes |
|---|---|
| `kit okta [--users FILE.json] [--rate-limit N] [--rate-window S] [--token-ttl S] [--issuer URL] [--signing-key FILE.pem] [--scenario NAME] [--port N]` | Generate an Okta twin (users/groups CRUD + OIDC). `--rate-limit` adds 429s (window `--rate-window`, default 60s); `--token-ttl` expires OIDC tokens on the virtual clock. `--signing-key` signs JWTs with your PEM instead of a key generated per `kit` run; `--issuer` is the issuer used when a request has no `Host`. |
| `kit oauth2 [--provider github\|codeberg] [--users FILE.json] [--token-ttl S] [--scenario NAME] [--port N]` | Generate an OAuth2 provider twin. `--token-ttl` expires tokens on the virtual clock. |
| `kit <name> [--<flag> VALUE ...] [--scenario NAME] [--port N]` | Generate a twin from any kit, built in or in `$MOCKSYS_HOME/kits/*.yaml`. A value is parsed by the flag's type (`string`, `number`, `boolean`, `file` or `json`), and a non-boolean flag needs a value. A flag the kit doesn't declare, such as `--rate-limit` on `oauth2`, is an error. See [twins.md](twins.md#writing-a-kit). |
| `kits` | List every kit (built in and from `$MOCKSYS_HOME/kits`) with its source, flags and defaults. Warns about a user kit that shadows a built-in or fails to parse. |
| `genes` | List each gene a contract can name under `genes:`, with its params (`*` = required), plus the capabilities that are built in rather than genes. See [contracts.md](contracts.md#genes). |
| `conform <name> --corpus FILE [--against URL] [--ignore k1,k2] [--headers] [--port N]` | Replay a corpus at the twin (and, with `--against`, the live service); diff; exit 1 on drift. Omit `--corpus` to replay recorded traffic. Refuses a grpc mock. See [twins.md](twins.md). |
| `clock <name> [advance <dur> \| set <ts>]` | Read or move the twin's deterministic virtual clock. A grpc twin's clock works the same way. `advance 1h`/`30m`/`45s`/`2d`/`<secs>`; `set <epoch\|ISO>`. The twin must be running. See [effects.md](effects.md#the-virtual-clock). |

## Author

| Command | Notes |
|---|---|
| `new <service/name> [--service S]` | Scaffold a blank `contract.yaml` to hand-author. |
| `add <name> --request 'METHOD /path' [--status N] [--body FILE \| --text STR] [--header 'K=V,..'] [--when-query 'k=v,present,..'] [--when-header 'k=v,..']` | Append an operation. `--header` sets response headers (e.g. a redirect `Location`); `--when-query`/`--when-header` add request-conditional branching (a bare key = presence, `k=v` = equals). |
| `import openapi <spec> [--service NAME] [--target URL]` | One scenario per operation, with schemas + named examples and a synthesized service profile. |
| `examples <name> [--op ID]` | List operations → responses → examples (`[x]` = selected). Gene operations are marked `[gene NAME]`. |
| `use <name> --op ID --example NAME [--off] [--only]` | (De)select an example. `--only` clears the operation's others first (happy↔error swap). Refuses a gene operation and names the gene. |
| `fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection] [--malformed-json] [--clear]` | Overlay a transport fault (stored on the contract). |
| `parameterize <name> --path '/a/{var}/b'` | Loosen matching exact paths to a `{param}` template. Refuses when the template would change a gene operation. |
| `validate <name>` | Structural + example-vs-schema check over the expanded operations; an unknown gene or gene param is an error. For a grpc contract, it also loads the proto and checks that each `rpc` exists and is unary. Exit 1 on errors. |
| `compile <name>` | Rebuild `imposter.json` from `contract.yaml` (normally automatic). |

## Run & verify

| Command | Notes |
|---|---|
| `run <name> [--port N] [--env VAR] [--watch]` | Host the mock; prints the port + export lines. `--watch` recompiles+reloads on edits to `contract.yaml` and, for a grpc contract, its proto and the imports inside the scenario directory. An explicit `--port` is pinned onto the contract. A grpc mock's address is `localhost:PORT`, with no scheme, in `run`, `env` and `up`. |
| `env <name> [--json \| --tilt] [--port N] [--env VAR]` | Wire a consumer to the mock. Default: eval-safe `export` lines (`eval "$(mocksys env NAME)"`). `--json`: `{scenario, port, url, <vars>}`. `--tilt`: a Tiltfile `local_resource` (pins a port; doesn't start). |
| `inspect <name>` | Agent-readable summary: calls, matchers, redactions, volatile fields. Shows friendly `/users/{id}` paths. |
| `doctor <name> [--fix]` | Lint matcher hygiene / frozen volatile values. `--fix` scrubs the contract's response headers and recompiles. Overfit is only flagged on recorded scenarios. |
| `requests <name> [--clear]` | What the running mock received (`--clear` resets). The mock keeps the newest requests, and the header says how many older ones were dropped. |
| `assert <name> --saw 'METHOD /path'` | Verify a call happened (path may use `*`). Exit 1 on miss, saying so when older requests were dropped from the log and the call may have been one of them. A gRPC call is `POST /pkg.Service/Method`, as `requests` shows it. |
| `ls` | All scenarios + which are live. |
| `stop <name> \| --all` | Tear down a running scenario (or all). |
| `daemon stop` | Stop the mocksys daemon. Every mock it serves stops with it. The next command that serves a mock starts a new daemon. |

## Stacks — bring up a named set together

| Command | Notes |
|---|---|
| `stack <name> <scenario>...` | Define/replace a stack. `stack <name>` alone shows it. |
| `up <stack>` | Bring every scenario up (each on its pinned/auto port) with a combined env block. |
| `down <stack>` | Stop every scenario in the stack. |

## Seed from live traffic (needs real access)

| Command | Notes |
|---|---|
| `init <service>` | Materialize a service profile: `github` \| `gitlab` \| `aws-s3` \| `stripe` \| `generic-http`. |
| `record <service/name> [--target URL] [--port N]` | Start a recording proxy to the target. |
| `freeze <name> [--draft]` | Scrub secrets + volatility (in memory) and lift a contract from the recording. `--draft` lands it for review (then `promote`). |
| `promote <name>` | Make a reviewed draft the canonical contract. |

## Pack / share

| Command | Notes |
|---|---|
| `pack <name> [--out FILE] \| --stdout` | Bundle a scenario as a self-describing `.mock.tgz` (includes the service profile). A grpc scenario's proto travels in its directory; `pack` refuses a proto that reads files outside it. |
| `unpack <file.mock.tgz>` | Restore a bundle into the store. |

## Store (git-backed `~/.mocksys`)

| Command | Notes |
|---|---|
| `home` | Store path + git status. |
| `status` | Uncommitted changes (which scenarios are new/edited/removed). |
| `log [--n N]` | Recent store history. |
| `publish [<name>\|<service>]` | Git-commit frozen scenarios (inits the repo on first use). |
| `rm <scenario\|service>` (alias `delete`) | Delete from the store (stops it if live; commits the removal). |
| `restore <name> \| --all` | Discard uncommitted local changes. |
| `remote <git-url>` | Set the share remote. |
| `push` / `pull` | Sync the store with the remote. |

## Orientation

| Command | Notes |
|---|---|
| `prime` | One-screen agent orientation / cheatsheet. |
| `help` | The terse flag list. |

## Argument parsing

`--flag value` becomes an option; a bare `--flag` (followed by another flag or nothing) is
boolean `true`. Positional arguments are everything else, in order. For `--header` /
`--when-query` / `--when-header`, the value is a comma-separated `K=V` list (a bare `K`
means presence). A malformed `contract.yaml` produces a clean one-line error, not a stack
trace.

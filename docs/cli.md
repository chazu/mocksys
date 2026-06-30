# CLI reference

Every command. Run `mocksys help` for the terse flag list, `mocksys prime` for an agent
orientation. A scenario id is `service/name`. Commands that read a contract recompile a
stale `imposter.json` automatically.

Global: the store is `$MOCKSYS_HOME` (else `~/.mocksys`). `MOCKSYS_MB` overrides how the
`mb` daemon is launched. `MOCKSYS_NO_INJECTION=1` refuses stateful scenarios.

## Twins — full clones in one command

| Command | Notes |
|---|---|
| `kit okta [--users FILE.json] [--rate-limit N] [--rate-window S] [--token-ttl S] [--scenario NAME] [--port N]` | Generate an Okta twin (users/groups CRUD + OIDC). `--rate-limit` adds 429s; `--token-ttl` expires OIDC tokens on the virtual clock. |
| `kit oauth2 [--provider github\|codeberg] [--users FILE.json] [--scenario NAME] [--port N]` | Generate an OAuth2 provider twin. |
| `genes` | List the behavioral genes a kit composes, and the kits available. |
| `conform <name> --corpus FILE [--against URL] [--ignore k1,k2] [--headers] [--port N]` | Replay a corpus at the twin (and, with `--against`, the live service); diff; exit 1 on drift. Omit `--corpus` to replay recorded traffic. See [twins.md](twins.md). |
| `clock <name> [advance <dur> \| set <ts>]` | Read or move the twin's deterministic virtual clock. `advance 1h`/`30m`/`45s`/`2d`/`<secs>`; `set <epoch\|ISO>`. The twin must be running. See [effects.md](effects.md#the-virtual-clock). |

## Author

| Command | Notes |
|---|---|
| `new <service/name> [--service S]` | Scaffold a blank `contract.yaml` to hand-author. |
| `add <name> --request 'METHOD /path' [--status N] [--body FILE \| --text STR] [--header 'K=V,..'] [--when-query 'k=v,present,..'] [--when-header 'k=v,..']` | Append an operation. `--header` sets response headers (e.g. a redirect `Location`); `--when-query`/`--when-header` add request-conditional branching (a bare key = presence, `k=v` = equals). |
| `import openapi <spec> [--service NAME] [--target URL]` | One scenario per operation, with schemas + named examples and a synthesized service profile. |
| `examples <name> [--op ID]` | List operations → responses → examples (`[x]` = selected). |
| `use <name> --op ID --example NAME [--off] [--only]` | (De)select an example. `--only` clears the operation's others first (happy↔error swap). |
| `fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection] [--malformed-json] [--clear]` | Overlay a transport fault (stored on the contract). |
| `parameterize <name> --path '/a/{var}/b'` | Loosen matching exact paths to a `{param}` template. |
| `validate <name>` | Structural + example-vs-schema check. Exit 1 on errors. |
| `compile <name>` | Rebuild `imposter.json` from `contract.yaml` (normally automatic). |

## Run & verify

| Command | Notes |
|---|---|
| `run <name> [--port N] [--env VAR] [--watch]` | Host the mock; prints the port + export lines. `--watch` recompiles+reloads on `contract.yaml` edits. An explicit `--port` is pinned onto the contract. |
| `env <name> [--json \| --tilt] [--port N] [--env VAR]` | Wire a consumer to the mock. Default: eval-safe `export` lines (`eval "$(mocksys env NAME)"`). `--json`: `{scenario, port, url, <vars>}`. `--tilt`: a Tiltfile `local_resource` (pins a port; doesn't start). |
| `inspect <name>` | Agent-readable summary: calls, matchers, redactions, volatile fields. Shows friendly `/users/{id}` paths. |
| `doctor <name> [--fix]` | Lint matcher hygiene / frozen volatile values. `--fix` scrubs in place. Overfit is only flagged on recorded scenarios. |
| `requests <name> [--clear]` | What the running mock received (`--clear` resets). |
| `assert <name> --saw 'METHOD /path'` | Verify a call happened (path may use `*`). Exit 1 on miss. |
| `ls` | All scenarios + which are live. |
| `stop <name> \| --all` | Tear down a running scenario (or all). |

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
| `pack <name> [--out FILE] \| --stdout` | Bundle a scenario as a self-describing `.mock.tgz` (includes the service profile). |
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

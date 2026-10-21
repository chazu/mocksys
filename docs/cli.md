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
serving; run them again. The local CA for `tls` mocks is in `$MOCKSYS_HOME/tls/` (see
[TLS](#tls)). The daemon writes its output to `$MOCKSYS_HOME/daemon.log`,
and a failed start shows the end of that log. Each running mock keeps its newest 10000
requests. To change that, set `MOCKSYS_MAX_RECORDED_REQUESTS` to a whole number above 0
on the command that starts the daemon (run `mocksys daemon stop` first); the daemon reads
it only when it starts, and does not start on 0 or a value that is not a whole number.
`MOCKSYS_NO_INJECTION=1` refuses
scenarios that list `modules`, the only code a scenario can run.

## Twins — full clones in one command

| Command | Notes |
|---|---|
| `kit okta [--users FILE.json] [--rate-limit N] [--rate-window S] [--token-ttl S] [--issuer URL] [--signing-key FILE.pem] [--scenario NAME] [--port N]` | Generate an Okta twin (users/groups CRUD + OIDC). `--rate-limit` adds 429s (window `--rate-window`, default 60s); `--token-ttl` expires OIDC tokens on the virtual clock. `--signing-key` signs JWTs with your PEM instead of a key generated per `kit` run; `--issuer` is the issuer used when a request has no `Host`. |
| `kit oauth2 [--provider github\|codeberg] [--users FILE.json] [--token-ttl S] [--scenario NAME] [--port N]` | Generate an OAuth2 provider twin. `--token-ttl` expires tokens on the virtual clock. |
| `kit <name> [--<flag> VALUE ...] [--scenario NAME] [--port N] [--force]` | Generate a twin from any kit, built in or in `$MOCKSYS_HOME/kits/` (`*.yaml`, or `<name>/kit.yaml`). A value is parsed by the flag's type (`string`, `number`, `boolean`, `file` or `json`), and a non-boolean flag needs a value. A flag the kit doesn't declare, such as `--rate-limit` on `oauth2`, is an error. A directory kit copies its modules into the scenario and prints that the scenario runs module code. When a module already in the scenario differs from the kit's copy, `kit` writes nothing; `--force` replaces it. Like any boolean flag, `--force` reads the next argument as its value, so it goes after the kit name. See [twins.md](twins.md#kits-that-ship-modules). |
| `kits` | List every kit (built in and from `$MOCKSYS_HOME/kits`) with its source, flags and defaults, and mark each kit that ships modules. Warns about a user kit that shadows a built-in or fails to parse, and about a single-file kit that lists modules. |
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
| `validate <name>` | Structural + example-vs-schema check over the expanded operations; an unknown gene or gene param is an error. For a grpc contract, it also loads the proto and checks that each `rpc` exists. Exit 1 on errors. |
| `compile <name>` | Rebuild `imposter.json` from `contract.yaml` (normally automatic). |

## Run & verify

| Command | Notes |
|---|---|
| `run <name> [--port N] [--env VAR] [--watch]` | Host the mock; prints the port + export lines. `--watch` recompiles+reloads on edits to `contract.yaml` and, for a grpc contract, its proto and the imports inside the scenario directory. An explicit `--port` is pinned onto the contract. A grpc mock's address is `localhost:PORT`, with no scheme, in `run`, `env` and `up`. A `tls` mock's address is `https://localhost:PORT`, and `run` prints the CA to trust with the commands for node and curl (or grpcurl). |
| `env <name> [--json \| --tilt] [--port N] [--env VAR]` | Wire a consumer to the mock. Default: eval-safe `export` lines (`eval "$(mocksys env NAME)"`). `--json`: `{scenario, port, url, <vars>}`. `--tilt`: a Tiltfile `local_resource` (pins a port; doesn't start). For a `tls: true` mock, the exports include `NODE_EXTRA_CA_CERTS` and the JSON includes `caCert`. When `NODE_EXTRA_CA_CERTS` already names another file, `env` leaves it and says on stderr to add the CA to that file. `up` does the same for a stack. |
| `tls ca` | Print the path of the local CA certificate, `$MOCKSYS_HOME/tls/ca.pem`, creating the CA on first use. Only the path goes to stdout, so `export NODE_EXTRA_CA_CERTS="$(mocksys tls ca)"` works; stderr shows how other clients trust it. See [TLS](#tls). |
| `inspect <name>` | Agent-readable summary: calls, matchers, redactions, volatile fields. Shows friendly `/users/{id}` paths. |
| `doctor <name> [--fix]` | Lint matcher hygiene / frozen volatile values. `--fix` scrubs the contract's response headers and recompiles. Overfit is only flagged on recorded scenarios. |
| `requests <name> [--clear]` | What the running mock received (`--clear` resets). The mock keeps the newest requests, and the header says how many older ones were dropped. |
| `assert <name> --saw 'METHOD /path'` | Verify a call happened (path may use `*`). Exit 1 on miss, saying so when older requests were dropped from the log and the call may have been one of them. A gRPC call is `POST /pkg.Service/Method`, as `requests` shows it. A message a client sends on a WebSocket is `MESSAGE /path`, the upgrade's path. |
| `ls` | All scenarios + which are live. |
| `stop <name> \| --all` | Tear down a running scenario (or all). |
| `daemon stop` | Stop the mocksys daemon. Every mock it serves stops with it. The next command that serves a mock starts a new daemon. |

## TLS

A contract with `tls: true` is served with a certificate for `localhost`, `127.0.0.1`
and `::1`, issued from a local CA when `run` posts it (see
[contracts.md](contracts.md#tls)). Each client must trust the CA:

| Client | How |
|---|---|
| node (`https`, `fetch`, grpc-js `createSsl()`) | `export NODE_EXTRA_CA_CERTS="$(mocksys tls ca)"` before node starts |
| curl | `curl --cacert "$(mocksys tls ca)" https://localhost:PORT/...` |
| grpc-js, explicitly | `grpc.credentials.createSsl(fs.readFileSync(CA))` |
| gRPC C core (Python, Ruby, C++) | `export GRPC_DEFAULT_SSL_ROOTS_FILE_PATH="$(mocksys tls ca)"` |
| grpcurl | `grpcurl -cacert "$(mocksys tls ca)" -proto FILE localhost:PORT list` |
| Browsers, every program of the user | Add the CA to the system trust store. macOS: `security add-trusted-cert -r trustRoot -k ~/Library/Keychains/login.keychain-db "$(mocksys tls ca)"`. Debian or Ubuntu: `sudo cp "$(mocksys tls ca)" /usr/local/share/ca-certificates/mocksys.crt && sudo update-ca-certificates`. Firefox keeps its own store. |

The CA:

- Lives in `$MOCKSYS_HOME/tls/`: `ca.pem` (the certificate to trust) and `ca.key`.
  The directory is mode 0700 and the key 0600, and mocksys tightens them again on
  each use. A `.gitignore` in the directory keeps both out of `publish` and out of a
  project repository that holds `MOCKSYS_HOME`. Never copy `ca.key` off the machine.
- Lasts ten years. A mock's certificate lasts 397 days at most, since some clients
  refuse a longer one, and a new one is issued on every `run`, so a mock's
  certificate never needs renewal.
- Is replaced when it expires within 30 days, or when `ca.pem` or `ca.key` is
  missing. mocksys renames the old directory to `tls.bak-<time>` beside it, so
  nothing is deleted, and stderr says so. Clients must then trust the new `ca.pem`.
  A mock that is already running keeps its certificate from the old CA until you
  `run` it again, so a client that trusts only the new CA refuses it until then.
- Is never replaced when a file is there but cannot be used: an unreadable file, a
  key that is not RSA, an encrypted key, or a key that does not match `ca.pem`.
  mocksys stops with an error that names the file. Fix the file, or move
  `$MOCKSYS_HOME/tls/` aside to make a new CA on the next `run` or `tls ca`. Moving
  it aside is also how to replace the CA on purpose.
- Belongs to one store. A project that sets `MOCKSYS_HOME=./.mocks` has its own CA.

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
| `record <service/name> [--target URL] [--port N] [--proto FILE]` | Start a recording proxy to the target. With `--proto`, record a gRPC upstream at `--target host:port` (no TLS); the proto and its imports are copied into the scenario. See [contracts.md](contracts.md#recording). |
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

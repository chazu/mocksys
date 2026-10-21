(ns mocksys.cli.help
  "Agent orientation (`prime`) and the usage screen."
  (:require [clojure.string :as str]))

(defn cmd-prime [_]
  (println
   (str/join
    "\n"
    ["# mocksys — local mocks and full digital twins of external systems"
     ""
     "You stand up a local mock of an external system (github, stripe, okta, an OAuth"
     "provider, ...) that an app/test hits instead of the real thing — from a fixed"
     "fixture up to a stateful *digital twin* (CRUD resources, auth flows, rate limits,"
     "expiring tokens). Vocabulary: a *scenario* (named `service/name`) is described by a"
     "canonical `contract.yaml` (method/path, response schemas, selectable *examples*, and"
     "an optional declarative `effect` for stateful behavior); it compiles to an imposter"
     "that the mocksys daemon serves, automatically on `run`. You *author* a contract (or generate one with a"
     "`kit`); recording live traffic is just one way to seed one, when you have creds."
     ""
     "## Stand up a digital twin in one command (the fastest path)"
     "A *kit* generates a whole behavioral clone — stateful, seeded, runnable:"
     "  mocksys kit okta                              # Okta twin: users+groups CRUD, SCIM filter, OIDC"
     "  mocksys kit oauth2 --provider github          # OAuth2 provider: picker, single-use codes, userinfo"
     "  mocksys kits                                  # every kit + its flags (yours: $MOCKSYS_HOME/kits/*.yaml)"
     "  mocksys genes                                 # reusable genes + their params (list them under `genes:`)"
     "A twin is one stateful scenario sharing one port/state; `run` it like any other."
     ""
     "## Or author a mock yourself (no live access needed)"
     "  mocksys import openapi ./openapi.yaml --service stripe   # one scenario per operation"
     "  mocksys new acme/get-widget                   # scaffold, then edit contract.yaml"
     "  mocksys add acme/get-widget --request 'GET /widgets/1' --status 200 --body w.json"
     "Compose genes in contract.yaml; they expand into operations after your own:"
     "  genes: [{crud-collection: {coll: users, base: /api/v1/users, seed-name: users}}]"
     "Stateful resources (a REST collection) are declarative — no JavaScript:"
     "  state.collections: {users: {idField: id, seed: users}}   # in contract.yaml"
     "  effect: {bind: {...}, create|get|update|remove|list: {collection: users, ...}}"
     "Logic no verb expresses (an HMAC check) is a custom verb in a JS file beside the contract:"
     "  modules: [./verbs.js]   effect: {call: {verb: verifySignature, args: {sig: \"{sig}\"}, as: event}}"
     "Each operation keeps its schema plus *named examples* you select to serve:"
     "  mocksys examples stripe/createcharge          # list examples, [x]=in play"
     "  mocksys use stripe/createcharge --op createcharge --example card_declined --only"
     "  mocksys validate stripe/createcharge          # schema/structure check"
     ""
     "## Run it & wire a test/app to the mock"
     "  mocksys run acme/get-widget                    # host the mock (prints a port)"
     "  eval \"$(mocksys env acme/get-widget)\"          # exports BASE_URL=... etc"
     "  # point your code at the exported URL, run it, then assert what it called:"
     "  mocksys assert acme/get-widget --saw 'GET /widgets/*'   # exit 1 on miss"
     ""
     "## Validate fidelity against reality (the digital-twin loop)"
     "  mocksys conform okta/okta --corpus calls.json            # golden: status vs expect"
     "  mocksys conform okta/okta --corpus calls.json --against https://your.okta.com"
     "Replays the same requests at the twin and (with creds) the live service, diffs"
     "responses (normalized by volatile fields), and exits 1 on drift — drive a twin to"
     "zero observable delta. corpus = a JSON array of {method, path, body?, expect?}."
     ""
     "## Branch on request content (several ops share a path, first match wins)"
     "  mocksys add gh/auth --request 'GET /authorize' --status 302 \\"
     "      --when-query 'login' --header 'Location=https://app/cb?code=abc'   # ?login present"
     "  mocksys add gh/auth --request 'GET /authorize' --status 200 --text '<picker/>'  # fallback"
     ""
     "## Stateful flows, time & failure modes"
     "  mocksys kit oauth2 --provider github           # code→token→userinfo handshake (stateful)"
     "  mocksys kit okta --rate-limit 100 --token-ttl 3600   # 429s + tokens that expire on the clock"
     "  mocksys clock okta/okta advance 1h            # move the deterministic virtual clock (expiry, windows)"
     "  mocksys fault github/create-issue --latency 2000   # or --timeout --drop-connection --clear"
     "  mocksys parameterize github/create-issue --path '/repos/{owner}/{repo}/issues'"
     ""
     "## Seed a contract from live traffic (optional — needs real access)"
     "  mocksys init github                            # load the service profile (redact/target)"
     "  mocksys record github/create-issue             # proxy → api.github.com; drive traffic at it"
     "  mocksys freeze github/create-issue             # scrub secrets+volatility → a contract"
     "Secrets are redacted and volatile headers stripped on freeze, in memory, so"
     "nothing unscrubbed is ever written to disk — the store is safe to commit."
     ""
     "## Inspect / manage"
     "  mocksys inspect <name>     # agent-readable summary of a scenario"
     "  mocksys doctor  <name>     # lint matchers/volatility; --fix to clean"
     "  mocksys requests <name>    # what the running mock actually received"
     "  mocksys ls                 # all scenarios + which are live"
     "  mocksys stop <name>        # or --all"
     "  mocksys daemon stop        # stop the daemon (and every running mock)"
     ""
     "## Store + sharing"
     "All scenarios live in one shared library at ~/.mocksys (a git repo; override"
     "with MOCKSYS_HOME). It's the same library from every project."
     "  mocksys home                          # store path + git status"
     "  mocksys status / log                  # uncommitted changes / recent history"
     "  mocksys publish github/create-issue   # git-commit a scenario"
     "  mocksys rm github/create-issue        # delete a scenario (or a whole service)"
     "  mocksys restore github/create-issue   # discard uncommitted local edits"
     "  mocksys remote git@host:org/mocks.git # one-time: set the share remote"
     "  mocksys push   /  mocksys pull        # sync with teammates"
     "  mocksys pack <name>                   # or a standalone .mock.tgz (no git needed)"
     ""
     "Run `mocksys help` for the full flag list."])))

(defn usage []
  (println "mocksys — author reusable mock fixtures for external systems")
  (println)
  (println "  TWINS (full behavioral clones in one command — see `mocksys genes`)")
  (println "  mocksys kit okta [--users F.json] [--rate-limit N] [--rate-window S] [--token-ttl S] [--scenario NAME] [--port N]")
  (println "  mocksys kit oauth2 [--provider github|codeberg] [--users F.json] [--token-ttl S] [--port N]   OAuth2 provider twin")
  (println "  mocksys kit <name> [--<flag> VALUE ...]         any kit, built in or from $MOCKSYS_HOME/kits/*.yaml")
  (println "  mocksys kits                                    list kits (built in and yours) with their flags")
  (println "  mocksys genes                                   list the genes (and their params) a contract or kit composes")
  (println "  mocksys conform <name> --corpus FILE [--against URL] [--ignore k1,k2]   replay+diff (exit 1 on drift)")
  (println "  mocksys clock <name> [advance <1h|30m|45s> | set <ts>]   read/move a twin's deterministic virtual clock")
  (println)
  (println "  AUTHOR (hand-author or import — no live access needed)")
  (println "  mocksys new <service/name> [--service S]        scaffold a contract to hand-author")
  (println "  mocksys import openapi <spec> [--service NAME] [--target URL]   spec -> one scenario per operation")
  (println "  mocksys add <name> --request 'METHOD /path' [--status N] [--body FILE | --text STR]")
  (println "      [--header 'K=V,..'] [--when-query 'k=v,..'] [--when-header 'k=v,..']   response hdrs / request branching")
  (println "  mocksys examples <name> [--op ID]               list examples + which are in play")
  (println "  mocksys use <name> --op ID --example NAME [--only|--off]   select example(s) to serve")
  (println "  mocksys fault <name> [--status N] [--latency MS] [--timeout] [--drop-connection] [--clear]")
  (println "  mocksys parameterize <name> --path '/a/{var}/b'")
  (println "  mocksys validate <name>                         check the contract (exit 1 on errors)")
  (println "  mocksys compile <name>                          rebuild imposter.json from contract.yaml")
  (println)
  (println "  RUN / VERIFY")
  (println "  mocksys run <name> [--port N] [--env VAR] [--watch]   --watch: recompile+reload on edit")
  (println "  mocksys env <name> [--json|--tilt]              eval \"$(mocksys env <name>)\"; or emit JSON / a Tiltfile resource")
  (println "  mocksys stack <name> <scenario>...              define a stack (a set of scenarios)")
  (println "  mocksys up <stack> | down <stack>               bring a whole stack up/down with combined env")
  (println "  mocksys inspect <name>")
  (println "  mocksys doctor <name> [--fix]")
  (println "  mocksys requests <name> [--clear]")
  (println "  mocksys assert <name> --saw 'METHOD /path'")
  (println "  mocksys ls")
  (println "  mocksys stop <name> | --all")
  (println "  mocksys daemon stop                             stop the daemon and every mock it serves")
  (println "  mocksys rm <scenario|service>                   delete from the store (stops it if live)")
  (println)
  (println "  SEED FROM LIVE TRAFFIC (optional — needs real access)")
  (println "  mocksys init <service>                          github | gitlab | aws-s3 | stripe | generic-http")
  (println "  mocksys record <service/name> [--target URL] [--port N]")
  (println "  mocksys freeze <name> [--draft]                 --draft: land for review, then `promote`")
  (println "  mocksys promote <name>                          make a reviewed draft canonical")
  (println)
  (println "  PACK / SHARE")
  (println "  mocksys pack <name> [--out FILE] | --stdout")
  (println "  mocksys unpack <file.mock.tgz>")
  (println)
  (println "  STORE (git-backed shared library)")
  (println "  mocksys home                                    show the store path + git status")
  (println "  mocksys status                                  list uncommitted changes in the store")
  (println "  mocksys log [--n N]                             recent store history")
  (println "  mocksys restore <name> | --all                 discard uncommitted local changes")
  (println "  mocksys publish [<name>|<service>]              git-commit frozen scenarios")
  (println "  mocksys remote <git-url>                        set the share remote")
  (println "  mocksys push | pull                             sync the store with the remote")
  (println "  mocksys prime                                   agent orientation / cheatsheet"))


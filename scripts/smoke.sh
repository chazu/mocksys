#!/usr/bin/env bash
# End-to-end smoke test: drives the built CLI (out/mocksys.js) against its own
# daemon in a throwaway store. The daemon gets a private admin port, so a daemon
# you already run is left alone; it is stopped on exit.
#
#   npm run build && scripts/smoke.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MS="${MOCKSYS_BIN:-$ROOT/bin/mocksys}"
# the checks run from a temp dir, so MOCKSYS_BIN=dist/mocksys must resolve from here
case "$MS" in /*) ;; *) MS="$PWD/$MS" ;; esac
PORT="${SMOKE_PORT:-9471}"

free_port() { node -e 'const s=require("net").createServer().listen(0,()=>{console.log(s.address().port);s.close()})'; }
export MOCKSYS_ADMIN_PORT="$(free_port)"
ADMIN="http://127.0.0.1:$MOCKSYS_ADMIN_PORT"

WORK="$(mktemp -d)"
export MOCKSYS_HOME="$WORK/store"
cd "$WORK"
cleanup() {
  "$MS" daemon stop >/dev/null 2>&1 || true
  [ -n "${ADMIN2:-}" ] && env -u MOCKSYS_ADMIN_PORT MOCKSYS_HOME="$WORK/store2" "$MS" daemon stop >/dev/null 2>&1 || true
  [ -n "${OLD_PID:-}" ] && kill "$OLD_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fails=0
check() { # <label> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "  ok   $1"
  else echo "  FAIL $1: expected [$2], got [$3]"; fails=$((fails + 1)); fi
}
status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
json_field() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const v=eval("(o)=>o"+process.argv[1])(JSON.parse(s));console.log(typeof v==="object"?JSON.stringify(v):v)})' "$1"; }

VERSION="$(node -p 'require(process.argv[1]).version' "$ROOT/package.json")"

echo "# a daemon from another version is replaced"
# Stands in for a daemon left by an older mocksys: it names itself, serves one mock,
# and exits on DELETE /.
node -e '
  const http = require("http");
  const srv = http.createServer((q, r) => {
    const reply = (b) => { r.setHeader("Content-Type", "application/json"); r.end(JSON.stringify(b)); };
    if (q.method === "GET" && q.url === "/") return reply({ name: "mocksys", version: "0.0.0-old", pid: process.pid });
    if (q.method === "GET" && q.url === "/imposters") return reply({ imposters: [{ port: 1, name: "old/mock" }] });
    if (q.method === "DELETE" && q.url === "/") { r.on("finish", () => process.exit(0)); return reply({ stopped: 1 }); }
    r.statusCode = 404; reply({});
  }).listen(+process.argv[1], "127.0.0.1");' "$MOCKSYS_ADMIN_PORT" &
OLD_PID=$!
until curl -s "$ADMIN/" >/dev/null 2>&1; do sleep 0.1; done
"$MS" add demo/hello --request 'GET /hello' --status 200 --text hi >/dev/null
"$MS" run demo/hello --port $PORT >/dev/null 2>"$WORK/run.err"
check "one stderr line names the old version and the lost mock" 1 \
  "$(grep -c "it runs mocksys 0.0.0-old, this is $VERSION); its 1 running mock(s) stop: old/mock" "$WORK/run.err")"
check "the daemon now runs this version" "$VERSION" "$(curl -s "$ADMIN/" | json_field '.version')"
"$MS" stop demo/hello >/dev/null

echo "# static mock"
"$MS" run demo/hello --port $PORT >/dev/null
check "serves the authored body" "hi" "$(curl -s localhost:$PORT/hello)"
"$MS" assert demo/hello --saw 'GET /hello' >/dev/null
check "assert sees the call" 0 $?
"$MS" stop demo/hello >/dev/null

echo "# daemon"
check "GET / identifies the mocksys daemon" mocksys "$(curl -s "$ADMIN/" | json_field '.name')"
check "the daemon logs to the store" 1 "$(grep -c "mocksys $VERSION daemon on http://127.0.0.1:$MOCKSYS_ADMIN_PORT" "$MOCKSYS_HOME/daemon.log")"
listeners() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -Fn | sed -n 's/^n//p' | LC_ALL=C sort | tr '\n' ' '; }
check "admin API listens on 127.0.0.1 only" "127.0.0.1:$MOCKSYS_ADMIN_PORT " "$(listeners "$MOCKSYS_ADMIN_PORT")"
"$MS" run demo/hello --port $PORT >/dev/null
if node -e 'require("net").createServer().on("error",()=>process.exit(1)).listen(0,"::1",function(){this.close()})'; then
  check "a mock listens on the loopback addresses only" "127.0.0.1:$PORT [::1]:$PORT " "$(listeners $PORT)"
else
  check "a mock listens on 127.0.0.1 only (no ::1 on this host)" "127.0.0.1:$PORT " "$(listeners $PORT)"
fi
check "the admin API refuses a browser origin" 403 \
  "$(status_of -H 'Origin: https://evil.example' "$ADMIN/imposters")"
TOKEN_FILE="$MOCKSYS_HOME/daemon-$MOCKSYS_ADMIN_PORT.token"
check "an admin call without the token is 401" 401 "$(status_of "$ADMIN/imposters")"
check "with the store's token it is served" 200 \
  "$(status_of -H "Authorization: Bearer $(cat "$TOKEN_FILE")" "$ADMIN/imposters")"
check "only the daemon's user can read the token" 600 \
  "$(node -p '(require("fs").statSync(process.argv[1]).mode & 0o777).toString(8)' "$TOKEN_FILE")"
out="$(MOCKSYS_HOME="$WORK/other" "$MS" ls 2>&1)" && code=0 || code=$?
check "a CLI on another store, set to this daemon's port, is refused" 1 "$code"
check "and told whose daemon it reached" 1 \
  "$(echo "$out" | grep -c "daemon on 127.0.0.1:$MOCKSYS_ADMIN_PORT belongs to another store or user")"
check "and its pid, to stop it" 1 "$(echo "$out" | grep -c "stop it (pid $(curl -s "$ADMIN/" | json_field '.pid'))")"
check "and how to run its own" 1 "$(echo "$out" | grep -c MOCKSYS_ADMIN_PORT)"
"$MS" stop demo/hello >/dev/null

echo "# okta twin"
"$MS" kit okta --port $PORT >/dev/null
"$MS" run okta/okta >/dev/null
check "list users" 200 "$(status_of localhost:$PORT/api/v1/users)"
check "SCIM eq filter" 200 "$(status_of -G localhost:$PORT/api/v1/users --data-urlencode 'filter=status eq "ACTIVE"')"
id=$(curl -s -X POST localhost:$PORT/api/v1/users -H 'Content-Type: application/json' \
       -d '{"profile":{"login":"smoke@example.com","email":"smoke@example.com"}}' | json_field '.id')
check "created user is readable" 200 "$(status_of localhost:$PORT/api/v1/users/$id)"
check "SCIM quoted value with 'and'" 200 \
  "$(status_of -G localhost:$PORT/api/v1/users --data-urlencode 'filter=profile.lastName eq "Smith and Sons"')"
check "SCIM gt over timestamps" 200 \
  "$(status_of -G localhost:$PORT/api/v1/users --data-urlencode 'filter=lastUpdated gt "2020-01-01T00:00:00.000Z"')"
check "malformed filter is Okta's E0000031" E0000031 \
  "$(curl -s -G localhost:$PORT/api/v1/users --data-urlencode 'filter=status zz "x"' | json_field '.errorCode')"
"$MS" conform okta/okta --corpus "$ROOT/examples/okta-corpus.json" >/dev/null
check "conform golden corpus" 0 $?

echo "# determinism"
"$MS" stop okta/okta >/dev/null
MOCKSYS_NO_INJECTION=1 "$MS" run okta/okta >/dev/null && code=0 || code=$?
check "MOCKSYS_NO_INJECTION still runs a stateful twin" 0 "$code"
id2=$(curl -s -X POST localhost:$PORT/api/v1/users -H 'Content-Type: application/json' \
        -d '{"profile":{"login":"smoke@example.com","email":"smoke@example.com"}}' | json_field '.id')
check "a fresh twin mints the same id for the same request" "$id" "$id2"

echo "# genes in contract.yaml"
P2=$((PORT + 1))
mkdir -p "$MOCKSYS_HOME/demo/directory"
cp "$ROOT/examples/directory.yaml" "$MOCKSYS_HOME/demo/directory/contract.yaml"
"$MS" run demo/directory --port $P2 >/dev/null
check "a gene-only twin filters its seed" '["u1","u2"]' \
  "$(curl -s -G localhost:$P2/api/v1/users --data-urlencode 'filter=status eq "ACTIVE"' | json_field '.map(u=>u.id)')"
check "the membership gene assigns" 204 "$(status_of -X PUT localhost:$P2/api/v1/groups/g1/users/u2)"
check "editing a gene operation is refused" 1 \
  "$("$MS" use demo/directory --op list-users --example x >/dev/null 2>&1 && echo 0 || echo 1)"
"$MS" stop demo/directory >/dev/null

echo "# yaml kits"
"$MS" kit oauth2 --port $P2 >/dev/null
"$MS" run oauth/github >/dev/null
loc=$(curl -s -o /dev/null -w '%{redirect_url}' \
        "localhost:$P2/github/login/oauth/authorize?login=octocat&state=s&redirect_uri=http://app/cb")
code=$(echo "$loc" | sed -n 's/.*code=\([^&]*\).*/\1/p')
tok=$(curl -s -X POST localhost:$P2/github/login/oauth/access_token -d "code=$code" | json_field '.access_token')
check "the oauth2 kit completes the handshake" octocat \
  "$(curl -s localhost:$P2/github/user -H "Authorization: Bearer $tok" | json_field '.login')"
check "an undeclared kit flag is refused" 1 "$("$MS" kit oauth2 --bogus 1 >/dev/null 2>&1 && echo 0 || echo 1)"
"$MS" stop oauth/github >/dev/null
mkdir -p "$MOCKSYS_HOME/kits"
cp "$ROOT/examples/kits/directory.yaml" "$MOCKSYS_HOME/kits/"
"$MS" kit directory --users-base /scim/v2/Users --port $P2 >/dev/null
"$MS" run directory/directory >/dev/null
check "a user kit's flag shapes the twin" 200 "$(status_of localhost:$P2/scim/v2/Users)"
"$MS" stop directory/directory >/dev/null

echo "# custom verbs"
P3=$((PORT + 2))
WH="$MOCKSYS_HOME/demo/webhooks"
mkdir -p "$WH"
cp "$ROOT/examples/webhooks/"* "$WH/"
hmac() { node -e 'console.log("sha256="+require("crypto").createHmac("sha256",process.argv[1]).update(process.argv[2]).digest("hex"))' "$@"; }
deliver() { # <port> <signature> <body>
  status_of -X POST "localhost:$1/webhooks" -H "X-Hub-Signature-256: $2" -H 'Content-Type: application/json' -d "$3"; }
body='{"action":"opened","number":7}'
out="$("$MS" validate demo/webhooks 2>&1)" && code=0 || code=$?
check "validate passes without running the module" 0 "$code"
check "and says when the verbs are checked" 1 "$(echo "$out" | grep -c 'verbs are checked when the scenario runs')"
out="$(MOCKSYS_NO_INJECTION=1 "$MS" run demo/webhooks --port $P3 2>&1)" && code=0 || code=$?
check "MOCKSYS_NO_INJECTION refuses a scenario with modules" 1 "$code"
check "and names them" 1 "$(echo "$out" | grep -c 'runs JavaScript from its modules (./verbs.js)')"
"$MS" run demo/webhooks --port $P3 >/dev/null
check "a delivery signed with the secret is accepted" 202 "$(deliver $P3 "$(hmac whsec_test "$body")" "$body")"
check "a forged signature is 401" 401 "$(deliver $P3 "$(hmac wrong "$body")" "$body")"
check "only the signed delivery was stored" '[7]' "$(curl -s localhost:$P3/deliveries | json_field '.map(d=>d.number)')"
sed -i.bak 's/signature does not match/edited/' "$WH/verbs.js"
"$MS" run demo/webhooks >/dev/null
check "an edited module applies on the next run" edited \
  "$(curl -s -X POST localhost:$P3/webhooks -H 'X-Hub-Signature-256: sha256=00' -d "$body" | json_field '.error')"
"$MS" stop demo/webhooks >/dev/null
"$MS" pack demo/webhooks --out "$WORK/webhooks.mock.tgz" >/dev/null
mv "$WH/verbs.js" "$MOCKSYS_HOME/outside.js"
out="$(MOCKSYS_HOME="$WORK/store2" "$MS" unpack "$WORK/webhooks.mock.tgz" 2>&1)"
check "unpack names the module code it brought" 1 \
  "$(echo "$out" | grep -c 'demo/webhooks runs JavaScript from its modules (./verbs.js)')"
store2() { env -u MOCKSYS_ADMIN_PORT MOCKSYS_HOME="$WORK/store2" "$MS" "$@"; }
ADMIN2="$(store2 home | sed -n 's/^daemon: *127\.0\.0\.1://p')"
check "home shows a second store's admin port, derived from its path" yes \
  "$([ "${ADMIN2:-0}" -ge 26000 ] && [ "$ADMIN2" -le 26999 ] && echo yes || echo no)"
store2 run demo/webhooks --port $P3 >/dev/null
check "a bundle carries the module" 202 "$(deliver $P3 "$(hmac whsec_test "$body")" "$body")"
check "the second store's daemon runs beside the first" "mocksys mocksys" \
  "$(curl -s "http://127.0.0.1:$ADMIN2/" | json_field '.name') $(curl -s "$ADMIN/" | json_field '.name')"
"$MS" ls >/dev/null && code=0 || code=$?
check "and the first store still reaches its own" 0 "$code"
mkdir -p "$WORK/sub"
out="$(cd "$WORK/sub" && env -u MOCKSYS_ADMIN_PORT MOCKSYS_HOME=../store2 "$MS" ls 2>&1)" && code=0 || code=$?
check "a relative store from a subdirectory reaches the same daemon" "0 1" \
  "$code $(echo "$out" | grep -c '▶ demo/webhooks.*LIVE')"
store2 daemon stop >/dev/null
sed -i.bak 's#\./verbs\.js#../../outside.js#' "$WH/contract.yaml"
out="$("$MS" validate demo/webhooks 2>&1)" && code=0 || code=$?
check "a module outside the scenario directory is refused" 1 "$code"
check "and named" 1 "$(echo "$out" | grep -c 'modules: ../../outside.js is outside the scenario directory')"

echo "# kits that ship modules"
cp -R "$ROOT/examples/kits/webhooks" "$MOCKSYS_HOME/kits/"
check "kits marks a kit that ships modules" 1 "$("$MS" kits | grep -c 'webhooks .*ships modules: ./verbs.js')"
out="$("$MS" kit webhooks --secret whsec_kit --port $P3 2>&1)"
check "kit names the module code it copied" 1 \
  "$(echo "$out" | grep -c 'webhooks/webhooks runs JavaScript from its modules (verbs.js)')"
"$MS" run webhooks/webhooks >/dev/null
check "the kit's module checks a delivery signed with the kit's secret" 202 \
  "$(deliver $P3 "$(hmac whsec_kit "$body")" "$body")"
check "and refuses one signed with another" 401 "$(deliver $P3 "$(hmac whsec_test "$body")" "$body")"
"$MS" stop webhooks/webhooks >/dev/null
echo '// edited by hand' >> "$MOCKSYS_HOME/webhooks/webhooks/verbs.js"
check "kit again keeps a hand-edited module" 1 "$("$MS" kit webhooks >/dev/null 2>&1 && echo 0 || echo 1)"
check "and --force replaces it" 0 \
  "$("$MS" kit webhooks --force >/dev/null 2>&1 && grep -c 'edited by hand' "$MOCKSYS_HOME/webhooks/webhooks/verbs.js" || true)"

echo "# async failures in module code"
P4=$((PORT + 3))
AF="$MOCKSYS_HOME/demo/async"
mkdir -p "$AF"
cat > "$AF/verbs.js" <<'JS'
Promise.reject(new Error("rejected at load"));
module.exports = { verbs: {
  fire() { Promise.reject(new Error("rejected by fire")); return "fired"; },
  later() { setTimeout(() => { throw new Error("thrown later by later"); }, 0); return "scheduled"; },
} };
JS
cat > "$AF/contract.yaml" <<'YAML'
service: async
scenario: demo/async
origin: authored
modules: [./verbs.js]
operations:
  - id: fire
    request: { method: GET, path: /fire }
    effect: { call: { verb: fire, as: r }, respond: { status: 200, text: "{r}" } }
  - id: later
    request: { method: GET, path: /later }
    effect: { call: { verb: later, as: r }, respond: { status: 200, text: "{r}" } }
YAML
"$MS" run demo/async --port $P4 >/dev/null 2>&1 || true
check "a verb that leaves a rejection unhandled answers" 200 "$(status_of localhost:$P4/fire)"
check "a verb whose timer throws answers" 200 "$(status_of localhost:$P4/later)"
sleep 0.2
check "the daemon keeps serving the mock" 200 "$(status_of localhost:$P4/fire)"
check "and the admin API" 200 "$(status_of "$ADMIN/")"
logged() { grep -q -- "$1" "$MOCKSYS_HOME/daemon.log" && echo yes || echo no; }
check "the log names the rejection at load" yes "$(logged 'unhandled rejection.*rejected at load')"
check "with the module's line" yes "$(logged "$AF/verbs.js:1")"
check "the log names the verb's rejection" yes "$(logged 'unhandled rejection.*rejected by fire')"
check "and the timer's throw" yes "$(logged 'uncaught exception.*thrown later by later')"
"$MS" stop demo/async >/dev/null 2>&1 || true

echo "# record"
out="$("$MS" record demo/rec --target https://api.example.com/v1 2>&1)" && code=0 || code=$?
check "a --target with a path fails up front" 1 "$code"
check "and says why" 1 "$(echo "$out" | grep -c -- '--target must not contain a path "/v1" (got https://api.example.com/v1)')"

echo "# imposter.json is never trusted"
imp="$MOCKSYS_HOME/okta/okta/imposter.json"
node -e '
  const f = process.argv[1], imp = JSON.parse(require("fs").readFileSync(f, "utf8"));
  imp.stubs.unshift({ predicates: [{ equals: { path: "/pwned" } }],
                      responses: [{ inject: "function(){ return { statusCode: 299, body: \"pwned\" }; }" }] });
  require("fs").writeFileSync(f, JSON.stringify(imp));
  require("fs").utimesSync(f, new Date(Date.now() + 3600e3), new Date(Date.now() + 3600e3));' "$imp"
"$MS" run okta/okta >/dev/null
check "a planted injection is not served" "" "$(curl -s localhost:$PORT/pwned)"
check "imposter.json was rebuilt from the contract" 0 "$(grep -c pwned "$imp" || true)"
out="$(curl -s -X POST "$ADMIN/imposters" -H 'Content-Type: application/json' \
         -H "Authorization: Bearer $(cat "$TOKEN_FILE")" -d "{\"protocol\":\"http\",\"port\":$((PORT + 3)),\"stubs\":[{\"responses\":[{\"inject\":\"function(){}\"}]}]}")"
check "the daemon refuses a posted injection" "stubs[0].responses[0]: unsupported field(s) inject" "$(echo "$out" | json_field '.errors[0].message')"

echo "# grpc"
GP=$((PORT + 2))
G="$ROOT/examples/grpc-todos"
mkdir -p "$MOCKSYS_HOME/demo/todos"
cp "$G/contract.yaml" "$G/todo.proto" "$MOCKSYS_HOME/demo/todos/"
"$MS" validate demo/todos >/dev/null && code=0 || code=$?
check "the example validates against its proto" 0 "$code"
out="$("$MS" run demo/todos --port $GP)"
check "run names a host:port target" 1 "$(echo "$out" | grep -c "running 'demo/todos' at localhost:$GP (gRPC)")"
rpc() { node "$G/client.js" "localhost:$GP" "$@" || true; }
check "GetTodo reads a seeded todo" "Write the proto" "$(rpc GetTodo '{"id":"t1"}' | json_field '.title')"
tid="$(rpc CreateTodo '{"title":"ship grpc"}' | json_field '.id')"
check "GetTodo reads what CreateTodo stored" "ship grpc" "$(rpc GetTodo "{\"id\":\"$tid\"}" | json_field '.title')"
check "UpdateTodo changes the field it sets" "Rewrite the proto" "$(rpc UpdateTodo '{"id":"t1","title":"Rewrite the proto"}' | json_field '.title')"
check "and keeps the ones it does not" true "$(rpc GetTodo '{"id":"t1"}' | json_field '.done')"
check "a miss is NOT_FOUND" '{"code":"NOT_FOUND","details":"no todo nope"}' "$(rpc GetTodo '{"id":"nope"}')"
check "ListTodos fills the repeated field" 2 "$(rpc ListTodos '{}' | json_field '.todos.length')"
check "ListTodos filters" "$tid" "$(rpc ListTodos '{"filter":"done eq false"}' | json_field '.todos[0].id')"
check "a {grpcCode, message} body picks the code" PERMISSION_DENIED "$(rpc DeleteTodo '{"id":"t1"}' | json_field '.code')"
"$MS" assert demo/todos --saw 'POST /todo.v1.Todos/CreateTodo' >/dev/null && code=0 || code=$?
check "assert sees an rpc as its POST path" 0 "$code"
check "env exports the target without a scheme" "export TODOS=localhost:$GP" "$("$MS" env demo/todos --env TODOS | head -1)"
sed 's#todo.v1.Todos/GetTodo#todo.v1.Todos/WatchTodos#' "$G/contract.yaml" > "$MOCKSYS_HOME/demo/todos/contract.yaml"
out="$("$MS" validate demo/todos 2>&1)" && code=0 || code=$?
check "validate refuses a streaming rpc" 1 "$code"
check "and says why" 1 "$(echo "$out" | grep -c 'get: rpc todo.v1.Todos/WatchTodos is server-streaming; only unary rpcs are served')"
cp "$G/contract.yaml" "$MOCKSYS_HOME/demo/todos/contract.yaml"
"$MS" stop demo/todos >/dev/null

echo "# recorded-request cap"
"$MS" daemon stop >/dev/null
MOCKSYS_MAX_RECORDED_REQUESTS=2 "$MS" run demo/hello --port $PORT >/dev/null
for p in /first /hello /hello; do curl -s -o /dev/null "localhost:$PORT$p"; done
check "requests shows the newest 2 and counts the dropped one" 1 \
  "$("$MS" requests demo/hello | grep -c '(2; 1 older dropped)')"
out="$("$MS" assert demo/hello --saw 'GET /first' 2>&1)" && code=0 || code=$?
check "assert misses a dropped call" 1 "$code"
check "and says it may have been dropped" 1 "$(echo "$out" | grep -c '1 older request(s) were dropped')"
"$MS" stop demo/hello >/dev/null

echo "# tls"
TP=$((PORT + 3))
mode_of() { node -p '(require("fs").statSync(process.argv[1]).mode & 0o777).toString(8)' "$1"; }
mkdir -p "$MOCKSYS_HOME/demo/https"
cp "$ROOT/examples/https.yaml" "$MOCKSYS_HOME/demo/https/contract.yaml"
out="$("$MS" run demo/https --port $TP)"
check "run names an https URL" 1 "$(echo "$out" | grep -c "running 'demo/https' at https://localhost:$TP")"
CA="$("$MS" tls ca 2>/dev/null)"
check "tls ca prints the CA in the store" "$MOCKSYS_HOME/tls/ca.pem" "$CA"
check "run says which CA to trust" 1 "$(echo "$out" | grep -c "TLS: trust the mocksys CA, $CA")"
check "the CA directory is the owner's alone" 700 "$(mode_of "$MOCKSYS_HOME/tls")"
check "so is the CA key" 600 "$(mode_of "$MOCKSYS_HOME/tls/ca.key")"
check "curl trusting the CA gets the body" alice "$(curl -s --cacert "$CA" "https://localhost:$TP/whoami" | json_field '.user')"
curl -s -o /dev/null "https://localhost:$TP/whoami" && code=0 || code=$?
check "curl without it refuses the certificate" 60 "$code"
check "node trusts it through NODE_EXTRA_CA_CERTS" alice \
  "$(NODE_EXTRA_CA_CERTS="$CA" node -e 'fetch(process.argv[1]).then(r => r.json()).then(b => console.log(b.user))' "https://localhost:$TP/whoami")"
check "env exports NODE_EXTRA_CA_CERTS" "export NODE_EXTRA_CA_CERTS=$CA" "$("$MS" env demo/https | grep NODE_EXTRA_CA_CERTS)"
check "imposter.json holds no key" 0 "$(grep -c 'PRIVATE KEY' "$MOCKSYS_HOME/demo/https/imposter.json" || true)"
echo '[{"method":"GET","path":"/whoami","expect":200}]' > "$WORK/https-corpus.json"
"$MS" conform demo/https --corpus "$WORK/https-corpus.json" >/dev/null && code=0 || code=$?
check "conform replays at an https twin" 0 "$code"
"$MS" conform demo/https --corpus "$WORK/https-corpus.json" --against "https://localhost:$TP" >/dev/null 2>&1 && code=0 || code=$?
check "conform --against checks the live service's certificate" 1 "$code"
NODE_EXTRA_CA_CERTS="$CA" "$MS" conform demo/https --corpus "$WORK/https-corpus.json" --against "https://localhost:$TP" >/dev/null && code=0 || code=$?
check "and passes once its CA is trusted" 0 "$code"
check "env --json names the CA" "$CA" "$("$MS" env demo/https --json | json_field '.caCert')"
cp "$CA" "$WORK/corp.pem"
out="$(NODE_EXTRA_CA_CERTS="$WORK/corp.pem" "$MS" env demo/https 2>"$WORK/env.err")"
check "env leaves a NODE_EXTRA_CA_CERTS of another file alone" 0 "$(echo "$out" | grep -c NODE_EXTRA_CA_CERTS || true)"
check "and says to add the CA to that file" 1 \
  "$(grep -c "NODE_EXTRA_CA_CERTS is already $WORK/corp.pem; add the certificate in $CA to that file" "$WORK/env.err")"
"$MS" stack tls-stack demo/https >/dev/null
check "up exports NODE_EXTRA_CA_CERTS" "export NODE_EXTRA_CA_CERTS=$CA" "$("$MS" up tls-stack | grep NODE_EXTRA_CA_CERTS)"
out="$(NODE_EXTRA_CA_CERTS="$WORK/corp.pem" "$MS" up tls-stack 2>"$WORK/up.err")"
check "up leaves a NODE_EXTRA_CA_CERTS of another file alone" 0 "$(echo "$out" | grep -c NODE_EXTRA_CA_CERTS || true)"
check "and says to add the CA to that file" 1 \
  "$(grep -c "NODE_EXTRA_CA_CERTS is already $WORK/corp.pem; add the certificate in $CA to that file" "$WORK/up.err")"
mkdir -p "$MOCKSYS_HOME/demo/login-tls"
{ echo "tls: true"; sed "s#^scenario: demo/login-throttle#scenario: demo/login-tls#" "$ROOT/examples/login-throttle.yaml"; } > "$MOCKSYS_HOME/demo/login-tls/contract.yaml"
"$MS" run demo/login-tls --port $((TP + 1)) >/dev/null
check "clock reaches an https twin" 1 "$("$MS" clock demo/login-tls advance 1h | grep -c 'virtual time 2024-01-01T01:00:00')"
"$MS" stop demo/login-tls >/dev/null
GTP=$((TP + 2))
mkdir -p "$MOCKSYS_HOME/demo/todos-tls"
cp "$G/todo.proto" "$MOCKSYS_HOME/demo/todos-tls/"
{ echo "tls: true"; sed "s#^scenario: demo/todos#scenario: demo/todos-tls#" "$G/contract.yaml"; } > "$MOCKSYS_HOME/demo/todos-tls/contract.yaml"
out="$("$MS" run demo/todos-tls --port $GTP)"
check "run says gRPC over TLS" 1 "$(echo "$out" | grep -c "running 'demo/todos-tls' at localhost:$GTP (gRPC over TLS)")"
check "a grpc-js client trusting the CA gets the reply" "Write the proto" \
  "$(NODE_EXTRA_CA_CERTS="$CA" node "$G/client.js" --tls "localhost:$GTP" GetTodo '{"id":"t1"}' | json_field '.title')"
check "an insecure client cannot connect" UNAVAILABLE "$(node "$G/client.js" "localhost:$GTP" GetTodo '{"id":"t1"}' | json_field '.code' || true)"
"$MS" stop demo/todos-tls >/dev/null
"$MS" publish >/dev/null
check "publish commits no file of the CA" 0 "$(git -C "$MOCKSYS_HOME" ls-files | grep -c '^tls/' || true)"
"$MS" stop demo/https >/dev/null

echo "# daemon stop"
"$MS" daemon stop >/dev/null
check "daemon stop frees the admin port" 000 "$(status_of "$ADMIN/")"
check "and the mocks with it" 000 "$(status_of localhost:$PORT/api/v1/users)"

echo
if [ "$fails" -eq 0 ]; then echo "smoke: all checks passed"; else echo "smoke: $fails check(s) failed"; exit 1; fi

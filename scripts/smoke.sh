#!/usr/bin/env bash
# End-to-end smoke test: drives the built CLI (out/mocksys.js) against its own
# daemon in a throwaway store. The daemon gets a private admin port, so a daemon
# you already run is left alone; it is stopped on exit.
#
#   npm run build && scripts/smoke.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MS="${MOCKSYS_BIN:-$ROOT/bin/mocksys}"
PORT="${SMOKE_PORT:-9471}"

free_port() { node -e 'const s=require("net").createServer().listen(0,()=>{console.log(s.address().port);s.close()})'; }
export MOCKSYS_ADMIN_PORT="$(free_port)"
ADMIN="http://127.0.0.1:$MOCKSYS_ADMIN_PORT"

WORK="$(mktemp -d)"
export MOCKSYS_HOME="$WORK/store"
cd "$WORK"
cleanup() {
  "$MS" daemon stop >/dev/null 2>&1 || true
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
"$MS" run okta/okta >/dev/null
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
MOCKSYS_HOME="$WORK/store2" "$MS" run demo/webhooks --port $P3 >/dev/null
check "a bundle carries the module" 202 "$(deliver $P3 "$(hmac whsec_test "$body")" "$body")"
MOCKSYS_HOME="$WORK/store2" "$MS" stop demo/webhooks >/dev/null
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

echo "# daemon stop"
"$MS" daemon stop >/dev/null
check "daemon stop frees the admin port" 000 "$(status_of "$ADMIN/")"
check "and the mocks with it" 000 "$(status_of localhost:$PORT/api/v1/users)"

echo
if [ "$fails" -eq 0 ]; then echo "smoke: all checks passed"; else echo "smoke: $fails check(s) failed"; exit 1; fi

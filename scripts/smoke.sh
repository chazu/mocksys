#!/usr/bin/env bash
# End-to-end smoke test: drives the built CLI (out/mocksys.js) against a real
# Mountebank daemon in a throwaway store. Needs :2525 free; stops the daemon on exit.
#
#   npm run build && scripts/smoke.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MS="$ROOT/bin/mocksys"
PORT=9471

if lsof -nP -iTCP:2525 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "smoke: something already listens on :2525; stop it first (mocksys uses that port)" >&2
  exit 2
fi

WORK="$(mktemp -d)"
export MOCKSYS_HOME="$WORK/store"
cd "$WORK"
cleanup() {
  "$MS" stop --all >/dev/null 2>&1 || true
  lsof -ti tcp:2525 | xargs kill 2>/dev/null || true
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

echo "# static mock"
"$MS" add demo/hello --request 'GET /hello' --status 200 --text hi >/dev/null
"$MS" run demo/hello --port $PORT >/dev/null
check "serves the authored body" "hi" "$(curl -s localhost:$PORT/hello)"
"$MS" assert demo/hello --saw 'GET /hello' >/dev/null
check "assert sees the call" 0 $?
"$MS" stop demo/hello >/dev/null

echo "# daemon"
check "admin API launched with --localOnly" yes \
  "$(ps -o args= -p "$(lsof -ti tcp:2525 -sTCP:LISTEN | head -1)" | grep -q -- --localOnly && echo yes || echo no)"

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

echo
if [ "$fails" -eq 0 ]; then echo "smoke: all checks passed"; else echo "smoke: $fails check(s) failed"; exit 1; fi

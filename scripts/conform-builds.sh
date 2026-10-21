#!/usr/bin/env bash
# Differential check between this checkout's build and a build of <base-ref>: one
# Okta twin contract is served by each build's daemon, and the two are compared with
# `mocksys conform` over examples/okta-corpus.json, then byte for byte
# (scripts/replay-diff.mjs) over the corpus and two stateful sequences. Each
# comparison starts from freshly posted twins. Both daemons get private admin ports
# and throwaway stores; both are stopped on exit.
#
#   npm run build && scripts/conform-builds.sh <base-ref>
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REF="${1:?usage: scripts/conform-builds.sh <base-ref>}"
PORT="${CONFORM_PORT:-9590}"
WORK="$(mktemp -d)"
BASE="$WORK/base"

free_port() { node -e 'const s=require("net").createServer().listen(0,()=>{console.log(s.address().port);s.close()})'; }
OLD_ADMIN="$(free_port)"
NEW_ADMIN="$(free_port)"
old() { MOCKSYS_HOME="$WORK/old" MOCKSYS_ADMIN_PORT="$OLD_ADMIN" "$BASE/bin/mocksys" "$@"; }
new() { MOCKSYS_HOME="$WORK/new" MOCKSYS_ADMIN_PORT="$NEW_ADMIN" "$ROOT/bin/mocksys" "$@"; }
cleanup() {
  old daemon stop >/dev/null 2>&1 || true
  new daemon stop >/dev/null 2>&1 || true
  git -C "$ROOT" worktree remove --force "$BASE" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "# building $REF"
git -C "$ROOT" worktree add --detach "$BASE" "$REF" >/dev/null 2>&1
ln -s "$ROOT/node_modules" "$BASE/node_modules"
(cd "$BASE" && npx shadow-cljs release cli >/dev/null)

new kit okta >/dev/null
new kit okta --scenario okta/limited --rate-limit 3 --rate-window 60 --token-ttl 600 >/dev/null
mkdir -p "$WORK/old"
cp -R "$WORK/new/okta" "$WORK/old/okta"

serve() { # both builds serve <scenario> afresh: the base on <port>, this one on <port>+1
  old run "$1" --port "$2" >/dev/null
  new run "$1" --port "$(($2 + 1))" >/dev/null
}

fails=0
echo "# mocksys conform, $REF twin as the live service"
serve okta/okta "$PORT"
stubs() { # <admin-port> <imposter-port>: the imposter's response kinds, e.g. "effect is"
  curl -fsS "http://localhost:$1/imposters/$2" | node -e 'let s="";process.stdin.on("data",c=>s+=c).on("end",()=>{
    const kinds=new Set(JSON.parse(s).stubs.flatMap(st=>st.responses.flatMap(Object.keys)));console.log([...kinds].sort().join(" "))})'
}
echo "# response kinds: $REF [$(stubs "$OLD_ADMIN" "$PORT")], this build [$(stubs "$NEW_ADMIN" "$((PORT + 1))")]"
case " $(stubs "$NEW_ADMIN" "$((PORT + 1))") " in *" effect "*) ;; *) echo "this build serves no effect stubs"; exit 1 ;; esac
# conform sends each twin its own Host, so only the URLs built from it may differ
host_keys="issuer,authorization_endpoint,token_endpoint,userinfo_endpoint,jwks_uri,iss,aud"
new conform okta/okta --corpus "$ROOT/examples/okta-corpus.json" \
  --against "http://localhost:$PORT" --headers --ignore "$host_keys" || fails=$((fails + 1))

echo "# byte for byte"
for mode in corpus okta limited; do
  scenario=okta/okta; port=$PORT
  [ "$mode" = limited ] && scenario=okta/limited && port=$((PORT + 2))
  serve "$scenario" "$port"
  node "$ROOT/scripts/replay-diff.mjs" "$mode" "http://localhost:$port" "http://localhost:$((port + 1))" \
    "$ROOT/examples/okta-corpus.json" || fails=$((fails + 1))
done

echo
if [ "$fails" -eq 0 ]; then echo "conform-builds: no differences from $REF"; else echo "conform-builds: $fails comparison(s) differ from $REF"; exit 1; fi

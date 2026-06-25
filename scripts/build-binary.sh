#!/usr/bin/env bash
# Build a standalone mocksys binary: shadow-cljs (AOT cljs -> JS) then bun --compile.
#
#   scripts/build-binary.sh              # native binary -> dist/mocksys
#   scripts/build-binary.sh all          # cross-compile the release matrix -> dist/mocksys-<target>
#   scripts/build-binary.sh <bun-target> # one target, e.g. bun-linux-x64
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> shadow-cljs release (cli -> out/mocksys.js)"
npx shadow-cljs release cli

mkdir -p dist
compile() { # <bun-target|""> <outfile>
  local target="$1" out="$2"
  if [ -n "$target" ]; then
    echo "==> bun compile $target -> $out"
    bun build out/mocksys.js --compile --target="$target" --outfile "$out"
  else
    echo "==> bun compile (native) -> $out"
    bun build out/mocksys.js --compile --outfile "$out"
  fi
}

case "${1:-}" in
  all)
    for t in bun-darwin-arm64 bun-darwin-x64 bun-linux-x64 bun-linux-arm64; do
      compile "$t" "dist/mocksys-$t"
    done ;;
  "")        compile "" "dist/mocksys" ;;
  *)         compile "$1" "dist/mocksys-$1" ;;
esac

echo "==> done:"
ls -lh dist/

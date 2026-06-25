#!/usr/bin/env bash
# Cut a mocksys release: build the binary matrix, pack tarballs, checksum them, and
# patch the Homebrew formula. Optionally create the GitHub release.
#
#   scripts/release.sh v0.1.0            # build + pack + checksum + patch formula
#   scripts/release.sh v0.1.0 --publish  # also: gh release create + upload assets
#
# Targets map bun's names to the formula's arch tags:
#   bun-darwin-arm64 -> darwin-arm64   bun-darwin-x64 -> darwin-x64
#   bun-linux-x64    -> linux-x64      bun-linux-arm64 -> linux-arm64
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TAG="${1:?usage: release.sh vX.Y.Z [--publish]}"
VER="${TAG#v}"
PUBLISH="${2:-}"
FORMULA="packaging/homebrew/Formula/mocksys.rb"

echo "==> building binary matrix"
./scripts/build-binary.sh all

declare -a ARCHES=(darwin-arm64 darwin-x64 linux-x64 linux-arm64)

echo "==> packing tarballs (each contains a single binary named 'mocksys')"
rm -rf dist/pkg && mkdir -p dist/pkg
for a in "${ARCHES[@]}"; do
  cp "dist/mocksys-bun-$a" dist/pkg/mocksys
  tar -czf "dist/mocksys-$a.tar.gz" -C dist/pkg mocksys
done
rm -rf dist/pkg

echo "==> checksums + patching $FORMULA"
# bump version + the version embedded in every download URL
perl -i -pe "s/^(\\s*version )\".*\"/\${1}\"$VER\"/" "$FORMULA"
perl -i -pe "s{download/v[^/]+/mocksys-}{download/v$VER/mocksys-}g" "$FORMULA"
for a in "${ARCHES[@]}"; do
  sha="$(shasum -a 256 "dist/mocksys-$a.tar.gz" | awk '{print $1}')"
  echo "    $a  $sha"
  # set the sha256 on the line right after this arch's url line
  perl -0777 -i -pe "s{(mocksys-$a\\.tar\\.gz\"\\s*\\n\\s*sha256 \")[^\"]*}{\${1}$sha}g" "$FORMULA"
done

echo "==> done. assets in dist/*.tar.gz, formula patched for $TAG"
if [ "$PUBLISH" = "--publish" ]; then
  echo "==> gh release create $TAG"
  gh release create "$TAG" dist/mocksys-*.tar.gz --title "$TAG" --generate-notes
  echo "Now commit & push the updated $FORMULA to the homebrew-mocksys tap repo."
else
  echo "Next: gh release create $TAG dist/mocksys-*.tar.gz   (or re-run with --publish)"
fi

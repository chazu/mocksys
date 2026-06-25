# homebrew-mocksys (tap skeleton)

This directory is the source for the **Homebrew tap** that distributes `mocksys`.

## Publishing the tap (one-time)

A tap is its own git repo named `homebrew-<name>`. Push the contents of this
directory to `github.com/chazu/homebrew-mocksys`:

```sh
# from this directory
gh repo create chazu/homebrew-mocksys --public --source=. --remote=origin --push
# (or: create the repo, then `git init && git add Formula/ README.md && git push`)
```

Then anyone can install with:

```sh
brew install chazu/mocksys/mocksys
# or
brew tap chazu/mocksys && brew install mocksys
```

## Cutting a release (each version)

The formula downloads prebuilt binaries from this project's GitHub **Releases**.
`scripts/release.sh vX.Y.Z` (in the mocksys repo) builds the binary matrix, packs
each as `mocksys-<target>.tar.gz`, computes sha256s, and updates
`Formula/mocksys.rb` (version + the four `sha256` lines). It can also create the
GitHub release and upload the assets via `gh`.

After running it, commit and push the updated `Formula/mocksys.rb` to the tap repo.

## How it works

`mocksys` itself is a self-contained binary (ClojureScript compiled with
shadow-cljs, packed with `bun build --compile`). It drives **Mountebank** as an
external daemon so a mock survives across separate CLI invocations — the formula
provisions Mountebank into `libexec` (it only needs `node`) and the `bin/mocksys`
wrapper points at it via `MOCKSYS_MB`.

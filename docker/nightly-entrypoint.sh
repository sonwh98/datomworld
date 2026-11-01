#!/usr/bin/env bash
# The nightly container entrypoint: fresh clone at the pinned SHA, generated
# assets on persistent volumes mounted AT the in-clone paths (so gen:antlr's
# worktree-containment check passes — symlinked output parents are refused,
# docs/agents/build-n-test.md), then the versioned driver.
#
# Why clone-then-copy: docker mounts the volumes at container creation, so
# /repo-src/{node_modules,.shadow-cljs,build,lib/cljd-out} already exist as
# (empty) dirs and `git clone` refuses a non-empty target. The clone lands in
# /tmp and is copied in; files under mounted paths land on the volumes, the
# rest on the container layer. The checkout is still exactly the pinned SHA.
#
# Environment:
#   REPO_URL   git@github.com:sonwh98/datomworld.git
#   SHA        the commit to test (from the pin, or resolved by the caller)
#   LANES      e.g. node,dart | jvm
#   PAR        per-lane parallelism (default 12)
#   ROLE       publish (resolve origin/master and write the pin into /results)
#              | read (SHA must be provided)
#   NIGHTLY_RESULTS  /results (bind-mounted from the host)
#
# The SSH key for the private clone is mounted read-only at /run/ssh.
set -euo pipefail

REPO_URL="${REPO_URL:-git@github.com:sonwh98/datomworld.git}"
SHA="${SHA:?SHA env required}"
LANES="${LANES:?LANES env required}"
PAR="${PAR:-12}"
RESULTS="${NIGHTLY_RESULTS:-/results}"
ROLE="${ROLE:-publish}"
REPO=/repo-src

export GIT_SSH_COMMAND="ssh -i $(find /run/ssh -type f ! -name '*.pub' | head -1) -o StrictHostKeyChecking=accept-new"

echo "entrypoint: cloning $REPO_URL at $SHA (role=$ROLE)"
git clone --no-checkout --quiet "$REPO_URL" /tmp/src
git -C /tmp/src checkout --quiet --detach "$SHA"
mkdir -p "$REPO"
cp -a /tmp/src/. "$REPO/"
rm -rf /tmp/src

echo "entrypoint: driver start (lanes=$LANES par=$PAR)"
cd "$REPO"
exec bb src/dev/nightly.clj "$REPO" "$LANES" "$PAR" --now --pin-sha "$SHA"

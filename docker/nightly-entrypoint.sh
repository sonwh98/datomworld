#!/usr/bin/env bash
# The nightly container entrypoint: fresh clone at the pinned SHA, generated
# assets on persistent volumes (warm compiles), then the versioned driver.
#
# Environment:
#   REPO_URL   git@github.com:sonwh98/datomworld.git
#   SHA        the commit to test (from the pin, or resolved by the caller)
#   LANES      e.g. node,dart | jvm
#   PAR        per-lane parallelism (default 12)
#   ROLE       publish (this host resolves origin/master and writes the pin
#              into /results) | read (SHA must be provided)
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
CLONE=/repo

export GIT_SSH_COMMAND="ssh -i $(find /run/ssh -type f ! -name '*.pub' | head -1) -o StrictHostKeyChecking=accept-new"

echo "entrypoint: cloning $REPO_URL at $SHA (role=$ROLE)"
git clone --no-checkout --quiet "$REPO_URL" "$CLONE"
git -C "$CLONE" checkout --quiet --detach "$SHA"

# generated-asset volumes mount at /cache/*; symlink them into the clone so
# every tool writes through to the persistent cache (the checkout stays
# otherwise pristine).
mkdir -p /cache/npm /cache/shadow /cache/build /cache/cljd-out /cache/m2 /cache/gitlibs
ln -sfn /cache/npm        "$CLONE/node_modules"
ln -sfn /cache/shadow     "$CLONE/.shadow-cljs"
ln -sfn /cache/build      "$CLONE/build"
mkdir -p "$CLONE/lib" && ln -sfn /cache/cljd-out "$CLONE/lib/cljd-out"
ln -sfn /cache/m2      /root/.m2
ln -sfn /cache/gitlibs /root/.gitlibs

echo "entrypoint: driver start (lanes=$LANES par=$PAR)"
cd "$CLONE"
exec bb src/dev/nightly.clj "$CLONE" "$LANES" "$PAR" --now --pin-sha "$SHA"

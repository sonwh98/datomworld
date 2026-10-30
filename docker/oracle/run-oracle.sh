#!/usr/bin/env bash
# Build the oracle image and run the datomic-q-v1 oracle runner against
# Datomic Local (:storage-dir :mem) inside it.
#
#   docker/oracle/run-oracle.sh [OUT_DIR] [-- runner args...]
#
# The checkout is mounted READ-ONLY; the only writable mounts are OUT_DIR
# (observations, default ./oracle-out) and a named volume for the Maven/gitlibs
# cache. The observations file is oracle-out/datomic-q-v1-observations.edn and
# is never the frozen corpus.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:-$repo/oracle-out}"
[ "$#" -gt 0 ] && shift
[ "${1:-}" = "--" ] && shift
mkdir -p "$out"
out="$(cd "$out" && pwd)"

image="${ORACLE_IMAGE:-datomic-q-oracle:local}"
docker build -t "$image" "$repo/docker/oracle"

# Runner args default to the Local backend; HOME and the clj cache dirs live on
# the writable volume because the checkout (and so ./.cpcache) is read-only.
if [ "$#" -eq 0 ]; then set -- --backend datomic-local; fi

exec docker run --rm \
  -v "$repo":/repo:ro \
  -v "$out":/out \
  -v datomic-q-oracle-cache:/cache \
  -e HOME=/cache \
  -e CLJ_CONFIG=/cache/.clojure \
  -e CLJ_CACHE=/cache/.cpcache \
  -e GITLIBS=/cache/.gitlibs \
  -w /repo \
  "$image" \
  clojure -Sdeps '{:mvn/local-repo "/cache/.m2"}' -M:oracle \
    -m dao.space.datomic-query-oracle \
    --out /out/datomic-q-v1-observations.edn \
    "$@"

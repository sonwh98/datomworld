#!/usr/bin/env bash
# Run the W0a corpus: bring up MySQL + PHP, drive tiers t0..t2 from an empty
# database, dump the schema after each tier, and leave the raw output in OUT.
#
#   docker/wp-corpus/run-corpus.sh WP_TARBALL_SHA256 OUT_DIR
#
# Environment: MYSQL_IMAGE, PHP_IMAGE, BB_IMAGE (pin by digest on the freeze run).
# The WordPress tarball is downloaded here, verified against the given sha256
# and extracted into OUT/wp; nothing from it runs on the host.
# OUT afterwards: pin-raw.txt, raw/*.jsonl, raw/mysql-general.log,
# requests-t{0,1,2}.edn, schema/after-t{0,1,2}.sql, wp-version.
set -euo pipefail

want_sha="${1:?wordpress tarball sha256}"
out="${2:?out dir}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$out/raw" "$out/schema"
out="$(cd "$out" && pwd)"
export CORPUS_OUT="$out"
export WP_ROOT="$out/wp"

if [ ! -d "$WP_ROOT" ]; then
  curl -fsSL -o "$out/wordpress.tar.gz" "https://wordpress.org/latest.tar.gz"
  got="$(shasum -a 256 "$out/wordpress.tar.gz" | cut -d' ' -f1)"
  [ "$got" = "$want_sha" ] || { echo "tarball sha256 $got != $want_sha" >&2; exit 2; }
  mkdir -p "$out/wp-extract" && tar xzf "$out/wordpress.tar.gz" -C "$out/wp-extract"
  mv "$out/wp-extract/wordpress" "$WP_ROOT" && rmdir "$out/wp-extract"
fi
grep -E "^\\\$wp_version" "$WP_ROOT/wp-includes/version.php" > "$out/wp-version"

cd "$here"
compose=(docker compose -p wpcorpus)
# A hung fixture must not orphan a host-side php process: every docker
# invocation is wrapped in a host timeout, and the per-run project name
# makes the teardown target exactly this run's containers.
RUN_TS=$(date +%s)
compose=(docker compose -p wpcorpus-${RUN_TS})
trap '"${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true' EXIT

timeout 300 "${compose[@]}" build php
timeout 300 "${compose[@]}" up -d db php

# pin-raw.txt: the images and the live MySQL/PHP facts that become pin.edn.
{
  echo "# images"
  docker image inspect --format '{{index .RepoDigests 0}}' "${MYSQL_IMAGE:-mysql:8.4}" || true
  docker image inspect --format '{{index .RepoDigests 0}}' "${PHP_IMAGE:-php:8.3-cli}" || true
  echo "# mysql"
  timeout 300 "${compose[@]}" exec -T db mysql -uroot -proot -N -e \
    "SELECT @@version, @@sql_mode, @@collation_connection, @@time_zone" 2>/dev/null
  echo "# php"
  timeout 300 "${compose[@]}" exec -T php php -r 'echo PHP_VERSION, "\n", implode(",", get_loaded_extensions()), "\n";'
} > "$out/pin-raw.txt"

for tier in t0 t1 t2; do
  timeout 300 "${compose[@]}" run --rm driver bb /driver/drive.clj http://php:8080 /out "$tier"
  timeout 300 "${compose[@]}" exec -T db mysqldump -uroot -proot --no-data --skip-comments wp \
    > "$out/schema/after-$tier.sql" 2>/dev/null
done
timeout 300 "${compose[@]}" exec -T db mysql -uroot -proot -e "FLUSH LOGS" 2>/dev/null || true
echo "corpus run complete: $out"

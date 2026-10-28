# wp-corpus: dao.wordpress W0a SQL corpus stack

Records every SQL statement, prepare, result and error of a scripted WordPress
install, front-end and admin transcript against a pinned MySQL, for freezing
under `test/fixtures/wordpress/<tag>/` (docs/design/dao.wordpress.md section 11).

- `db.php`: observer wpdb drop-in (mounted as `wp-content/db.php`); one JSONL
  file per request id (`X-Corpus-Request` header).
- `compose.yml`: `db` (mysql, general log to `raw/mysql-general.log`), `php`
  (`php -S` + `router.php`, 4 workers), `driver` (babashka, same internal network).
- `drive.clj`: one tier per call (`t0` install, `t1` front and auth, `t2` admin).
- `run-corpus.sh WP_SHA256 OUT`: whole run; leaves raw output in OUT.

Run on shiva (orchestrator): scratch clone, `mise trust`, tmux, then

    docker/wp-corpus/run-corpus.sh <tarball-sha256> ~/ci/w0a-out

(Pin `MYSQL_IMAGE`, `PHP_IMAGE`, `BB_IMAGE` by digest in the environment.)
Copy back with `ssh shiva 'tar czf - -C ~/ci w0a-out' | tar xzf - -C build/wp-corpus/`,
then on the laptop

    clojure -M:dev -m dao.wordpress.corpus build/wp-corpus/w0a-out <tag> <tarball-sha256>

Never committed: `raw/`, the extracted WordPress tree, the tarball.

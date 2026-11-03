Created-GMT: 2026-11-01 05:35:00 GMT
Created-Local: 2026-11-01 12:35:00 +07
Coding-Agent: interactive
Session-ID: sess_b6001731-427a-4431-b74f-97f348558fc2

# Task: diagnose why the Dart sharded lane fails at `cljd compile`

Role: implementer/diagnostician (Codex). Repo: sonwh98/datomworld.
Hosts: jupiter is IDLE and is the one to use (load ~0.5). shiva is running the
Node lane right now — do not touch it, and never kill another seat's processes.

## The failure

`src/dev/dart_shard.clj` run on jupiter at commit 161dac7d:

```
start 20261031-2316 conc=8
build-yin-repl-peer rc=0
all=269 covered=252 uncovered=17
compile-all rc=1 secs=19
FAIL: cljd compile failed
```

Artifacts: `jupiter:~/ci/datomworld-nightly/target-ci/dart-shards-20261031-2316/`
(`summary.txt` has the lines above). **`compile.log` was 0 bytes** — the run
predates the output-capture fix, so the actual cljd error is unknown. That fix
is now on master (`4a28d183`), so a re-run captures it.

## What is known to work on jupiter

- `flutter 3.47.8` via mise, `clojure 1.12.4.1602`, `java 21` — all resolve.
- Emitting shard dirs works: `bb src/dev/cljd_agg.clj --only <6 namespaces>
  --shard-dir build/cljd-agg-<x> --no-compile --no-run` produced 6 shard files,
  and `flutter test build/cljd-agg-<x>` passed (yang.base, 34 tests) — verified
  by hand on 2026-11-01 for ONE subsystem with an ALREADY-POPULATED
  `lib/cljd-out`.
- That hand test used **6** namespaces. The lane compiles **all ~250** in one
  invocation.

## Prime suspect (verify, do not assume)

`dart_shard.clj` builds the compile namespace list with:

```clojure
(let [allns (->> (mapcat (fn [s] (some->> (str out "/ns-" s ".txt") slurp
                                         str/split-lines (remove str/blank?)))
                        subs)
                 (apply concat (some->> (str out "/ns-uncovered.txt") slurp
                                        str/split-lines (remove str/blank?)))
                 distinct vec)]
  (apply p/shell ... "clojure" "-M:clojuredart:cljd" "compile" allns))
```

`(->> A (apply concat B))` reads as `(apply concat A B)`, i.e.
`(concat A B)` — A is a seq of namespace seqs and B is a seq of namespace
strings, so the argument list handed to the cljd compiler may be malformed
(nested seqs, or strings from `A`'s head mixed with `B`). Check what `allns`
actually contains before the compile, and whether the cljd CLI rejects it
(e.g. "namespace not found", or a nested sequence printed as its own argument).

Also consider: argv length with ~250 namespaces, a namespace in the cljd list
that has no Dart counterpart, `lib/cljd-out` staleness from the earlier
`zsh: no matches`-era runs, or a genuine cljd compile error in one namespace
(which would be a product/test issue, not harness).

## Your tasks

1. **Get the real error.** On jupiter, in `~/ci/datomworld-nightly` (or a fresh
   clone if you prefer), at origin/master, run the lane's compile step and
   capture stderr:
   `bb src/dev/dart_shard.clj ~/ci/datomworld-nightly 8` — it should fail
   within a minute and now WRITE `compile.log`. Report the exact compiler
   message.
2. **Print/verify `allns`** in isolation (e.g. a small `bb -e` that reproduces
   the expression against the artifact's `ns-*.txt` files) and state whether it
   is a well-formed flat vector of namespace symbols/strings.
3. **Fix the harness** if the cause is in `dart_shard.clj` (the argument list,
   argv size, `lib/cljd-out` handling, or per-namespace fallback). Keep the
   change inside `src/dev/dart_shard.clj`. Do not commit.
4. If the failure is a genuine `cljd` compile error in a specific namespace,
   STOP and report: namespace, error, and whether it also fails on the JVM --
   that is a product/test issue for its owning seat, not this brief.
5. Validate your fix by getting the lane at least through compile and into
   `flutter test` for a small subsystem subset (not necessarily the whole
   suite), and report the wall time of that subset.

## Constraints

- Jupiter only. Do not start anything on shiva (Node lane is running there).
- `:out`/`:err` must be `(io/file ...)` or `:err :out` — never a
  `java.io.Writer` (unflushed Writers cost us a day of undiagnosable runs) and
  never `:err :append` (not a babashka option; it corrupts the exec).
- Do not commit; the orchestrator reviews and commits. Keep edits to
  `src/dev/dart_shard.clj`; do not touch product source, `collab/`, or docs.

## Deliverable (stdout; orchestrator promotes)

1. The real cljd error text.
2. What `allns` actually was (and whether it was malformed).
3. Root cause + the fix diff (or the handoff if it is product code).
4. The validation run: command, subsystem subset, tests passed, wall time.
5. Anything you could not verify.

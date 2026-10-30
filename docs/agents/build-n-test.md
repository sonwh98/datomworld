---
description: Build, lint, test commands, TDD guidelines, and bracket debugging for Clojure, ClojureScript, Node.js, and ClojureDart
---

# BUILD & TEST

## Commands

```sh
# Tests (Babashka runner)
bb test              # All three lanes (JVM, Node, Dart), fast default form: no slow tests
bb test:all          # Every test on every lane, slow ones included (JVM takes 20+ min)
bb test:slow         # Only the slow tests, on every lane
# One lane alone: <task>:clj, <task>:cljs, <task>:cljd
bb test:clj          # JVM, fast: skips ^:slow (bare `clj -M:test` runs them too)
bb test:cljs         # Node (shadow-cljs :cljs alias), fast: guarded slow bodies print SKIP
bb test:cljd         # Dart (needs flutter on PATH), fast: guarded slow bodies print SKIP
bb test:all:clj      # JVM, no filter: `clojure -M:test`
bb test:all:cljs     # Node with DATOM_SLOW_TESTS=1
bb test:all:cljd     # Dart with DATOM_SLOW_TESTS=1
bb test:slow:clj     # JVM: `-i :slow` (long-loops-test, ~14 min)
bb test:slow:cljs    # Node: only the namespaces that use dao.test-slow/guard, DATOM_SLOW_TESTS=1
bb test:slow:cljd    # Dart: only those namespaces' generated tests, DATOM_SLOW_TESTS=1
# Slow tests: tag a deftest ^:slow. The JVM runner then excludes it from `bb test:clj`.
# Node and Dart have no tag filter, so a .cljc slow test also wraps its body in
# (dao.test-slow/guard "name" (fn [] ...)); it prints SKIP unless DATOM_SLOW_TESTS=1.
# Node and Dart select slow tests by NAMESPACE (derived by grepping `slow/guard`
# under test/), so the non-slow tests inside those namespaces also run there.
# On Node, shadow also runs the test namespaces those nine require (about
# seven more, e.g. lower-portable-test); on Dart only the nine namespaces run.
# Run test:slow / test:all before a big merge or a commit that touches many parts.
# Run one Dart lane at a time per machine (the limit is cores and the Flutter cache,
# not subsystems). On the many-core remote hosts see "Remote test servers" below for
# which lanes may overlap.
#
# When to tag a test slow: when it takes more than about 5 s on any lane (the
# Python e2e tests sit on a ~3 s floor, so their JVM cut is 3.0 s). The
# default lanes must stay in minutes; 22 JVM tests (0.75% of the suite) were
# 81% of its test time. Tag it ^:slow on the JVM; if it is a .cljc test, also
# wrap its body in dao.test-slow/guard so Node and Dart skip it by default. A
# test that is slow only on Dart (dao.jing.dht-test/unproven-chunks-... takes
# 72 s there, 3.6 s on the JVM) is a candidate for a speed investigation first.
# Python slow set (2026-10-06): 65 ^:slow and 15 guard-only tests in the
# yang.python.antlr e2e, e2e-c1, prelude-parity, safepoint, float-address
# and int-contract tests; a Python slice landing runs `bb test:sub:clj yang.python --slow`
# (or `clojure -M:test -i :slow -n <ns>`).
# While iterating, use the Gate Ladder (see below): G0 iterate via `bb test:sub:clj <own>`
# or `clojure -M:test -n <ns>`. Do not run the full `bb test` while iterating.
# At slice checkpoints, run G1 (`bb test:sub <own>`). Before landing a slice, run
# G2 (`bb test:changed` in the worktree across all three lanes).
# Full repo-wide `bb test` (G3) is the mandatory landing gate on master after merge.
# Cross-host bugs show only on Node or Dart, so tri-host verification is mandatory
# for every gate from G1 upward. Run one lane set at a time per machine to avoid
# contention (a laptop has 8 cores; the 24- and 48-core remote hosts have room to
# overlap lanes, see "Remote test servers" below).
# Measured fast-lane times (2026-10-03): bb test about 14 min = JVM 5.5 + Node
# 3.8 + Dart 4.3, plus builds. test:slow: Node and Dart about 5 min each (mostly
# compile); the JVM half is estimated at 17 min (long-loops-test alone is 14),
# not yet run end to end. test:all: JVM 20+ min, also an estimate.
npm test             # Node.js tests

# Cross-host peers the JVM lane spawns. `bb test` builds both. A bare
# `bb test:clj` builds only the Node REPL (it depends on build:yin-repl-node);
# without build:yin-repl-peer first, the Dart-peer tests skip with a printed notice.
# Under `bb test:sub` and `bb test:changed`, prerequisites are derived automatically.
bb build:yin-repl-peer   # Dart exe build/yin-repl-peer (yin.repl R5 pairs)
bb build:yin-repl-node   # Node REPL target/yin-repl.js; REQUIRED by
                         # yin.repl.dht-process-test (JVM-to-Node DHT reader,
                         # and the linker L5 Node reader that requires a module
                         # by name): `clojure -M:test` without it fails that test
```

### Changed-only tests: `bb test:changed`

`bb test:changed` (src/dev/affected.clj) runs the fast tests a change can
reach: the reverse require closure of the files changed since the
merge-base with master, plus fixtures that mention a changed resource path,
tests that walk a changed tree, and each src file's `_test` by convention.
A walker is a file calling file-seq, fs/glob, fs/list-dir,
fs/walk-file-tree or .listFiles; each string literal argument of its
io/file, fs/file, fs/path, fs/glob, java.io.File. or File. calls is a root
(let-bound roots count), and a change under a root selects it. The three
lanes run in parallel (logs: target/profile/changed-<lane>.log) after the
needed builds. `test:changed:list` shows what and why; `:clj`/`:cljs`/
`:cljd` run one lane; `--base REF`; `--changed FILE...` (absolute OK; a
typo, an empty list or the wrong cwd exits 1). Use it to iterate and to
land a slice confined to a package. Exit 2 (from :list too) is a wide
change (deps.edn bb.edn shadow-cljs.edn mise.toml .cljstyle src/dev/
antlr/ .clj-kondo/ package*/pubspec*): it runs nothing; run `bb test`,
as for a change to the core everything requires (yin.vm engine,
dao.stream, codecs) and before a big merge. Not seen: dynamic loads
(`requiring-resolve`, `resolve`), paths or walk roots built at run time,
and spawned programs that changed (target/yin-repl.js, Dart peers).

### Sharded quick JVM feedback and change routing

```sh
bb test:quick-sharded                           # Full G3-sized JVM quick lane, 4 workers
bb test:quick-sharded --changed src/cljc/yang/clojure.cljc
bb test:quick-sharded --base master --workers 6  # Changed selection, explicit worker limit
bb test:quick-sharded --list --changed src/cljc/yang/clojure.cljc
bb test:routed docs/agents/build-n-test.md       # Explicit changed paths (absolute OK)
bb test:routed --dry-run deps.edn               # Print dispatch; executes no gate
bb test:routed                                 # Git change set, same base as test:changed
bb deps:npm                                    # Content-checked local npm ci
bb -cp src/dev -m quick-sharded-test            # Runner/routing/cache self-tests
```

`test:quick-sharded` is a JVM-only execution alternative for broad G2 and
the JVM portion of G3. Without selection flags it selects every JVM test;
`--changed FILE...` or `--base REF` uses `affected/select` and
`affected/lane-tests`, exactly as `test:changed:clj` does. Wide input selects
the full JVM set here. It never changes `test:changed`'s selection or exit-2
semantics. `--workers N` must be positive (default 4 on the laptop).

Shard membership comes from every entry in `src/dev/subsystems.edn`, using
`affected/select-subsystems` and the same longest-prefix ownership rule as
`test:sub`. As in the shiva script below, currently unowned namespaces run
in an explicit `unmapped` residual shard, printed by name. Registry omissions
therefore cannot silently shrink coverage. Each nonempty shard gets its own
`clojure -M:test -e :slow -n <ns> ...` process, with at most N JVMs running.
Prerequisites are derived from the entire selected JVM reach and finish
serially before workers start; the Node REPL build prerequisite
(`build:yin-repl-node`) also ensures `bb deps:npm`.

Each invocation writes a separate `target/profile/quick-sharded-<id>/`:
`selected.txt`, `ns-<shard>.txt`, `covered.txt`, `uncovered.txt`, per-shard logs,
and `summary.edn`. Before execution it checks **set equality** between selected
namespaces and the union of shard lists, plus absence of duplicate assignments.
Any uncovered, unexpected, or duplicate namespace fails loudly before tests.
The final summary totals tests, assertions, failures and errors and separates
JVM wall time from total time including selection/builds. A nonzero child exit,
failure/error, or missing final test-runner summary makes the task exit nonzero.
Dry plans write namespace evidence but execute no builds or tests. An empty
selection prints `0 JVM namespaces selected` and exits 1, including dry plans.
The final `summary.edn` includes a `:verification-scope` record; the same EDN
record is printed on stdout with `:type :verification-scope`, `:runner`,
`:scope`, `:exit`, `:dry-run?`, and `:gate-complete?`. JVM feedback always has
`:scope :jvm-feedback` and `:gate-complete? false`, even on exit 0.

`test:routed <changed-files...>` applies this priority table to the whole change
set, prints the exact dispatch, executes it and propagates its result:

+----------------------------------------------------------------------------------------------+-----------------------------------------------------------------------+
| Change class (first match wins)                                                              | Dispatch                                                              |
+==============================================================================================+=======================================================================+
| Existing wide inputs (`deps.edn`, `bb.edn`, `src/dev/`, ANTLR, package locks, runner         | `bb test` — full quick tri-host; success required                     |
| configs, etc.), toolchain manifests, CI workflows, build scripts                             |                                                                       |
+----------------------------------------------------------------------------------------------+-----------------------------------------------------------------------+
| Only `.md`, `.txt`, `.rst` documentation, excluding test resources                           | `git diff --check HEAD -- <paths>` — documentation whitespace/format  |
|                                                                                              | lint only                                                             |
+----------------------------------------------------------------------------------------------+-----------------------------------------------------------------------+
| Foundation code owned by a layer-0 entry in `subsystems.edn`, or `yang.clojure`, `yang.php`, | `bb test:quick-sharded --changed <paths>` — broad selected JVM        |
| `yang.typescript`, `yang.python` code                                                        | feedback                                                              |
+----------------------------------------------------------------------------------------------+-----------------------------------------------------------------------+
| Other code/tests/fixtures with a nonempty affected selection                                 | `bb test:changed --changed <paths>` — existing quick tri-host         |
|                                                                                              | selection                                                             |
+----------------------------------------------------------------------------------------------+-----------------------------------------------------------------------+
| Other non-doc input with no statically selected tests                                        | `bb test` — fail closed rather than report a vacuous green gate       |
+----------------------------------------------------------------------------------------------+-----------------------------------------------------------------------+

Mixed changes take the widest matching route. Missing paths, an empty git change
set, unknown flags, and the wrong cwd fail. Deleted paths are validated against
the base tree. If the leaf command returns exit 2, routing immediately dispatches
`bb test` and requires its success. `--dry-run`/`--list` only reports the route;
its zero exit is **not** a verification result. Documentation checks inspect
staged and unstaged changes together against HEAD. Paths absent from
`git ls-files` print `unchecked (untracked)` and appear in the scope record
under `:unchecked`; they cannot complete the docs gate. These checks do not lint
executable examples or validate documentation links.

Routing prints its own final EDN verification-scope record. `:gate-complete?`
means the dispatched gate completed successfully, not that every landing gate
is satisfied. It is false for foundation/JVM feedback, dry runs, unchecked docs,
and failed dispatches. Successful checked docs, selected tri-host, and full
quick tri-host routes report `:docs-whitespace`, `:selected-quick-tri-host`, and
`:full-quick-tri-host` respectively. Exit codes continue to propagate child
results, so automation must inspect the scope as well as the exit code.

**Gate requirements are unchanged.** Sharded JVM success alone is not G2/G3
tri-host success: for foundation/compiler changes also run
`bb test:changed:cljs --changed <paths>` and
`bb test:changed:cljd --changed <paths>`, respecting the existing JVM/Dart
serialization rule. G1, full post-merge `bb test` (G3), applicable slow G4,
demo smoke checks and review/landing gates all remain required. Node and Dart
lanes retain their current commands. The original unsharded tasks remain
available. Do not run another lane set concurrently on the laptop.

#### Worktree-local prerequisite caches

`bb gen:antlr` now checks a content key over all grammar/manifests (including
recorded helper/generated digests and runtime pins), the generator/cache source,
`deps.edn`, `mise.toml`, actual Java/javac versions and canonical worktree path.
It also verifies the names and SHA-256 bytes of all generated targets, compiled
classes and cached upstream helpers before a hit. Changed inputs or missing,
removed or corrupted outputs trigger a full generation, retaining the generator's
digest checks. Filtered/discovery invocations retain their CLI behavior and
invalidate the complete-build stamp.

`bb deps:npm` wraps `npm ci` with a key covering `package-lock.json`,
`package.json`, npm configuration, Node/npm versions, platform, relevant npm/Node
environment and worktree identity; it verifies the installed file tree before
reuse. A failed command never establishes a cache hit. Existing Node build tasks
ensure this dependency too. Direct `npm ci` is still available.

Stamps and fail-fast writer locks live under `target/prereq-cache/`. Remove a
lock only after confirming its owning process died. Symlinked output parents
that escape the worktree are refused. This is reuse **within one worktree**,
not a shared artifact store: never share mutable `build/`, `lib/`, `target/` or
`node_modules/` across live worktrees. ANTLR generation and Dart compilation
still require the existing worktree isolation/serialization rules; the cache
does not make overlapping builds and tests safe. Peer executables and shadow
builds themselves are not cached by this mechanism.

Laptop pilot (2026-10-29, 8 cores, 4 workers, `yang.clojure.cljc` changed):
the unchanged selector and the sharded plan both covered **61 JVM namespaces**;
both completed with **955 tests, 14,848 assertions, zero failures/errors**.
Sharded JVM wall time was **177.165 s** (total including prerequisites **257.207 s**;
outer command **258.27 s**). The original `test:changed:clj` command completed
in **376.79 s** overall and reported **298 s** for its JVM lane (that runner
rounds to whole seconds). The investigation's earlier unsharded command had
timed out at 300 s without a summary. These are single-run observations, not
a median/p95 claim or a replacement for tri-host landing verification.

### Subsystem-first tests: `bb test:sub`

`bb test:sub <subsystem>...` (src/dev/affected.clj) runs the tests belonging to
one or more canonical subsystems (defined in `src/dev/subsystems.edn`). Selection
is by longest namespace prefix matching.

```sh
bb test:sub <subsystem>...        # All three lanes in parallel (fast form)
bb test:sub:clj <subsystem>...    # JVM lane alone
bb test:sub:cljs <subsystem>...   # Node lane alone
bb test:sub:cljd <subsystem>...   # Dart lane alone
bb test:sub:list <subsystem>...   # Show namespaces, builds, Class B seams & dependents; runs nothing
bb test:sub <subsystem>... --slow # Include slow tests (-i :slow on JVM, DATOM_SLOW_TESTS=1 on Node/Dart)
bb -cp src/dev -m affected-test   # Run affected-test self-tests directly in Babashka
```

#### Canonical Subsystems (`src/dev/subsystems.edn`)

- **Layer 0 (Core Data & Transport)**: `dao.stream`, `dao.jing`, `dao.data`, `dao.base`
- **Layer 1 (Space & UI)**: `dao.space`, `dao.gui`, `dao.postgraphics`
- **Layer 2 (Execution Engine)**: `yin.vm.ucf`, `yin.vm`
- **Layer 3 (Languages & Interop)**: `yin.repl`, `yang.python`, `yang.base`
- **Layer 4 (Applications & Demos)**: `demo`, `world`
- **Infrastructure**: `dev`, `bench`

Prerequisites (`build:yin-repl-peer`, `build:yin-repl-node`, `gen:antlr`)
and lane serialization (`[:clj :cljd]`) are derived purely from text reach of the
selected namespaces and run automatically when needed.

#### Gate Ladder (docs/design/test.subsystems.md)

1. **G0 Iterate**: Run only the subsystem's JVM lane (`bb test:sub:clj <own>` or `clojure -M:test -n <ns>`).
2. **G1 Checkpoint**: Run the three-lane subsystem test (`bb test:sub <own>`). Proves the home suite across all three hosts before reporting ready.
3. **G2 Land**: Run `bb test:changed` in the worktree across all three lanes (plus `bb test:sub <own> --slow` if slow tests are present). Covers dependents across the change (for foundation subsystems like `dao.stream` or `dao.jing`, this correctly runs nearly the full suite).
4. **G3 Master**: The mandatory landing gate on master after merge, and for any wide change (`bb test`).
5. **G4 Release**: Full or slow test run before a big release or cross-cutting merge (`bb test:slow` or `bb test:all`), and at each phase boundary of a multi-phase track (owner decision 2026-10-12; see "Test tiers: quick and slow" below). G0 to G3 run the quick tier; G4 runs the slow tier.


The linker-over-DHT end-to-end gate (docs/design/yin.vm.linker.dht.md,
slice L5) is two tests:

- `yin.repl.dht-process-test` (JVM lane only): real `yin.repl.main`
  processes over real loopback UDP. A JVM publisher with a key file
  publishes modules by name, and JVM and Node readers require them by
  name on all four VMs. Plain Clojure in the test JVM takes the same path
  and checks each section 9 failure as data. Ports are ephemeral, and
  every wait is bounded. A run takes a few minutes. Run it alone with
  `clojure -M:test -n yin.repl.dht-process-test`, after
  `bb build:yin-repl-node`.
- `yin.vm.linker.dht-end-to-end-test` (all three lanes): the same
  scenario in process, over the `dao.jing.dht` test mesh seam. Dart's
  signed-name leg runs here, because no Dart `yin.repl.main` process
  exists to spawn. `build/yin-repl-peer` is the R5 slice peer, not a
  REPL.

```sh
# ClojureScript / shadow-cljs (always via deps.edn :cljs alias, never npx)
clj -M:cljs -m shadow.cljs.devtools.cli watch <build-id>    # e.g. watch demo
clj -M:cljs -m shadow.cljs.devtools.cli compile <build-id>  # e.g. compile demo
clj -M:cljs -m shadow.cljs.devtools.cli release <build-id>  # production release

# ClojureDart
clj -M:clojuredart:cljd compile

# Linting
clj -M:kondo --lint <path>
```

The Dart `bb` lanes run `src/dev/cljd_agg.clj` (`bb src/dev/cljd_agg.clj
[--slow-only | --slow-regex]`), not `cljd test`. `--slow-only` runs only the
namespaces that use `dao.test-slow/guard` and fails if one has no generated
Dart file. `--slow-regex` prints those namespaces as an anchored alternation
with its dots already escaped for an EDN string (bb.edn feeds it to shadow-cljs
`--config-merge` for `test:slow:cljs`); it is not a ready-to-use regex, and it
exits 1 when no namespace uses the guard. `flutter test` spends about 7.5 s loading
each of the ~166 generated `test/cljd-out/**/*_test.dart` files, so the
script compiles every test namespace via `clojure -M:clojuredart:cljd compile
<namespaces>`, writes up to min(8, cores) shard files under `build/cljd-agg/`
(greedy by file size; each imports its files and calls their `main`), runs
`flutter test --concurrency N` on the shards, and deletes them. Shards never sit
under `test/`, so plain `clojure -M:clojuredart:cljd test` still works. Tests of
different namespaces share an isolate per shard. Failures stay attributable:
package:test names are ns-qualified (`dao.foo-test/bar-test`).

`flutter test`/`cljd test` globs everything under `test/cljd-out/`, and the
compile does not prune it when a source file is deleted or renamed. The
aggregated lanes only run namespaces that still exist under `test/`, but a
stale file can silently pass a plain `cljd test`. Run `rm -rf test/cljd-out`
before relying on that to prove a deletion or rename took effect.

## Test tiers: quick and slow

The suite has two tiers so that slow tests run less often than quick ones
(owner decision, 2026-10-12).

- **Quick tier:** every test not tagged `^:slow`. `bb test:clj` or
  `clojure -M:test -e :slow` on the JVM; the Node and Dart lanes skip the
  guarded bodies (`dao.test-slow/guard`) unless `DATOM_SLOW_TESTS=1`.
- **Slow tier:** the tagged deftests. `bb test:slow` or
  `clojure -M:test -i :slow -n <ns>` on the JVM.
- **The tagging rule** is the one above ("When to tag a test slow"): a deftest that
  takes about 5 s or more run alone. On 2026-10-12 every JVM test namespace and every
  deftest in the 22 slowest namespaces were timed on a 48-core Linux server, and the
  **63 deftests at 5 s or more** were tagged: `^:slow` on the deftest, plus the guard
  wrapper in `.cljc` files. 37 are guarded, 26 are `^:slow` only (`.clj` files and
  `:clj` reader-conditional branches). Those 22 namespaces now hold **168 slow tests**
  in all (some were already tagged). Per-test times are not in the repo: measure again
  with the method below before changing the cut.
- **Cadence:** the quick tier at every checkpoint (G1) and landing (G2, G3); the slow
  tier before merging a big change and at each phase boundary of a multi-phase track
  (the existing G4). There is no nightly run.

Measured (2026-10-12, origin master plus the tags, Linux servers; see "Remote test
servers" for how to run them):

| Run | Time |
|---|---|
| Full JVM lane, one process, before tagging (shiva) | 70 min |
| Full JVM lane, sharded by subsystem, before tagging (jupiter, 13 shards, 8 at a time) | 46.5 min, set by `yin.repl.python-antlr-test` |
| **Quick tier, sharded by subsystem (jupiter, same 13 shards)** | **5 min 3 s**, 4055 tests, all shards exit 0; longest shards `yang.python` 253 s and `yin.vm.ucf` 204 s |
| **Slow tier (shiva, one JVM per namespace, 6 at a time)** | **53 min**, 22 namespaces, 168 tests, all exit 0; longest `yin.repl.python-antlr-test` 3183 s (about 1580 s alone on the laptop) |

The quick tier has exactly 63 fewer tests than the untagged suite (4118 to 4055).
Some quick parts are still 25 to 69 s when a namespace is run alone (`e2e-c2`,
`prelude-parity`, `int-ops`, `holder.driver`, `compose-rows`): many tests each just
under the 5 s cut (the Python e2e per-test floor is about 2 to 3 s), not shared setup.
A lower cut would shrink the quick tier further and move more tests to the slow tier.

**How the per-test times were measured.** To time every deftest of a namespace alone,
run it without the test runner's alias main (the `:test` alias's `-m cognitect.test-runner`
otherwise swallows `-e`, and every JVM runs the whole suite):

```sh
NS=<namespace> clojure -Sdeps '{:aliases {:t {:extra-paths ["test"]}}}' -M:t timing.clj
# timing.clj: require the namespace, for each deftest var that is not ^:slow:
#   (binding [clojure.test/*test-out* (java.io.StringWriter.)] (clojure.test/test-vars [v]))
# and print the elapsed nanoseconds. One-off setup (a `use-fixtures :once` or a parser
# load) is paid by the first test in file order; check before tagging that test alone.
```

**Sharding the JVM lane by subsystem** (the quick tier; run on a server, see below). The
shards come from `bb test:sub:list <subsystem>` for the 13 subsystems that hold JVM
tests, so each subsystem is one JVM; the script also checks that the shards cover every
JVM test namespace under `test/`:

```sh
#!/usr/bin/env bash
# Usage: shard-jvm.sh <clone> [parallel]  -- JVM lane sharded by subsystem (fast tests, -e :slow)
set -u
export PATH="$HOME/.local/bin:/snap/bin:$PATH"
cd "$1" || exit 2
PAR="${2:-8}"
mise trust >/dev/null 2>&1
eval "$(mise env -s bash)"
OUT="$1/target-ci/shards"; rm -rf "$OUT"; mkdir -p "$OUT"
ts() { date +%H:%M:%S; }
echo "start $(ts) $(git rev-parse --short HEAD) par=$PAR" | tee "$OUT/summary.txt"
npm ci --silent >"$OUT/npm.log" 2>&1                   && echo "npm ok $(ts)" | tee -a "$OUT/summary.txt"
bb build:yin-repl-node >"$OUT/build-node.log" 2>&1     && echo "node repl built $(ts)" | tee -a "$OUT/summary.txt"
bb gen:antlr >"$OUT/antlr.log" 2>&1             && echo "antlr ok $(ts)" | tee -a "$OUT/summary.txt"
SUBS="yang.python yin.repl yin.vm yin.vm.ucf dao.stream dao.jing dao.gui dao.space demo dao.data dao.postgraphics yang.base dao.base"
: > "$OUT/covered.txt"
for s in $SUBS; do
  bb test:sub:list "$s" 2>/dev/null | awk "/^clj:/{f=1;next} /^[A-Za-z]/{f=0} f{gsub(/ /,\"\");print}" > "$OUT/ns-$s.txt"
  cat "$OUT/ns-$s.txt" >> "$OUT/covered.txt"
  echo "$s $(wc -l < "$OUT/ns-$s.txt")" >> "$OUT/plan.txt"
done
# coverage: every JVM test namespace under test/ vs the union of the shards
find test -type f \( -name "*_test.clj" -o -name "*_test.cljc" \) | sed -e "s#^test/##" -e "s#\.cljc\?\$##" -e "s#/#.#g" -e "s#_#-#g" | sort -u > "$OUT/all.txt"
sort -u "$OUT/covered.txt" > "$OUT/covered.sorted"
comm -23 "$OUT/all.txt" "$OUT/covered.sorted" > "$OUT/ns-uncovered.txt"
echo "all=$(wc -l < $OUT/all.txt) covered=$(wc -l < $OUT/covered.sorted) uncovered=$(wc -l < $OUT/ns-uncovered.txt)" | tee -a "$OUT/summary.txt"
run_shard() {
  s=$1; f="$OUT/ns-$s.txt"; [ -s "$f" ] || return 0
  args=(); while read -r n; do args+=(-n "$n"); done < "$f"
  t0=$(date +%s); clojure -M:test -e :slow "${args[@]}" >"$OUT/$s.log" 2>&1; rc=$?
  echo "$s rc=$rc secs=$(( $(date +%s)-t0 )) $(ts) $(grep -E "^Ran " "$OUT/$s.log" | tail -1)" | tee -a "$OUT/summary.txt"
}
export -f run_shard; export OUT; export -f ts
printf "%s\n" $SUBS uncovered | xargs -P "$PAR" -I{} bash -c "run_shard {}"
echo "done $(ts)" | tee -a "$OUT/summary.txt"
```

**The slow tier** runs each tagged namespace on its own JVM, a few at a time:

```sh
#!/usr/bin/env bash
# Usage: slow-tier.sh <clone> [parallel] -- the ^:slow tests of the tagged namespaces, one JVM per namespace
set -u
export PATH="$HOME/.local/bin:/snap/bin:$PATH"
cd "$1" || exit 2
PAR="${2:-6}"
mise trust >/dev/null 2>&1
eval "$(mise env -s bash)"
OUT="$1/target-ci/slowtier"; rm -rf "$OUT"; mkdir -p "$OUT"
run_ns() {
  n=$1; t0=$(date +%s)
  clojure -M:test -i :slow -n "$n" > "$OUT/$n.log" 2>&1; rc=$?
  echo "$n rc=$rc secs=$(( $(date +%s)-t0 )) $(grep -E "^Ran " "$OUT/$n.log" | tail -1)" >> "$OUT/summary.txt"
}
export -f run_ns; export OUT
echo "start $(date +%H:%M:%S) $(git rev-parse --short HEAD) par=$PAR" > "$OUT/summary.txt"
xargs -P "$PAR" -I{} bash -c "run_ns {}" < "$HOME/ci/pertest-ns.txt"
echo "done $(date +%H:%M:%S)" >> "$OUT/summary.txt"
```

## Remote test servers: shiva and jupiter

Two Linux x86_64 servers run the full suite faster than a laptop. Both are
reached over SSH as `sto` with key authentication, and both have `mise`, `tmux`
and `git`.

| Host | Cores / RAM | Toolchain state (server snapshot checked 2026-10-11; repo selection comes from `mise.toml`) |
|---|---|---|
| `shiva.stigmergy.systems` | 24 / 62 GB | Server snapshot: java 21, clojure 1.12.4.1602, node 25.6.1, babashka, cljstyle, zprint, flutter 3.47.1. The repo's `mise.toml` selects Flutter `latest` on beta, not a fixed version. Activate the repo toolchain before running any lane. |
| `jupiter.stigmergy.systems` | 48 / 62 GB | Server snapshot (rechecked 2026-10-29): java 21, clojure 1.12.4.1602, node 25.6.1, babashka, cljstyle, zprint, flutter 3.47.8 (the stale hand-symlinked 3.41.9 was replaced via `mise install flutter@3.47.8` after it failed to compile `dart:ui`-using cljd namespaces; if the Dart lane dies in the cljd compile, check `mise ls flutter` first). Tri-host quick tier verified green on 2026-10-29 (see Measured times). |

**Never test in `~/workspace/datomworld` on either host.** It is a working copy
that is behind origin (shiva: 45 commits behind on 2026-10-11) and belongs to its
owner. Use a scratch clone under `~/ci/` and leave the working copy alone.

### Get the code there: push to origin, never rsync

Rsyncing a worktree over the WAN ran at about 150 KB/s (576 MB of tracked files
would take about an hour), and an unanchored `--exclude build` also dropped source
directories named `build`, `out` and `target`. Both hosts reach GitHub, so push the
branch from the laptop (a feature branch, never master), make one scratch clone on
the host from its existing checkout (a local clone takes seconds), and fetch:

```sh
# once per host: a separate scratch clone, not the working copy
ssh shiva.stigmergy.systems 'set -e; mkdir -p ~/ci; cd ~/workspace/datomworld;
  URL=$(git remote get-url origin);
  git clone -q --no-checkout ~/workspace/datomworld ~/ci/datomworld-<name>;
  cd ~/ci/datomworld-<name>; git remote set-url origin "$URL";
  git fetch -q origin; git checkout -q --detach origin/<branch>'
```

To test another branch later, fetch and check it out in the scratch clone with
`git fetch origin` then `git checkout --detach origin/<branch>`. A branch that
was never pushed cannot be tested remotely.

### Activate the toolchain in the right order

A non-interactive `ssh host cmd` does not load the shell profile, so `mise` and its
tools are not on PATH. In a script, do this in exactly this order, after the `cd`
into the clone:

```sh
export PATH="$HOME/.local/bin:/snap/bin:$PATH"   # mise: ~/.local/bin on shiva, /snap/bin on jupiter
cd ~/ci/datomworld-<name>
mise trust >/dev/null 2>&1                       # trust is per path; a fresh clone is untrusted
mise install                                    # install versions selected by this checkout's mise.toml
eval "$(mise env -s bash)"                       # puts the repo-selected tools on PATH
mise current flutter                            # verify the selected Flutter version
```

Activating mise before the `cd` and `mise trust` leaves the repo-selected tools
(flutter in particular) off PATH: the Dart lane then dies in seconds with "Can't
find flutter on PATH", and so does `bb build:yin-repl-peer`.

### Which lanes may overlap (reconciling the one-lane-set rule)

"One lane set at a time" and "one Dart lane at a time" are per-machine rules about
CPU: they exist because a laptop has 8 cores. They are not a rule about repos.
`test.subsystems.md` section 5 item 6 says the same ("on one machine where
avoidable"). On shiva (24 cores) and jupiter (48) the cores are not the limit, so
lanes may overlap, with these limits:

- **JVM and Dart lanes must not share one clone.** `src/dev/affected.clj`
  (`serialized-pairs`, the `[:clj :cljd]` pair) serializes them whenever a JVM test
  runs the cljd compiler, and some do (`yin.repl.build-test`,
  `yin.vm.linker.cross-host-transfer-test`, `dao.stream.ws-project-cross-jvm-test`);
  the compiler writes the clone's `lib/cljd-out` and `build/`. A second scratch clone
  gives the Dart lane its own copies, so the two lanes then overlap safely. This is
  by the design's own reasoning ("each worktree owns its own `cljd-out`"), not yet
  verified by a measured run.
- **JVM and Node may share a clone.** Node writes `target/` and `.shadow-cljs/`; the
  JVM lane only reads `target/yin-repl.js`.
- **One Dart lane per host at a time**, even across clones: the lane shards up to
  `min(8, cores)` Flutter processes and shares the host's Flutter cache.
- **Different branches in different clones** are independent, so two branches may
  run on one host if its cores and RAM allow (62 GB each).
- Do not treat this as permission to overlap lanes on the laptop.

The first run on shiva (2026-10-11) overlapped JVM and Dart in one clone before
this rule was written down; its results are advisory until repeated with a
separate Dart clone.

### The jolt J2 lane: run it with `--jobs` on the servers

The jolt lane's core mode (`bb src/dev/jolt_j2_lane.clj core [--jobs N]
[--only re] [--timeout-ms M]`) runs each test namespace as its own child jolt
process (own Chez VM, own native parser library load, own log under
`build/j2-logs/core/`), so the namespaces cannot corrupt one another — the
only thing `--jobs N` overlaps is the waiting. The default is `jobs 1`
(strictly serial), which on jupiter makes the full ~179-namespace lane a
5-6-hour run.

On the servers, run it with `--jobs 16`:

- jolt children are essentially single-threaded (the 16 MiB parse thread
  only burns during parses) and cost 1-2 GB each, so 16 concurrent children
  sit far inside jupiter's 48 cores / 62 GB and shiva's 24 / 62;
- the lane's wall time is dominated by a few long namespaces
  (`yin.repl.python-antlr-test` has an explicit 3-hour step,
  `yin.vm.semantic.safepoint-harness-test` needs one — see
  `step-timeout-ms` in `src/dev/jolt_j2_lane.clj`), so beyond ~16 workers
  the critical path is the longest child, not the count;
- **parallel is unproven until checked once**: the recorded lane evidence
  (J2 closure, the safepoint 60/265,225/0 pass) was all produced `jobs 1`.
  The first parallel run must be compared per-namespace against a serial
  baseline; any namespace that flips red gets one serial re-run to
  attribute before its evidence is trusted. Until that first comparison
  passes, keep `jobs 1` for anything whose verdict is timing-sensitive —
  timeout and wall-time measurements, native-memory/leak gates, and the
  merge-decision qualification runs.

Suites that exceed the lane's default step budget are pinned in the lane's
`step-timeout-ms` map with their measured wall time; a namespace timing out
under the default budget is a reason to measure it serially and add an
entry, not to re-run under the same bound.

### The full run

Run long jobs inside `tmux` so the SSH session can drop. The script below does the
serial prerequisites once, then runs the JVM and Node lanes in the first clone and
the Dart lane in a second clone, all concurrently, with the same commands the `bb`
tasks use (the `bb test` task runs them one after another), and records each
lane's exit code and seconds. Make the Dart clone the same way as the first, at the
same commit, with a name like `~/ci/datomworld-<name>-dart`:

```sh
#!/usr/bin/env bash
# ~/ci/run-full.sh <jvm-node-clone> [<dart-clone>]   (both hosts run all three lanes; a second clone for Dart is recommended)
set -u
export PATH="$HOME/.local/bin:/snap/bin:$PATH"
DART="${2:-}"
cd "$1" || exit 2
mise trust >/dev/null 2>&1
eval "$(mise env -s bash)"
LOG="$1/target-ci"; mkdir -p "$LOG"
ts() { date +%H:%M:%S; }
echo "start $(ts) $(git rev-parse --short HEAD)" | tee "$LOG/summary.txt"
npm ci --silent >"$LOG/npm.log" 2>&1               && echo "npm ok $(ts)" | tee -a "$LOG/summary.txt"
bb build:yin-repl-node >"$LOG/build-node.log" 2>&1 && echo "node repl built $(ts)" | tee -a "$LOG/summary.txt"
bb gen:antlr >"$LOG/antlr.log" 2>&1         && echo "antlr ok $(ts)" | tee -a "$LOG/summary.txt"
bb build:yin-repl-peer >"$LOG/build-peer.log" 2>&1 && echo "dart peer built $(ts)" | tee -a "$LOG/summary.txt"
lane() { name=$1; shift; s=$(date +%s); "$@" >"$LOG/$name.log" 2>&1; rc=$?
         echo "$name rc=$rc secs=$(( $(date +%s)-s )) $(ts)" | tee -a "$LOG/summary.txt"; }
lane jvm  clojure -M:test -e :slow &
lane node clj -M:cljs -m shadow.cljs.devtools.cli compile test &
if [ -n "$DART" ]; then                            # its own clone (own lib/cljd-out, build/)
  ( cd "$DART" && mise trust >/dev/null 2>&1 && eval "$(mise env -s bash)"
    bb build:yin-repl-peer >"$LOG/build-peer-dart.log" 2>&1
    lane dart bb src/dev/cljd_agg.clj ) &
fi
wait
echo "done $(ts)" | tee -a "$LOG/summary.txt"
```

```sh
ssh <host> 'tmux new-session -d -s ci-full "bash -lc \"~/ci/run-full.sh ~/ci/datomworld-<name> ~/ci/datomworld-<name>-dart > ~/ci/run-full.out 2>&1\""'
```

Watch it: `ssh <host>`, then `tmux attach -t ci-full` (detach with `Ctrl-b d`; do not
press Ctrl-C inside it), or `tail -f ~/ci/datomworld-<name>/target-ci/{jvm,node,dart}.log`
and `cat .../summary.txt` (all logs and the summary are in the first clone). The session's own output is empty by design: the lanes
write to the logs. The JVM lane uses `-e :slow`, so it skips `^:slow` tests, like
`bb test:clj`. For the slow set use `-i :slow` (or `bb test:slow`); the slow C4
scanner row took about 11-19 minutes on the JVM and about 79 minutes on Node
(measured 2026-10-11).

### Known differences from the laptop

- **`dao.stream.datagram.dart-test/refusals-and-lifecycle-events` fails on Linux.**
  The assertion "a bind on a taken port deposits bind-failed" gets no `bind-failed`
  event. A second UDP bind on a taken port evidently behaves differently on Linux
  than on macOS (inferred, not confirmed). The test is in `dao.stream`, which the
  semantic-register slices do not touch: treat it as a known Linux-only failure,
  not a regression, until it is fixed or guarded.
- A JVM lane started before the Dart peer is built skips the Dart-peer tests with a
  printed notice. The script builds the peer first so they run.
- Both hosts run other work (a QEMU guest and rustdesk on shiva); idle load is
  under 1, but timings are not a clean benchmark.

### The nightly full suite and the branch-testing rule (2026-10-30 owner ruling)

Owner, verbatim: "since the complete test can take a good 7-8hrs to run
completely, it should be schedule to run once a day at 3am. while
branch/worktree test should only run test that are targeting code
changes."

Two tiers, by purpose:

- **The nightly full suite** (`~/ci/nightly-full.sh` on shiva, tmux
  session `nightly`, scheduled 03:00 daily through the orchestrator's
  automation): every lane, every host target, against origin/master —
  the JVM full suite, the full Node suite, the full Dart suite. Its
  results are a post-hoc integrity net, NOT a landing gate. Failures it
  finds route to fix rounds the next morning.
- **Branch/worktree testing**: only the namespaces that target the
  changed code (the diff's own tests plus the surfaces the diff touches),
  one JVM per namespace, plus one cheap portable lane (Node) when cljc
  goldens or fixtures moved. Never the full tier on a branch: a branch
  gate needs the changed code proven, not the whole tree re-proven.

An orchestrator landing a slice cites the subset runs plus the gate
sign-off; the nightly catches what the subset could not see, and a red
nightly owns the follow-up.

### The nightly full suite and the branch-testing rule (2026-10-30 owner ruling)

Owner, verbatim: "since the complete test can take a good 7-8hrs to run
completely, it should be schedule to run once a day at 3am. while
branch/worktree test should only run test that are targeting code
changes" — and, clarifying the nightly's target: "it should not be for
only a worktree/branch. the nightly complete test should be for master."

Two tiers, by target and purpose:

- **The nightly full suite runs MASTER ONLY** (`~/ci/nightly-full.sh` on
  shiva, tmux session `nightly`, scheduled 03:00 daily through the
  orchestrator's automation; it fetches and checks out origin/master
  before anything runs). Every lane, every host target: the JVM full
  suite, the full Node suite, the full Dart suite. Its results are a
  post-hoc integrity net for master, NOT a landing gate. Failures it
  finds route to fix rounds the next morning.
- **Branch/worktree testing runs only the namespaces that target the
  changed code** (the diff's own tests plus the surfaces the diff
  touches), one JVM per namespace, plus one cheap portable lane (Node)
  when cljc goldens or fixtures moved. Never the full tier on a branch:
  a branch gate needs the changed code proven, not the whole tree
  re-proven. A branch is never promoted by its own testing — the merge
  to master is what puts it under the nightly.

An orchestrator landing a slice cites the subset runs plus the gate
sign-off; the nightly catches what the subset could not see, and a red
nightly owns the follow-up.

### Measured times (first runs, 2026-10-11, commit 84c7c9b7, all lanes concurrent)

Shiva, Dart lane: 1054 s (17.5 min) cold, with the JVM and Node lanes running beside
it, against about 9 minutes on the 8-core laptop run alone. Prerequisites (`npm ci`,
Node REPL, ANTLR) took about 30 s; the Dart peer 91 s. JVM and Node lane times are
not recorded yet: fill them in from `summary.txt` after a completed run. Running the
lanes concurrently on one host did not make the Dart lane faster, so count on the
extra cores and a free laptop, and measure before relying on a speedup.

### Jupiter verification run (2026-10-29, commit 7c0a078f, scratch clone, sequential lanes)

First full tri-host verification of jupiter. Prerequisites (npm ci, Node REPL,
ANTLR via `gen:antlr`, Dart peer) all succeeded; the stale 3.41.9 Flutter was
replaced with 3.47.8 before the Dart lane. Results: **JVM quick tier
(`test:quick-sharded`, 12 workers) rc=0 in 702 s** (cold, every shard green);
**Node quick tier rc=0 in 4583 s** (all tests pass, but roughly 15x the
laptop — route Node lanes to the laptop or shiva); **Dart quick tier
rc=1 in 1495 s with the ONLY failure the documented known Linux-only red**
(`dao.stream.datagram.dart-test/refusals-and-lifecycle-events`, same
taken-port/bind-failed signature recorded above). Routing summary: jupiter
is the preferred JVM/sharding box (48 cores); Node stays off it when wall
time matters; Dart works on either host. Timings are single cold runs, not
medians, and two foreign QEMU guests idle on the host.

One exception to the concurrency above: never run two lanes that both trigger
`gen:antlr` (the quick `test:cljs`/`test:cljd` tasks) in the SAME worktree at the
same time — they race on `build/antlr` and `lib/antlr` and fail with
"Patch does not apply" or "Generated sources differ from the recorded digests".
Run them sequentially, or in separate clones (as the slow-tier harness does).
The fix after a collision is `rm -rf build/antlr lib/antlr` and a rerun; the
generator is not idempotent over an already-patched tree. Corroborated twice in
one week (the M3 round and the C3 completion round, 2026-10-18).

### Remote agent execution (delegate CLIs on shiva and jupiter)

Owner relay, 2026-10-24: the delegate agent CLIs — `claude`, `codex`, `glm` —
are installed and usable **on the remote hosts themselves**, not only from the
orchestrator's laptop. This generalizes this section from "run tests remotely
over SSH" to "delegate whole work agents remotely": a heavy implementation or
verification leg (test lanes, native builds, fault-injection runs, multi-hour
suites) can be executed by a delegate agent running on the server, keeping the
orchestrator's laptop unloaded.

The pattern (composed from the rules above plus the delegate-invocation
reference):

1. **Land the branch on origin first** (push from the laptop; never rsync) and
   prepare the scratch clone per "Get the code there".
2. **Start a named tmux session** for the task; run the agent inside it with
   the toolchain activated *inside* the tmux command (`eval "$(mise env -s
   bash)"` after the `cd` — the non-interactive SSH shell does not have it).
   A fresh clone hits mise's interactive trust prompt, which blocks the whole
   session until answered via `tmux send-keys`; pre-empt it with `mise trust`
   inside the same command.
3. **The remote agent works in the clone**: implement, build, run lanes,
   write its findings artifact — the same brief form as any delegate dispatch
   (role template, bounded file ownership, deliverable path), just executing
   on the host. Long jobs survive SSH drops because of tmux.
4. **The orchestrator polls detached files, never the pane** (pane captures
   carry control characters that make output look empty): rc files, summary
   logs and `ls *.flag` sentinels written by the run.
5. **Division of labor**: bookkeeping and polling are orchestrator SSH
   one-liners; execution belongs to the remote agent. Do not run heavy lanes
   from the orchestrator's laptop while a remote agent owns the same gate.
6. **Same trust rules as any delegate**: remote execution does not waive
   Workflow steps 6–7 of the orchestrator role — artifacts are verified, and
   review before commit applies to remote-produced work identically.
   Session-ID capture and resume rules from the delegate-invocation reference
   apply per host (each host's `~/.claude`, `~/.codex` store is its own).

Known state: `claude` and `codex` verified working on shiva (2026-10-24, the
jolt canonical-fixes verification ran there); `glm` reported available by the
owner on both hosts, not yet exercised. Both hosts are toolchain-verified for
the full tri-host suite (jupiter verified 2026-10-29 — see Measured times;
its 3.41.9 Flutter was replaced with 3.47.8, and model availability per
host is governed by `routing-status.md`, not this file).

## Testing Philosophy & TDD

Write tests before implementing features (TDD).

Tests define the contract and expected behavior. Implementation follows from the tests, not the other way around.

When implementing a feature or fix:
1. **Red phase**: Write the test first — define what should happen.
2. **Green phase**: Implement the minimum code to pass the test.
3. **Refactor phase**: Refactor if needed while keeping tests green.

This ensures code is testable by design and implementation matches actual requirements.

## Debugging Malformed CLJ / EDN / CLJS

A single missing bracket can silently break an entire file. The compiler may still succeed, with subsequent forms nesting inside the broken one.

1. **Run clj-kondo first**:
   ```sh
   clj -M:kondo --lint path/to/file.cljs
   ```

2. **If clj-kondo reports a mismatch, write a bracket-tracing script**:
   Do NOT try to eyeball-count brackets. Track a stack of `(char, line, col)` for every opener. Handle: string literals, escaped chars, and `;;` line comments.

3. **Fix one bracket at a time, re-run checker after each fix**:
   Common patterns:
   - Hiccup vector missing `]` causes siblings to become extra arguments.
   - `defn`/`let` missing `)` causes subsequent top-level forms to nest inside.
   - Both can coexist while total bracket count is zero-sum.

4. **After brackets balance**, rerun `clj-kondo`, then rebuild with `clj -M:cljs -m shadow.cljs.devtools.cli compile <build>`.

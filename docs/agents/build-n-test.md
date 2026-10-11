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
# Run one Dart lane at a time repo-wide.
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
# for every gate from G1 upward. Run one lane set at a time repo-wide to avoid contention.
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

Prerequisites (`build:yin-repl-peer`, `build:yin-repl-node`, `gen:python-antlr`)
and lane serialization (`[:clj :cljd]`) are derived purely from text reach of the
selected namespaces and run automatically when needed.

#### Gate Ladder (docs/design/test.subsystems.md)

1. **G0 Iterate**: Run only the subsystem's JVM lane (`bb test:sub:clj <own>` or `clojure -M:test -n <ns>`).
2. **G1 Checkpoint**: Run the three-lane subsystem test (`bb test:sub <own>`). Proves the home suite across all three hosts before reporting ready.
3. **G2 Land**: Run `bb test:changed` in the worktree across all three lanes (plus `bb test:sub <own> --slow` if slow tests are present). Covers dependents across the change (for foundation subsystems like `dao.stream` or `dao.jing`, this correctly runs nearly the full suite).
4. **G3 Master**: The mandatory landing gate on master after merge, and for any wide change (`bb test`).
5. **G4 Release**: Full or slow test run before a big release or cross-cutting merge (`bb test:slow` or `bb test:all`).


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

## Remote test servers: shiva and jupiter

Two Linux x86_64 servers run the full suite faster than a laptop. Both are
reached over SSH as `sto` with key authentication, and both have `mise`, `tmux`
and `git`.

| Host | Cores / RAM | Toolchain state (checked 2026-10-11) |
|---|---|---|
| `shiva.stigmergy.systems` | 24 / 62 GB | Complete: java 21, clojure 1.12.4.1602, node 25.6.1, babashka, cljstyle, zprint, flutter 3.47.1-stable. All three lanes run. |
| `jupiter.stigmergy.systems` | 48 / 62 GB | java 21, clojure, node, zprint; babashka installed with `mise install babashka@latest`; flutter 3.41.9 is NOT the pinned version. Run the JVM and Node lanes here, not Dart. |

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
eval "$(mise env -s bash)"                       # puts the repo-pinned tools on PATH
```

Activating mise before the `cd` and `mise trust` leaves the repo-pinned tools
(flutter in particular) off PATH: the Dart lane then dies in seconds with "Can't
find flutter on PATH", and so does `bb build:yin-repl-peer`.

### The full run

Run long jobs inside `tmux` so the SSH session can drop. The script below does the
serial prerequisites once, then runs the three lanes concurrently with the same
commands the `bb` tasks use (the `bb test` task runs them one after another) and
records each lane's exit code and seconds:

```sh
#!/usr/bin/env bash
# ~/ci/run-full.sh <checkout-dir>
set -u
export PATH="$HOME/.local/bin:/snap/bin:$PATH"
cd "$1" || exit 2
mise trust >/dev/null 2>&1
eval "$(mise env -s bash)"
LOG="$1/target-ci"; mkdir -p "$LOG"
ts() { date +%H:%M:%S; }
echo "start $(ts) $(git rev-parse --short HEAD)" | tee "$LOG/summary.txt"
npm ci --silent >"$LOG/npm.log" 2>&1               && echo "npm ok $(ts)" | tee -a "$LOG/summary.txt"
bb build:yin-repl-node >"$LOG/build-node.log" 2>&1 && echo "node repl built $(ts)" | tee -a "$LOG/summary.txt"
bb gen:python-antlr >"$LOG/antlr.log" 2>&1         && echo "antlr ok $(ts)" | tee -a "$LOG/summary.txt"
bb build:yin-repl-peer >"$LOG/build-peer.log" 2>&1 && echo "dart peer built $(ts)" | tee -a "$LOG/summary.txt"  # shiva only
lane() { name=$1; shift; s=$(date +%s); "$@" >"$LOG/$name.log" 2>&1; rc=$?
         echo "$name rc=$rc secs=$(( $(date +%s)-s )) $(ts)" | tee -a "$LOG/summary.txt"; }
lane jvm  clojure -M:test -e :slow &
lane node clj -M:cljs -m shadow.cljs.devtools.cli compile test &
lane dart bb src/dev/cljd_agg.clj &                # shiva only; omit on jupiter
wait
echo "done $(ts)" | tee -a "$LOG/summary.txt"
```

```sh
ssh <host> 'tmux new-session -d -s ci-full "bash -lc \"~/ci/run-full.sh ~/ci/datomworld-<name> > ~/ci/run-full.out 2>&1\""'
```

Watch it: `ssh <host>`, then `tmux attach -t ci-full` (detach with `Ctrl-b d`; do not
press Ctrl-C inside it), or `tail -f ~/ci/datomworld-<name>/target-ci/{jvm,node,dart}.log`
and `cat .../summary.txt`. The session's own output is empty by design: the lanes
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

### Measured times (first runs, 2026-10-11, commit 84c7c9b7, all lanes concurrent)

Shiva, Dart lane: 1054 s (17.5 min) cold, with the JVM and Node lanes running beside
it, against about 9 minutes on the 8-core laptop run alone. Prerequisites (`npm ci`,
Node REPL, ANTLR) took about 30 s; the Dart peer 91 s. JVM and Node lane times are
not recorded yet: fill them in from `summary.txt` after a completed run. Running the
lanes concurrently on one host did not make the Dart lane faster, so count on the
extra cores and a free laptop, and measure before relying on a speedup.

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

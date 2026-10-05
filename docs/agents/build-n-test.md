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
# On Node, shadow also runs the test namespaces those eight require (about
# seven more, e.g. lower-portable-test); on Dart only the eight namespaces run.
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
# Python slow set (2026-10-06): 63 ^:slow and 15 guard-only tests in the
# yang.python.antlr e2e, e2e-c1, prelude-parity, safepoint and float-address
# tests; a Python slice landing runs `clojure -M:test -i :slow -n <ns>` for
# its changed Python namespaces.
# While iterating, run only `bb test:clj` (or one namespace: `clojure -M:test -n
# <ns>`), not the full `bb test`. Run the full three-lane `bb test` once per
# slice, before landing, and one lane set at a time: overlapping runs slow each
# other down. Cross-host bugs show only on Node or Dart, so the full run is
# still the gate before a commit.
# Measured fast-lane times (2026-10-03): bb test about 14 min = JVM 5.5 + Node
# 3.8 + Dart 4.3, plus builds. test:slow: Node and Dart about 5 min each (mostly
# compile); the JVM half is estimated at 17 min (long-loops-test alone is 14),
# not yet run end to end. test:all: JVM 20+ min, also an estimate.
npm test             # Node.js tests

# Cross-host peers the JVM lane spawns. `bb test` builds both. A bare
# `bb test:clj` builds only the Node REPL (it depends on build:yin-repl-node);
# without build:yin-repl-peer first, the Dart-peer tests skip with a printed notice.
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

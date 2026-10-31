Created-GMT: 2026-10-05
Coding-Agent: claude (opus-5-5)
# Findings: `bb test:changed`

Worktree datomworld-tc, branch dev-test-changed, no git writes. Diff:
new src/dev/affected.clj, src/dev/affected_test.clj; additive edits to
bb.edn (5 new tasks), src/dev/cljd_agg.clj (--only),
docs/agents/build-n-test.md (18-line section).

## Verification

- TDD. Red: affected_test.clj against a stub ns, 16 tests, 83
  assertions, 71 failures. Green: `bb -cp src/dev -m affected-test`,
  16 tests, 83 assertions, 0 failures.
- The test lives under src/dev, not test/: the JVM lane's runner loads
  every test/ ns, and affected.clj needs babashka (edamame, babashka.fs).
- kondo `clj -M:kondo --lint` on the three .clj files: 0 errors,
  0 warnings.
- cljstyle: BLOCKED. `cljstyle fix` and `cljstyle check` both need
  approval in this session. Lines checked by hand instead: all <= 80
  columns, ASCII only. Run `cljstyle fix`/`check` on the three .clj
  files and bb.edn before landing.
- Corpus: all 474 code files under src/ and test/ (not test/cljd-out)
  read without error. The brief guessed ~700; 474 is the real count.
  scan-index exits 1 and names each file that fails to read.

## Design choices

- Parser: edamame (in bb) reads the first ns form once per feature
  (:clj, :cljs, :cljd) and once with a feature that matches nothing,
  so `:default` branches count too. Deps are the union of those
  readings. Handles :require/:use/:require-macros/:use-macros, symbols,
  [lib & opts], prefix lists, npm strings (ignored), `^:meta` names.
  Gotcha: edamame `parse-next` needs `normalize-opts` first, or it throws
  "Duplicate key: null".
- Test namespace: a file under test/ whose ns ends in -test or holds a
  `(deftest`. Lanes come from the file extension: clj = .clj/.cljc,
  cljs = .cljs/.cljc, cljd = `*_test.clj[cd]` (cljd_agg's own rule).
- Selection, per changed file (`--list` prints the reason for each):
  - code: reverse closure of its ns. A deleted file gets its ns from its
    path. Each src file also adds its conventional test.
  - non-code: the closure of every ns whose text mentions its path or
    basename. Deviation from the brief: a `.md` under test/resources is
    a fixture, not ignored (cbor-v1.errata.md is read by
    cbor-conformance-test).
  - added rule, tree walkers: a ns with `(file-seq (io/file "<root>"))`
    is selected by any change under <root>. Without it,
    store-write-audit-test (walks src/) and cbor-conformance-test (walks
    test/ and src/dev/) would be missed. The cost: every src change pulls
    in store-write-audit-test, and every test/ change pulls in
    cbor-conformance-test.
  - wide: the brief's list, plus package.json, package-lock.json,
    pubspec.yaml and pubspec.lock (they change the Node and Dart lanes).
- Exit codes: 0 pass or nothing selected; 1 a lane failed or bad input;
  2 wide change. On a wide change it prints "wide change: run bb test",
  lists all 223 test namespaces as selected, and runs nothing. My
  reading of "select everything" is that a wide change belongs to the
  serial `bb test` gate, not to a parallel run of the whole suite.
- A lane is FAIL if it exits nonzero, has failures or errors, or its
  log has no readable count. That last case matters: shadow
  `compile test` exited 0 when node crashed on MODULE_NOT_FOUND, and the
  table still showed FAIL.
- Prerequisites run once, before the parallel phase, and only when
  needed. A build is needed when a selected test, or a namespace it
  requires, names that build's output:
  - "yin-repl.js" -> build:yin-repl-node
  - "yin-repl-peer" -> build:yin-repl-peer
  - Python3Parser, Python3Lexer or build/antlr -> gen:python-antlr
    (JVM lane only)

  This leans conservative: a mention in a skip message counts. Unlike
  test:cljs, the Node lane does not always build the Node REPL. No Node
  test names target/yin-repl.js, so it builds only on demand. Samples:

  | change          | clj/cljs/cljd tests | builds                |
  |-----------------|---------------------|-----------------------|
  | yin/repl.cljc   | 22/19/19            | node, peer            |
  | integer.cljc    | 8/4/4               | antlr                 |
  | ucf/ledger.cljc | 16/15/15            | none                  |
  | dao/stream.cljc | 187/172/170         | node, peer, antlr     |

  The dao/stream.cljc change also serializes JVM then Dart (next item).
- Parallel safety, checked file by file:
  - Dart writes test/cljd-out, lib/cljd-out, build/cljd-agg and
    .clojuredart.
  - Node writes target/node-tests.js and .shadow-cljs/.
  - JVM tests write uniquely named target/test-*-<uuid> files.
  - Node vs Dart: no shared output. Shadow scans test/ and src/ only for
    cljs/cljc/js files, never the .dart ones. Safe.
  - JVM vs Node: target/ names are distinct. Safe.
  - JVM vs Dart: unsafe exactly when a selected JVM test runs the cljd
    compiler. yin.vm.linker.cross-host-transfer-test compiles
    transfer-peer into lib/cljd-out. That pair runs serially (JVM, then
    Dart) when a selected JVM test's closure has the literal
    "-M:clojuredart:cljd". The test is ^:slow, so the fast lane never
    runs it, but the rule stays conservative.
  - The peer and Node REPL builds run before the parallel phase, so
    they never overlap a lane.
- cljd_agg.clj `--only a,b`: restricts both the `cljd compile` list and
  the shards. A selected ns with no generated Dart file is fatal; so is
  `--only` with no value. Without --only nothing changes; `--slow-regex`
  was re-run and prints as before.
- Node lane: copies test:slow:cljs's --config-merge :ns-regexp mechanism
  (dots escaped for EDN) and removes DATOM_SLOW_TESTS from the child
  env. JVM lane: `clojure -M:test -e :slow -n ...`. Dart lane: inherits
  DATOM_SLOW_TESTS.

## Smoke checks (`bb test:changed:list`)

- This worktree's own diff (bb.edn, src/dev/affected*.clj,
  src/dev/cljd_agg.clj, collab/ ignored): wide, exit 2, 223 of 223
  selected (clj 208, cljs 193, cljd 188). Correct: src/dev and bb.edn
  are wide.
- `--changed src/cljc/yin/vm/integer.cljc`: 8 tests (clj 8, cljs 4,
  cljd 4). Includes yang.python.antlr.prelude-parity-test and
  yin.vm.integer-test. Hand check: only yang.python.antlr.prelude
  requires yin.vm.integer, so the closure is the prelude's tests
  (e2e, e2e-c1, e2e-c2, float-address, prelude-parity, safepoint) plus
  integer-test plus store-write-audit-test (tree walker).
- `--changed src/cljc/yin/vm/ucf/ledger.cljc`: 16 tests (clj 16, cljs
  15, cljd 15). Includes all 9 ucf authority tests (authority-test plus
  admission, completion, completion-fold, front, grant, inherited,
  input, reclaim), ledger-test, ledger-fixture-test, custody,
  crash-cut, durability and holder.evidence. The extra JVM one is
  store-write-audit-test.
- `--changed docs/x.md`: ignored, 0 selected, exit 0.
- `--changed test/resources/yin/vm/ucf/ledger-v1.txt
  test/resources/dao/jing/cbor-v1.errata.md`: 4 tests:
  - ledger-v1.txt via yin.vm.ucf.ledger-fixtures (it names the path)
    and its dependent ledger-fixture-test.
  - errata.md via cbor-conformance-test and cbor-test.
- `--changed src/cljc/yin/repl/main.cljc`: includes
  yin.repl.dht-process-test (it requires yin.repl.main).
- Wide plus others (`--changed docs/a.md src/.../integer.cljc bb.edn`)
  through the bb task: exit 2. Bad `--lane jvm`: exit 1.

## Timings (real runs, same 5-namespace selection)

The selection: `--changed` with the integer, ucf ledger, ucf
authority/grant and ucf custody test files. That gives those 4
namespaces plus dao.jing.cbor-conformance-test, a tree walker of test/.

- `bb test:changed:clj`: 5 namespaces, 66 tests, 508 assertions, 0
  failures, 10 s for the lane, 9.8 s wall overall. Log confirms exactly
  those 5 "Testing" lines.
- `bb test:changed:cljd`: 5 namespaces, 63 tests, 0 failures. 38 s,
  including the cljd compile of only those 5.
- `bb test:changed:cljs`: NOT COMPLETED. The shadow compile of the 5
  (155 files) took 17 s with exit 0. Then node died: "Cannot find module
  '@noble/hashes/blake3.js'", because this fresh worktree has no
  node_modules. `npm ci`, and symlinking the main checkout's
  node_modules, both needed approval in this session, so the tests never
  ran. The runner reported the lane as FAIL. Run `npm ci` here and
  re-time it.
- `bb test:changed`, all three lanes in parallel, same selection: wall
  33 s.

  | lane | result                      | time |
  |------|-----------------------------|------|
  | clj  | 66 tests, pass              | 11 s |
  | cljs | FAIL, missing node_modules  | 13 s |
  | cljd | 63 tests, pass              | 33 s |

  Exit 1. Each lane logged to its own file.
- `bb test:changed --changed src/clj/yin/demo.clj`: clj 1 namespace,
  3 tests, pass, 3 s; cljs and cljd shown as skipped.

## Limits (also in build-n-test.md)

The closure misses:
- requiring-resolve, resolve and other dynamic loads;
- fixtures read by a computed path, unless the basename appears
  somewhere;
- programs a test builds or spawns by name without requiring them, for
  example yin.vm.linker.transfer-peer, which cross-host-transfer-test
  compiles by name.

The tree-walker regex only knows `(file-seq (io/file|fs/file "lit"))`.
A change to core namespaces (engine, dao.stream) selects most of the
suite (187 JVM tests for dao.stream.cljc), so `bb test` is the natural
choice there.

## Open for the owner

- A wide change runs nothing (exit 2), as described above. The
  alternative is to run everything in parallel and still exit 2.
- The `.md` fixture exception and the four extra wide files are small
  deviations from the brief.

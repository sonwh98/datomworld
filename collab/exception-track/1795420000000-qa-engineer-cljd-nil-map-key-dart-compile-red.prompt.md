Created-GMT: 2026-11-23 09:3x GMT
Created-Local: 2026-11-23 16:3x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-directed dispatch)
Destination: codex exec -m gpt-6.1-terra on shiva (POOL 1)
Task-ID: 1795420000000-qa-engineer-cljd-nil-map-key-dart-compile-red

# Task: fix the shiva dart-lane compile red — nil-key map literal breaks cljd -map-lit

Role: QA, TDD and Verification Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-23 16:3x +0700 | Status: active | Rationale: implementation lane per routing board; shiva POOL 1 (jupiter POOL 2 exhausted); work is on the shiva dart lane

## Evidence of record

Shiva nightly dart shard at pinned origin/master `e4f2cb89`
(`~/ci/nightly-results/20261123-0015/`, detail
`~/ci/datomworld-nightly-dart/target-ci/dart-shards-20261123-0015/compile.log`):

    compile-all rc=1 secs=425
    === While expanding macro cljd.core/-map-lit:
    class clojure.lang.PersistentVector cannot be cast to class clojure.lang.IPersistentMap ===
    (cljd.core/-map-lit false {nil :nil, 0 :zero})
    ... in (deftest portable-key-and-equality-test ...) at test/yin/vm/data_test.cljc

Attribution: the deftest landed with P0-prime (`7d6923e8`, feat(yang.preludes)). JVM and
Node compile this file fine; ClojureDart's map-literal expansion rejects the literal with a
nil key. Same hazard family as the recorded `#?(:clj)` cljd reader traps
(docs/agents/build-n-test.md, "Scratch-clone first-run gaps" item 3).

## Read first

1. `docs/agents/build-n-test.md` — focus rule, worktree discipline, dart-lane rules
2. `docs/agents/roles/qa-engineer.md` — this role
3. `test/yin/vm/data_test.cljc` — the failing deftest
4. `src/cljc/yin/vm/data.cljc` — `canonical-entries` contract the rows assert

## Mission

Make `test/yin/vm/data_test.cljc` compile on ClojureDart WITHOUT changing what the rows
assert:

- Replace the nil-key map LITERAL (and any other literal in the file with the same hazard)
  with a runtime construction that never passes through `cljd.core/-map-lit` — e.g.
  `(array-map nil :nil 0 :zero)` or `(zipmap [nil 0] [:nil :zero])` — whichever keeps the
  JVM/Node semantics byte-identical (canonical-entries' expected ordering must not change;
  note the expected vector `[[0 :zero] [nil :nil]]` stays as-is).
- File ownership: `test/yin/vm/data_test.cljc` ONLY. No production code, no other files.
- If a second literal or a different cljd expansion error surfaces after this fix, fix it
  the same way (literal-shape only) and record it; if a fix requires changing an asserted
  expectation, STOP — findings only.

## Verification (scoped only, run on shiva in your own worktree)

1. `clojure -M:clojuredart:cljd compile` of the failing ns (or the shard script's
   compile-all over `yin.vm.data-test`) → rc=0, TWICE (record both).
2. Isolated JVM: `clojure -M:test -n yin.vm.data-test` → 0 failures, TWICE in two fresh
   JVMs (prove the literal change didn't move JVM semantics).
3. clj-kondo on the touched file: 0 new errors/warnings.
4. Do NOT run the full dart shard (the master-green seat re-runs the lane); the
   compile-and-focused gate above is your bar.

## Deliverables

Branch `fix/cljd-nil-key-map-literal` pushed from base `origin/master`; findings file
`collab/exception-track/1795420000000-qa-engineer-cljd-nil-map-key-dart-compile-red.codex-gpt-6.1-terra.findings.md`
in the worktree (root cause, every rc/Ran line, head sha). The orchestrator verifies,
re-runs the shiva dart lane, and lands through the countersign protocol. Never merge to
master yourself.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Created-GMT: 2026-11-13 20:1x GMT
Created-Local: 2026-11-14 03:1x +0700
Coding-Agent: glm
Session-ID: b103b608-ccbc-4569-a253-c7625d98614d

# Task: the dart-gate shape fix — convert the battery's JVM-only gates to the established cljd-gate pattern

Role: QA & Verification (mechanical fix round, master-green-routed)

Implementers:
- Model: glm-5.3 | Assigned: 2026-11-14 03:1x +0700 | Status: active | Rationale: probe-verified live; mechanical fix round on a file this lane knows from cc3's r8/r9

## The problem (master-green-attributed, routed to this seat as the file owner)

The nightly dart lane's LAST unknown red at `a3f7ab8d`: `yang.clojure-callsite-test` fails to LOAD on cljd (107–133 failures/shard) because cc3's r3 provenance deftests use `#?(:clj (deftest …))` — ClojureDart's reader honors `:clj`, so the emitted dart test-main calls deftest methods whose bodies use JVM interop and cannot compile. Full problem brief with evidence: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794615500000-problem-dart-gate-shape-callsite-provenance-tests.md` — READ IT FIRST.

## The fix (mechanical, per the brief's prescription)

In `test/yang/clojure_callsite_test.cljc` ONLY: convert every JVM-only deftest from `#?(:clj (deftest …))` to the established `#?(:cljd nil :default (deftest …))` shape — the file itself documents the pattern at ~line 1809 (the `repl-query-link-forwarding-test` gate, landed in `4d0d3e70`). Rules:
- Check each `#?(:clj` occurrence individually (14 in the file at dispatch). Only DEFTESTS whose bodies use JVM interop get converted; non-deftest forms and deftests that are dart-portable stay untouched.
- Do not change any test body, assertion, or name. The gate wraps; nothing else moves.
- The r1-r8 D1 deftests added to this file since cc3 (the correspondence/retention families) are IN SCOPE if they use `#?(:clj` — same conversion.
- The master-green prescription's verification bar: scoped cljd compile of `yang.clojure-callsite-test`, then the JVM side unchanged-evidence.

## Verification (laptop G0)

1. `clojure -M:test -n yang.clojure-callsite-test` — IDENTICAL counts to the pre-fix battery (the gate must not change JVM semantics; master-green's bar: JVM/node unchanged-evidence).
2. The cljd compile: `bb src/dev/cljd_agg.clj --only yang.clojure-callsite-test` (bb is available via `export PATH="$HOME/.local/share/mise/shims:$PATH"`; the worktree's ANTLR is pre-built). If cljd_agg requires a generated file, compile the ns first per the brief. The compile must SUCCEED where it previously failed to load.
3. `clj -M:kondo --lint test/yang/clojure_callsite_test.cljc` — 0 errors.
4. Report the exact per-occurrence disposition (which of the 14 gates converted, which stayed and why).

## Bounds/deliverable

ONE file. No test bodies touched. No other files. Commit NOTHING — the orchestrator commits and pushes the branch for master-green's dart-lane confirmation. Deliverable: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794695000000-engineer-dart-gate-shape-fix.glm-5.3.findings.md` (or worktree mirror at `/Users/sto/workspace/datomworld-dartgate/collab/yang-clojure/` + say-so).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

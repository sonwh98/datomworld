Created-GMT: 2026-11-10 (real header below)
Coding-Agent: agy
Session-ID: beea5490-155c-4532-b46c-7983626568e0

# Task: make yang.clojure-callsite-test cljd-portable — systematic pass, not spot fixes

Role: Compiler/VM Engineer

Implementers:
- Model: claude-opus-5-5-high | Assigned: (header above) | Status: active | Rationale: muse quota exhausted (resets 11-16); agy flat-rate implementation tier
- EXECUTION CONSTRAINT: no ssh, no local slow tests; the orchestrator verifies remotely.

## Context

This is the THIRD cljd portability break in test/yang/clojure_callsite_test.cljc
(1: :cljd Throwable unresolvable — fixed cd050ca2; 2: with-redefs at :1737 —
current failure, nightly 20261111-0405 dart compile:
"Unknown symbol: with-redefs ... yang/clojure_callsite_test.cljc"). The file
grows with each review round and each JVM-first idiom breaks the dart compile
again.

## Your job (systematic, one round)

1. Read the WHOLE file. Enumerate EVERY JVM-only construct: with-redefs,
   Throwable/Exception types in reader conditionals, java.* interop,
   clojure.lang references, System/*, any deftype/defrecord host leans.
2. For each, apply the repo's established idiom (read how yin/vm and repl tests
   do it): reader conditionals with :cljd arms, or host-neutral equivalents, or
   gate the whole deftest block on :cljd if the construct has no dart equivalent
   AND the dart lane legitimately cannot exercise it (the cljd compiler cannot
   run with-redefs-style rebinding — if that is what with-redefs needs, the
   correct gate is #?(:cljd ...) around those specific deftests, keeping the
   rest of the namespace dart-runnable).
3. PREFER gating specific deftests over emptying the namespace: keep as much
   dart coverage as the platform allows.
4. Verify with a LOCAL COMPILE only (bb src/dev/cljd_agg.clj --only
   yang.clojure-callsite-test --no-run — compilation success is the gate; the
   orchestrator runs the tests remotely). Iterate until compile succeeds.
5. No edits outside this one test file. No commits. Report git diff --stat and
   the compile success output.

Begin the final response exactly with Completed-GMT / Completed-Local.

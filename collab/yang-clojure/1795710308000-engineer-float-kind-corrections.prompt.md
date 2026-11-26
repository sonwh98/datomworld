Created-GMT: 2026-11-26 16:25:08 GMT
Created-Local: 2026-11-26 23:25:08 +0700
Coding-Agent: agy
Session-ID: pending (conversation_id)

# Task: fix/float-kind-portability r2 — the sign-off's seven blockers (B-1..B-7)

Role: Compiler Engineer (numeric semantics). You implemented r1 (gemini-4-neon session ba99228a lineage); the architect sign-off audited your work with live execution probes and found six code defects plus one evidence gap. Read the sign-off FIRST: collab/yang-clojure/1795709372000-architect-floatkind-signoff.gpt-6-astra.findings.md — its Required dispositions B-1..B-7 are your exact task list, each with the live counterexample. B-1 is already fixed and committed (93f95f1c, orchestrator-applied: the :require opener restored, namespace verified loading 21/245 green) — start from B-2.

## The work (each blocker: a failing test reproducing the counterexample FIRST, then the fix)

- **B-2** reader.cljc first-xyz truthiness exhaustion: false/nil set elements dropped (#{false 1.0 2} -> #{}). Test sequence presence, not element truthiness; false/nil at all positions + duplicate checking after them, JVM + Node.
- **B-3** numeric.cljc variadic clj-= truthiness: (= 1 1 false) must be false; clj-!= inherits correct negation; variadic source-path tests incl. operands after false/nil.
- **B-4** the ::discard sentinel collides with valid guest keyword :yang.clojure.reader/discard. Use separate parse control state or an unforgeable parse-local mechanism; test all valid literal values remain representable in top-level/collection/required-form positions.
- **B-5** anonymous-fn expansion p__1/p__2/p__rest capture user variables (the 4-vs-12 counterexample). Capture-avoiding, parse-scoped names, deterministic canonical output; positional + rest vs colliding bindings.
- **B-6** compatibility regressions: apostrophe-in-symbol (x') must read; scalar syntax-quote (backtick 1 -> 1) must execute as before. Restore the previously supported grammar; differential lexical tests vs the old behavior. If any narrowing is intentional it needs an explicit architectural disposition — the default is NO narrowing.
- **B-7** gate 5: add the case where an integral float is computed BEFORE the continuation pause, retained across pause/resume (+ snapshot serialization if claimed), then kind/value/bits/canonical identity verified. Keep the existing test as complementary.

## Rules

Same bounds as r1 (RULING 5 file boundaries; B-1 fix 93f95f1c stays). Every blocker: RED test first (record in collab/yang-clojure/<new-ts>-floatkind-evidence/), then fix, then GREEN. Re-run the FULL seven-gate suite + regressions on JVM and Node after all fixes (the sign-off found your regression evidence mixed — a clean full run is required). kondo clean. Then Dart + Jolt legs for the affected namespaces. Update findings; the architect sign-off requires a fresh review of the corrected tip before merge. Commit NOTHING — the orchestrator commits.

Deliverable: collab/yang-clojure/<new-ts>-engineer-float-kind-corrections-r2.gemini-4-neon.findings.md. Do not end the turn with runnable work remaining.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

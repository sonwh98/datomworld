Created-GMT: 2026-10-30 00:05:00 GMT
Created-Local: 2026-10-30 07:05:00 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: hardening-program-2
Role: Compiler & AST Engineer

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-30 07:05:00 +07 | Status: active | Rationale: post-expiry implementation lane; continuation of the hardening program you know (slices 7/8/9 were yours).

Work in /Users/sto/workspace/datomworld-yang-h2 (branch yang-hardening2, base 7dc18478 = the slice-9 BLOCKED gate; the six blockers below are that gate's named dependencies). You may edit ONLY:
- src/cljc/yang/clojure.cljc (the guest library + compiler)
- test/yang/clojure_test.clj
- test/yang/latte_oracle_test.clj
- test/yang/latte_oracle/ (manifest admission dispositions + fixture additions)
- src/dev/latte_oracle_tables.clj (manifest regeneration)
Nothing under src/cljc/yin/. collab/ is never staged.

THE BLOCKED GATE (your baseline): branch yang-slice9 commit 7dc18478 — test/yang/latte_oracle/slice9_gate.md and slice9_evidence.edn record 54 fixtures: 4 pass, 2 mismatches, 48 blocked by named dependencies. THIS PROGRAM closes those blockers. The gate stays red during your work; it turns green ONLY in its own final verification slice, not here.

DELIVERABLES, in dependency order:

1. MANIFEST ADMISSION DISPOSITIONS: the manifest (test/yang/latte_oracle/manifest.edn) records no admitted/excluded dispositions (the gate recorded :not-recorded-in-inherited-manifest). Extend the manifest schema and src/dev/latte_oracle_tables.clj so every binding carries :disposition :admitted | :excluded + :admission-reason, regenerate the table, and make the gate's coverage denominator read it. Admitted = reachable through the guest library + data module + admitted host seams (kind/*); excluded = genuinely nonportable (nano-time, pprint, host interop) with reasons. The gate-report format gains an honest admitted-vs-excluded denominator.

2. MISSING STDLIB ADMISSIONS: the gate's 48 blocked fixtures name missing deps — butlast, last, nth (bare), var? (exposed as the declared kind/ seam), with-meta, string?, and any other repeated closure members from slice9_evidence.edn's dependency graph. Admit each per its nature: guest-library implementation (butlast/last/string? etc.), declared host seam (var? — Clojure Var, admitted explicitly per the slice-7 convention for native closures; with-meta — metadata is plain data, guest-implementable over the established representation). Update the dependency graph so the blocked closures re-admit.

3. GENSYM FRESHNESS: the guest gensym ("G__fresh" repeated) breaks the :minimal-gensym-freshness mismatch. Fix: the explicit fresh-name supply must guarantee uniqueness across calls within one supply — e.g. the supply carries a counter, and gensym constructs G__fresh<N> with an ever-increasing N (thread the supply explicitly per the slice-8 context convention). Fixture: the gate's minimal repro ((let [a (gensym "fresh") b (gensym "fresh")] (= a b))) must now be false on all lanes, matching upstream.

4. FREE-VARS CONTEXT ADAPTATION: the :free-vars-local-capture mismatch is an A/B adaptation bug — upstream free-vars reads local definitions via (second def-env), and the slice-8 context adaptation passes a context map in that position. Fix the adaptation so the verbatim upstream source sees the local-definitions shape it expects (a two-element structure matching upstream's global-defs/local-defs pair), not a redesign of upstream. Fixture: the gate's minimal repro must return #{x} on all lanes.

5. UNFOLD-IMPLICIT TRY PATH: the real typing/unfold-implicit try/catch (with .getMessage, ex-data, exception bindings) stays blocked; slice-5's adapter covered only the adapted copy. Implement the full typed-argument flow: the try site's guest lowering (the outcome model's early-return covers throw; .getMessage/ex-data lower to outcome-model error-data accessors), and the implicit extension fixtures from the gate (guest closure source). If a piece is genuinely nonportable (e.g. a host stack-trace accessor), refuse it loudly and record the refusal in the evidence.

6. OPACITY ATOM -> CONTEXT: proof/+opacity-fn+ is an ambient atom initialized with a closure — the outcome convention refuses that def initializer. Adapt per slice 8's context model: the opacity function becomes a context field installed explicitly (the gate records :blocked-by [gate-proof-+opacity-fn+]); the proof fixtures unblock and run.

Acceptance: the gate's fixture pass count rises materially (report before/after); 0 fixtures silently skipped; every newly-passing fixture has A-vs-C agreement recorded; mismatches remaining after this round are named with minimal repros; regressions green (the four suites + the store-write-audit suite); kondo 0 errors, no new warnings. Commit your round (format.md; no Co-Authored-By). Do not push.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

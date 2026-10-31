Created-GMT: 2026-10-11 11:05:00 GMT
Created-Local: 2026-10-11 18:05:00 Asia/Bangkok

# Task: 5b round 2 -- pin the widened-start rule (fresh codex session)

You are a NEW engineer session (gpt-6.1-sol) with no memory of the prior work. Worktree `/Users/sto/workspace/datomworld-srvm-p5b`, branch `srvm-phase5b`, HEAD b2eadc83. Do NOT `git add`/commit. TEST-ONLY: `src/cljc/yin/vm/semantic_register/derive.cljc` must stay byte-identical (`git diff --quiet HEAD -- src/`). Write each `-n ns` literally; foreground only; DO NOT run slow tests (`-e :slow`). Take a verified byte backup of `derive.cljc` into `collab/` before the mutant and restore from it.

Read finding F1 of `collab/1792000000000-reviewer-srvm-phase5b.claude-fable-5-1.findings.md` (the reviewer said READY_TO_MERGE; F1 is the one MEDIUM test gap). In `test/yin/vm/semantic_register/derive_test.cljc`, `widened-layout-interval` (about line 690) pins a program where widened and un-widened scans give the same image. Add a second case in that deftest (keep the existing one) for `(if c (resume :p 1) (g 2))`: A `[[:var 1 c] [:branch-false 1 5] [:const 2 1] [:resume :p 2] [:jump 8] [:var 3 g] [:const 4 2] [:call 0 3 [4] false] [:halt 0]]`, pinned R image `{:bodies [{:locals 0 :registers 3 :start 0 :end 8}] :instructions [[:load-free 0 c] [:branch-false 0 5] [:const 0 1] [:resume :p 0] [:jump 8] [:load-free 1 g] [:const 2 2] [:call 0 1 [2] false []] [:halt 0]]}`. DERIVE the image yourself from design `docs/design/yin.vm.semantic-register-vm.derivations.md` section 2 first (widened start: `v0:[4,7]` assigned at pc 4 because `L(4)={0}`), report if your derivation differs from the reviewer's probe, and compare with the emitter's output. Fix the test's comment so it states what is actually covered. Then apply the reviewer's mutant M06 (start = first syntactic definition only, no widening over layout `L`) to `derive.cljc`, confirm the new case FAILS with a named test (the mutant should yield `g->T0, 2->T1, v0->T2`), restore byte for byte and verify (`cmp` and `git diff --quiet HEAD -- src/`).

Verify (foreground): `clojure -M:test -e :slow -n yin.vm.semantic-register.derive-test -n yin.vm.debruijn-register-code-test`; kondo and cljstyle on `derive_test.cljc`. Dart and Node: the orchestrator runs them.

Report to `collab/1792020000000-vm-engineer-srvm-phase5b-r2.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
What changed, the derived image and whether it matched, the mutant result with the failing test, exact lane counts.

Created-GMT: 2026-10-01 19:05:00 GMT
Created-Local: 2026-10-02 02:05:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0f8ca-cbed-7e13-b311-026d5e46d112 (captured from thread.started)

# Task: Gate review — Python C2-S1 generators core (yang.python)

Role: Reviewer (independent gate)

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-02 02:05:00 +07 (+0700) | Status: active | Rationale: the standing final gate for language-line landings; fresh thread

READ-ONLY review in /Users/sto/workspace/datomworld-py-c2gen1 (branch yang-python-c2-s1, based on master 1df123d1).
Do not edit any file; do not run test suites. Review the UNCOMMITTED state: `git diff` (4 files: lower.cljc,
prelude.cljc, lower_test.cljc, prelude_parity_test.cljc) plus the untracked test/yang/python/antlr/e2e_c2_test.clj.

Governing sources (same worktree's collab/):
- 1790874900000-architect-python-c2-generators-design.claude-fable-5-1.findings.md (the C2 design; S1 = its
  "S1: core" scope and acceptance table)
- 1790875890000-architect-c2-generators-crossruling.gpt-6-astra.findings.md (binding converged rulings 1-9)

Already-verified evidence you must NOT re-derive (orchestrator-run on this exact tree):
- JVM 2846 tests / 225,852 assertions / 0 failures; clj-kondo 0/0; cljstyle clean on all five touched files.
- glm-5.3 static gate (collab/1790877600000-reviewer-c2-s1-static-gate.glm-5.3.findings.md in the main repo):
  READY, 0 P1/P2, four P3s (all design-deferred scope: py/contains :generator arm, generator print form, the
  node-count-only continuation check, generic py/next message). Its CPython 3.9.6 parity runs byte-matched.
- Node and CLJD lanes are finishing in parallel; the orchestrator holds landing until they are green.

Your gate is the last independent look before landing. Focus where a second reviewer adds most:
1. Continuation semantics: the capture/invoke pairs in gen-switch/yield-raw/gen-exit/gen-fail — any path that
   double-invokes, drops the :generator boundary frame, or resumes into a stale caller-ctx; the already-executing
   ValueError guard; throw-into-never-run closing without capture.
2. Lowering soundness: the :gen binder scoping (set iff the body yields, reset in classes/comprehensions/module),
   the four yield_expr grammar positions all handled, the removed guards leaving exactly the two syntax diagnostics.
3. Cross-host: reader conditionals, host-type leakage, vector/sentinel equality on CLJS/CLJD, the iter-at :generator
   arm ignoring the index.
4. Test honesty: any vacuous or weakened assertion in the new/changed tests; the lower_test.clj change from
   "def g(): yield 1 unsupported" to "yield from unsupported" must be the only weakened row.
5. Anything the glm gate structurally could not see (it ran no suites; you may read its findings file for its P3s).

Verdict: READY (sign-off granted) or REQUEST CHANGES with severity-tagged findings (P1 blocking, P2 should-fix,
P3 notes), each with file:line and evidence. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Created-GMT: 2026-10-01 19:25:00 GMT
Created-Local: 2026-10-02 02:25:00 +07 (+0700)
Coding-Agent: claude
Session-ID: bfaf5e35-70a5-4e4b-9686-bf5717d46bd8 (resumed; slice 1 fix round 1)

# Task: Safepoint slice 1 fix round 1 — architect ruling: KeyboardInterrupt placement

Role: Yang Compiler and Universal AST Engineer

The Architect (fable, your design's author) reviewed the lazy-factory decision and REJECTED it. Full ruling:
/Users/sto/workspace/datomworld/collab/1790879200000-architect-safepoint-kinterrupt-ruling.claude-fable-5-1.findings.md
— read it whole. Summary (the ruling text is binding):
- The factory breaks naive correctness (a program reading KeyboardInterrupt fails closed even when it defines its
  own), shadowing (the thunk evaluates before the module dict is consulted), and canonical-program closure (the
  naive lowering now requires the hook namespace).
- FIX: KeyboardInterrupt becomes a plain entry in the base prelude's builtin-classes under BaseException
  (prelude.cljc:1478-1494 table), read through builtin-names like every other builtin. Delete builtin-thunks, the
  factory cell py.b/KeyboardInterrupt, the py.sp/keyboard-interrupt cell, and the safepoint require from
  lower.cljc. py.sp/deliver raises (py/call py.b/KeyboardInterrupt []) from inside a function body, so the
  reference resolves at call time after the bundled base prelude has loaded — your load-order constraint still
  holds without any factory.
- The same rule is recorded for RecursionError, SystemExit, GeneratorExit later slices: class in the base prelude,
  raising mechanism wherever it belongs.
REGRESSION TESTS (from the ruling; implement all):
1. Naive builtin: try: raise KeyboardInterrupt / except KeyboardInterrupt: print('k') prints on all four VMs with
   no hook prelude; except Exception does not catch it.
2. Shadowing: KeyboardInterrupt = 1; print(KeyboardInterrupt) prints 1, naive and derived.
3. Closure: for the e2e corpus, canonical free names are a subset of base-prelude definitions, host names and
   primitives; no py.sp/ symbol appears. This INVERTS the second half of fail-closed-test (safepoint_test.cljc
   around :422-426) which currently pins the factory.
4. Hook namespace: every key the hook prelude defines is in py.sp.
5. Transparency: add a program that reads KeyboardInterrupt to the every-vm= corpus (runs naive and under
   noop-uast).
6. Identity: keep the existing delivered-signal tests (e2e_test.clj:525-553).
7. Load order: the hook prelude alone loads on a VM that has only the signal stream and no base prelude.
Do not touch anything else. Do not weaken tests.

MECHANICS: the worktree's mise config is trusted and node_modules are installed (npm install already run). Run
lanes in the FOREGROUND, one simple command per step, waiting for each: mise exec -- bb build:yin-repl-node, then
mise exec -- bb test:clj, bb test:cljs, bb test:cljd; clj -M:kondo and cljstyle check on touched files. Report
exact counts per lane.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

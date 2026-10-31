Created-GMT: 2026-10-08 22:12:00 GMT
Created-Local: 2026-10-09 05:12:00 Asia/Ho_Chi_Minh
Coding-Agent: agy (Lead Orchestrator)

# Task: Track A Phase C4 Slice P3 Completion & Verification
Role: Yang Compiler and Universal AST Engineer (Claude Sonnet 5.5)

You are taking over and completing Track A Phase C4 Slice P3 in `/Users/sto/workspace/datomworld-p3` (branch `yang-python-c4-p3`).

## Context & State
- Predecessor implementation and draft findings: `collab/1791489508540-compiler-engineer-c4-p3.glm-5-3.findings.md`
- Original task brief: `collab/1791489508540-compiler-engineer-c4-p3.prompt.md`
- Architecture & Plan: `docs/design/yang.antlr.md` (§8.5.6 Imports, linked prelude, frontend catalog; §8.11 Safepoints)
- Code changes already present on disk:
  * `src/cljc/yang/python/antlr/prelude.cljc`: `pysp` module spec and emitter
  * `src/cljc/yang/python/antlr/safepoint.cljc`: linked hooks `pysp/loop`, `pysp/call`, `pysp/return`
  * `test/yang/python/antlr/linked_harness.cljc`: publishes and serves `pysp`
  * `test/yang/python/antlr/linked_safepoint_test.cljc`: acceptance tests across VMs
  * `test/yang/python/antlr/safepoint_programs.cljc`
  * Golden moves in `float_address_test.cljc` and `linked_prelude_test.cljc`

## Your Task
1. Inspect the 1 observed test failure in `yang.python.antlr.e2e-test`:
   `FAIL in (portable-packets-are-the-parsers-test) (e2e_test.clj:891)`:
   Check `#'yang.python.antlr.safepoint-programs/def-and-while` docstring / AST comparison and fix any discrepancy so the parser parity test passes.
2. Run focused verification suites:
   `clojure -M:test -n yang.python.antlr.safepoint-test -n yang.python.antlr.linked-safepoint-test -n yang.python.antlr.float-address-test -n yang.python.antlr.prelude-parity-test -n yang.python.antlr.linked-prelude-test -n yang.python.antlr.e2e-test -n yang.python.antlr.c3-gate-test -n yang.safepoint-test`
3. Verify linting and formatting:
   `clj -M:kondo --lint src/cljc test`
   `git diff --check`
4. Document the completed deliverable in:
   `collab/1791495000000-compiler-engineer-c4-p3.sonnet.findings.md`
   Begin with:
   Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
   Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone>
   Coding-Agent: Claude (claude-sonnet-5-5)

You are an independent Architect and Reviewer for datom.world.
Your role: Architect / Review Gate (docs/agents/roles/architect.md).

Task under review:
Python Safepoint Slice 1 (yang.antlr.md §8.5.2: lowering site marks, yang.safepoint stage, hook prelude, multi-host verification).
Worktree: `/Users/sto/workspace/datomworld-py-safepoint1`
Branch: `yang-python-safepoint-s1`

READ-ONLY review in `/Users/sto/workspace/datomworld-py-safepoint1`.
Do not edit any file; do not run test suites.

Context & References:
- Design: `docs/design/yang.antlr.md` §8.5.2 (Safepoints and Tracing, Slice 1)
- Architectural ruling: `collab/1790879200000-architect-safepoint-kinterrupt-ruling.claude-fable-5-1.findings.md`
- Lowering implementation: `src/cljc/yang/python/antlr/lower.cljc`
- Prelude implementation: `src/cljc/yang/python/antlr/prelude.cljc`
- Universal safepoint stage: `src/cljc/yang/safepoint.cljc`
- Universal stage abstraction: `src/cljc/yang/stage.cljc`
- Python safepoint hooks: `src/cljc/yang/python/antlr/safepoint.cljc`
- Tests: `test/yang/python/antlr/safepoint_test.cljc`, `test/yang/safepoint_test.cljc`

Already-verified evidence you must NOT re-derive (orchestrator-run on this exact tree):
- Tri-host verification:
  * JVM: 2,864 tests / 226,519 assertions / 0 failures, 0 errors
  * Node: 2,679 tests / 91,582 assertions / 0 failures, 0 errors
  * Dart: 2,634 tests / 0 failures / All tests passed
- Linter & Style:
  * clj-kondo: 0 errors, 0 warnings
  * cljstyle: clean
  * Pure ASCII, max 80 columns, no em dashes

Review Checklist & Invariants:
1. Architectural integrity:
   - Does `yang.safepoint` stage derive `A'` from `A` without touching or mutating `A`?
   - Are site markers recorded in the side table accurately during lowering?
   - Does KeyboardInterrupt follow the ruling (plain builtin-classes entry in prelude)?
   - Are safe integer bound literals in `prelude.cljc` strictly within JS safe integers (preserving CBOR encoding rules)?
   - Does tail preservation hold (no continuation accumulation across long loops)?
2. Invariants & Rules:
   - Zero-warning, zero-lint policy: kondo 0/0, cljstyle clean.
   - Pure ASCII, max 80 columns, no em dashes.
   - Verification across all 3 hosts (JVM, Node, Dart).

Provide your review findings.
Conclude with an unambiguous judgment:
- **READY — Sign-off granted** (if all P1/P2 criteria pass)
or
- **REQUEST CHANGES** (listing specific blocking P1/P2 items)

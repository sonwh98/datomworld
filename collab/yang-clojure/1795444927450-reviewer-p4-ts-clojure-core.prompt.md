Created-GMT: 2026-11-22 19:42:07 GMT
Created-Local: 2026-11-23 02:42:07 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 0e1a01f2-ffe7-428a-b1a3-3ec59f0616c7

# Task: Step-7 review — yang.preludes P4′-a (ts descriptor + frontend contract + composition-swap fixture)

Role: Reviewer (independent, read-only)

Implementers:
- Model: glm-5.4 (fallback glm-5.3) | Assigned: 2026-11-23 02:42 +07 | Status: active | Rationale: independent family from the codex/gpt-6.2-sol implementer; glm row: multi-file Clojure + invariant review

## What to review

The UNCOMMITTED working-tree diff in `/Users/sto/workspace/dw-p4ts` (branch `feat/p4-ts-clojure-core`, base master `0c36043d`). Review the real diff (`git diff`; new files include the ts profiles/contract/clj.ts adapter namespaces and `test/yang/typescript/antlr/descriptor_profile_test.cljc` — read new files whole). Do NOT modify anything.

- Governing design: `/Users/sto/workspace/dw-p4ts/docs/design/yang.preludes.md` r8 — §6.3 (schema), §6.4 (validation/hygiene/authority, item 6), §6.5 (default selector), §9 row "P4′".
- Engineer brief: `/Users/sto/workspace/datomworld/collab/yang-clojure/1795440552499-engineer-p4-ts-clojure-core-and-swap-fixture.prompt.md`.
- Template: the landed P3′ python implementation (contract/profiles/clj_runtime/descriptor tests in `src/cljc/yang/python/antlr/`).

## Already verified by the orchestrator (do not re-run suites)

descriptor+frontend 9/255/0; native lowering parity 736/736 identical UASTs-or-refusals vs base; deps clean. Engineer-captured: Node 175 assertions, Dart 4 tests, Jolt probe, swap fixture 13 assertions JVM+Node. Known pre-existing (NOT the ts diff): 3 py-v1 pin errors in yang.python.antlr.descriptor-profile-test reproduce at clean base 0c36043d (orchestrator-verified) — inherited from the python seat's master landings.

## Review focus (prioritized)

1. **§6.4 authority in the ts path:** descriptor validation reused (not forked) from `yang.core.semantics`; the ts contract's Hook ABI table with arities + per-hook ROLE records; address-bound role key — verify a same-arity wrong-role target fails closed AND that a test exercises the failure path.
2. **Legacy `ts-v1` behavior preservation:** the descriptor pins today's ts/js hooks 1:1; native entry `js/run-main [body]` (1-operand) FROZEN; native lowering parity is the evidence — scrutinize the `yang.typescript.antlr.lower` hunks for anything beyond descriptor reads (changed emission order, moved refusals, changed diagnostics).
3. **The clojure-core-v1 ts profile:** `clj.ts/run-main [body-fn ambient-resolver]`; adapter module with zero provenance doors; boxed float64 for `Number` literals, exact integers for `BigInt`; capability refusals fail closed BEFORE emission; ts-idiomatic projection aliases legal per the grammar.
4. **The DEFAULT flip for ts:** default selector → clojure-core-v1; `ts-v1` explicitly pinnable; in-flight snapshots retained; bare compiler calls retain the native pin (the oracles' path) — verify the selector change doesn't capture the oracle path.
5. **The composition-swap fixture (owner directive):** the swap (`identity` redirected to `clj.py/ex-info`) really executes end-to-end in both modes with provenance naming the swapped addresses; the negative path (missing role grant) fails closed; ZERO frontend changes involved — confirm no frontend/lowering file changes are needed for the swap.
6. **Fences + portability:** no `yang/php/` or py prelude-module changes; no JVM-only reflection; canonical ordering; zero raw host doubles; `data/nth` discipline in new vector reads.

## Deliverable

Write findings to `/Users/sto/workspace/dw-p4ts/collab/yang-clojure/1795444927450-reviewer-p4-ts-clojure-core.glm.findings.md` with the artifact header (Created-GMT/Created-Local/Coding-Agent/Session-ID/Task/Role/Implementers). Verdict per finding CRITICAL/HIGH/MEDIUM/LOW/NOTE with file:line evidence, then ONE overall verdict line: `VERDICT: APPROVED` / `VERDICT: APPROVED-WITH-FIXES` / `VERDICT: CHANGES-REQUIRED`. End your final message with the same verdict.

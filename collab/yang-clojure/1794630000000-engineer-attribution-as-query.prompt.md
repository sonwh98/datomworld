Created-GMT: 2026-11-05 04:00:00 GMT
Created-Local: 2026-11-05 11:00:00 Asia/Ho_Chi_Minh
Coding-Agent: glm (glm-5.3) — the lane the owner chose for this unit's arc (r4/r5)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: implement the SIGNED attribution-as-query ruling — caller-side site tagging, derived site tables, the seven-case battery, the honest migration

Role: compiler/harness engineer (glm). THE SPEC is the signed ruling-of-record: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794600000000-architect-attribution-as-query-ruling-of-record.md` — read it FIRST, implement exactly its rules R1-R7 and its implementation ordering. A fresh codex gate reviews against the ruling before landing. You have shell.

## Context

Worktree /Users/sto/workspace/datomworld-aaq, branch attribution-as-query, base master 3eebe844e (the ruling landed; D2, cell/authentic?, H3-2, H3-3 all in). The governing reviews (astra/qwen/kimi) are linked from the ruling's header — kimi's v3 review found the mechanism flaw your implementation must respect: **site ids are CALLER-SIDE, at application expressions in the compiled AST, post-macroexpansion; the callee-entry record carries the CALLER's site id.** Callee-side tagging (v3's flaw) yields identical records for both origins and is forbidden.

## The implementation, in the ruling's stated order

1. **FIRST DELIVERABLE — the written inventory confirmation**: what the instrumentation change touches (kernel-trace-form's position, the walker's application-node stamping site, the callee-record extension), the capacity measurement (stream/make + the instruction bound vs the added trace field — R6), and the offset-table coverage list (every context-API arity kernel-context-extra-arguments served, latte_oracle_test.clj:1786). Write it BEFORE code; commit it as the round's first commit.
2. **Caller-side site-id stamping**: application expressions in the compiled AST (post-macroexpansion — the corpus runs under is/testing/LaTTe macros; source-level tagging misses expanded sites) get compile-time-unique site ids; the callee-entry record becomes `[fname arity caller-site-id]`. The walker changes are AUTHORIZED by the ruling (R2) — scoped to application-node stamping + the record extension; nothing else in yin.vm moves.
3. **Mechanically derived site tables**: the compiler derives each site's argument shape (ambient literal `empty-env` vs anything else) during instrumentation — the tables are DERIVED, not hand-declared (R7's trust model), recorded in a stable versioned evidence format (R1's condition b), with the canonical-source-hash per fixture (R2b case 3's referent).
4. **The SEVEN-case battery + the stale-table diagnostic** (R2b): each case a real test; the three new ones (5 redefined as compile-time AST verification; 7 variable-argument default-refusal; 8 the stale-table diagnostic) written from the ruling's text.
5. **The accounting rewrite + the honest pin migration** (R5/R6): kernel-executed-arity? to the 3-vector shape; the offset table retired with its coverage list stated; every 2-vector pin migrated — the mechanical updates AND the semantic /1 re-credit listed in the evidence per R5.
6. **The attribution + re-credit**: F2's ambient-shape execution credits registered-definition?/1 through the ruled query; the evidence regenerates; the withdrawn-witness report if any.
7. **H3-6 prep**: the attribution query's shape, stated for the audit (the audit itself is a separate round).

## Verification (test-boundary ruling: focused local, suite-scale remote)

- Local (individual): the seven-case battery, the migrated pins, the re-credit, kondo on every touched file. Real outputs.
- REMOTE (list exact commands for the seat): the full oracle namespace (expect the by-design assertion to PASS FOR THE FIRST TIME — 165/165), yang.clojure-test, yang.base, the capacity restatement's confirmation.
- If 165/165 does NOT hold on the remote run: STOP, report the withdrawn witnesses per R5 — do not force credit.

## Boundaries

- yin.vm: ONLY the application-node stamping + the callee-record extension (R2's authorization). No other VM changes. yang.clojure: untouched unless the stamping requires it (justify). The harness (latte_oracle_test.clj, context.edn, slice9_gate.md's versioned attribution section) is the main surface. The pin migrations are authorized by R5 — list every revised pin in the evidence.
- Commit on the branch (inventory commit first); push (gh HTTPS if ssh unavailable); do NOT merge.

## Report

The inventory (deliverable 1) in full; the diff per file; every local test's real output; the remote command list; any deviation from the ruling with its reason.

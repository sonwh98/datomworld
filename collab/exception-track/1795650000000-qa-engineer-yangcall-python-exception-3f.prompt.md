Created-GMT: 2026-11-25 03:3x GMT
Created-Local: 2026-11-25 10:3x +0700
Dispatched-By: master-green orchestrator seat (interactive; provisional-row attribution)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, credits reset)
Task-ID: 1795650000000-qa-engineer-yangcall-python-exception-3f

# Task: attribute the yang.call.python-exception-test 3F (isolated slow namespace)

Role: QA, TDD and Verification Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-25 10:3x +0700 | Status: active | Rationale: the rows live on the JVM slow tier; debug on jupiter where they ran

## Evidence of record

Head-nightly slow tier at `0d378482` (jupiter): `slow-yang.call.python-exception-test.log`
→ Ran (see log) with **3 failures, 0 errors**. The namespace is in the restored slow set
(the discovery fix). The Assignment B amendment (landed `a95c4cfd`) did NOT absorb these
rows — verified red at the countersign on the union tree, isolated:
`DATOM_SLOW_TESTS=1 clojure -M:test -n yang.call.python-exception-test` → 10 tests /
127 assertions / 3 failures (see `/tmp/vfy-b-r2.out` on jupiter for the full log with
expected/actual pairs).

## Mission

1. Reproduce isolated in your own worktree at `origin/master` (current head, after
   `bb gen:antlr`): `DATOM_SLOW_TESTS=1 clojure -M:test -n yang.call.python-exception-test`.
   Record rc/Ran and each failing assertion verbatim.
2. Classify each of the 3 rows:
   (a) stale golden/expectation vs the current contract (cite the design doc lines),
   (b) pin drift (another missed re-mint — note: ALL known py pins were re-minted in
   `a95c4cfd`; a new address here would be a NEW pin surface — report exactly which),
   (c) real behavioral defect (production surface — STOP, findings only),
   (d) environment/order dependence (passes in subsystem order or with different
   setup — prove it with the paired run).
3. Fix authority: (a) only, and only within `test/yang/call/*` — no production files,
   no yang.php/yang.python surfaces. (b)/(c)/(d beyond proof) → STOP, findings only.
4. Non-weakening: cold-policy structure and all passing rows unchanged.

## Verification (scoped only)

1. Isolated namespace: 0 failures TWICE in fresh JVMs after your fix (or unchanged red +
   full attribution if STOP).
2. One adjacent scoped ns green (unchanged evidence).
3. clj-kondo clean on touched files.

## Deliverables

Branch `fix/yangcall-python-exception` pushed; findings
`collab/exception-track/1795650000000-qa-engineer-yangcall-python-exception-3f.codex-gpt-6.1-terra.findings.md`
(per-row table with classification + citations, every rc/Ran line, head sha). Orchestrator
countersigns and lands, or routes per findings. Never merge to master yourself.

Begin the final response exactly with:
Completed-GMT / Completed-Local lines.

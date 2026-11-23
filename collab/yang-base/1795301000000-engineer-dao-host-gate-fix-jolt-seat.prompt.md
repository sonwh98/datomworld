Created-GMT: 2026-11-22 08:04:15 GMT
Session-ID: pending (capture thread.started.thread_id)
# Task: fix dao.host.gate-test on master — regenerate the H0 freeze closure (relayed from the master-green seat)

Role: QA & Verification Engineer (implementation)
Implementers:
- Model: gpt-6.2-sol | Assigned: see Created-Local

## The governing brief (READ FIRST — it is complete)

/Users/sto/workspace/datomworld/collab/yang-base/1795300000000-brief-dao-host-gate-test-red-to-jolt-seat.md
— the master-green seat's evidence of record (29 failures at origin/master
472d8ac2+, three signatures), the root-cause shape to verify, the file
ownership, the verification bar, and the H3 precedent (d454378b, fdeb9370).
Follow it exactly.

## Work dir

Worktree off origin/master: git worktree add
/Users/sto/workspace/datomworld/.claude/worktrees/dao-host-gate-fix -b
fix/dao-host-gate-closure origin/master. Commit there; never push (the
master-green seat verifies and lands via countersign).

## File ownership (strict)

docs/design/host-boundary/closure-{clj,cljs,cljd}.edn,
test/dao/host/gate_test.clj, and the §3.1 prose totals in
docs/design/dao.stream.host-boundary.md. NOTHING else; no production code.

## Mission (per the brief)

1. Regenerate/record the closure per the H3 precedent — the generator per
   lane (clj/cljs/cljd), TWO-RUN determinism check per file (the closure must
   regenerate identically twice before it is recorded).
2. Reconcile the injected-arm test with the §8.1 :deferred-stage semantics —
   the cst.cljc closure-exemption is landed CONTRACT (astra's ruling); if the
   two other newly-flagged files (yang/core/lower.cljc, yang/php/antlr/cst.cljc
   — verify which from the DRY landing 1604aabe) are legitimate §8.1-adjacent
   surfaces, they go on the correct list WITH THEIR REASON; the gate's
   diagnostic contract stays intact. Never weaken a test to pass.
3. Census totals recorded honestly (161→160 observed movement; verify the
   actual number and what moved).

## Verification bar (per the 2026-11-07 merge bar — run these exactly)

- `clojure -M:test -n dao.host.gate-test` → 14 tests / 0 failures, TWICE in
  two fresh JVMs.
- `bb test:sub:clj dao.stream` green (the foundation precondition).

## Deliverables

Findings:
/Users/sto/workspace/datomworld/collab/yang-base/1795301000000-engineer-dao-host-gate-fix.gpt-6.2-sol.findings.md
(standard header; the verified root cause per signature; what moved and why;
the determinism evidence; the two fresh-JVM green runs verbatim; ends DONE).
Final response: Completed-* header + per-signature status + the verification
line.

## Rules

Foreground + explicit timeouts; G0 discipline (the gate namespace is the
failing test — fix against it alone; dao.stream subsystem check is the
precondition, everything else stays untouched). If the root-cause shape in
the brief does not hold (e.g. a landing the brief didn't name is the real
cause), record the correction with evidence — do not force the brief's story.
No suite lanes.

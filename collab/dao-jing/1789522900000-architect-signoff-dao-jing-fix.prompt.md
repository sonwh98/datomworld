Created-GMT: 2026-09-15 21:22:00 GMT
Created-Local: 2026-09-16 04:22:00 +07 (Asia/Ho_Chi_Minh)

# Task: Architect sign-off — dao.jing canonical encoder P0 fix

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 04:22:00 +07 | Status: active | Rationale: Architect sign-off gate, required before commit per user-granted overnight commit authorization

Session-ID: 7216a6f9-10f5-4468-8198-f9009b55e6ad

Perform a read-only architecture review of the uncommitted working-tree
diff to `src/cljc/dao/jing.cljc` and `test/dao/jing_test.cljc` (run `git
diff -- src/cljc/dao/jing.cljc test/dao/jing_test.cljc`).

## Context

This closes three P0 content-addressing defects in DaoJing's transitional
canonical encoder (metadata never printed, a set-tag collision with real
data, records silently colliding with equal plain maps) that an earlier
attempt left open (`collab/1789502626000-claude-review.findings.md`,
"Fix 1"). Implemented by glm-5.3
(`collab/1789513569000-storage-jing-canonical-fix.glm-5.3.findings.md`).

Independently verified by the orchestrator: `clojure -M:test -n
dao.jing-test` (38 tests / 226 assertions / 0 failures), plus the two
real `materialize!` consumers `dao.space.index-test` (55/512/0) and
`dao.data.btree-durability-test` (21/768/0), all clean.

Adversarially reviewed by deepseek-v4-pro, cross-family from the
implementer
(`collab/1789522420000-review-dao-jing-fix.deepseek-v4-pro.findings.md`).
That review's Finding 1 (a claimed crash on non-map metadata) was
disproved by the orchestrator — `(with-meta [1 2] :kw)` throws from
Clojure's own `with-meta` before dao.jing's code is ever reached, and no
legal Clojure value can carry non-map metadata (reader shorthand like
`^:kw` always normalizes to a map at read time). The review's other
findings (a symbol literally named `"#{1 2}"` collides with a set of the
same print; byte arrays hash by identity not content; scalar metadata is
silently dropped) are confirmed real but are pre-existing residuals
outside this unit's three named P0s, already disclosed in
`docs/design/dao.jing.md`'s Open Items as deferred to the pinned
canonical byte encoding, or, for byte arrays, a genuine but pre-existing
doc/impl gap untouched by this diff.

## Read first

- `docs/design/dao.jing.md` (the full file — Canonical encoding section
  especially)
- `docs/design/datom.world.md`
- `src/cljc/dao/jing.cljc` (the current full file, not just the diff)
- `collab/1789502626000-claude-review.findings.md` (the original P0
  specification)
- `collab/1789513569000-storage-jing-canonical-fix.glm-5.3.findings.md`
- `collab/1789522420000-review-dao-jing-fix.deepseek-v4-pro.findings.md`

## Evaluate

Foundational invariants, ownership boundaries, explicit state and control
flow, concurrency and linearization, dynamic extension, host isolation,
CLJ/CLJS/CLJD portability, migration risk, completion criteria, and
design contradictions — per your role definition. Specifically:

- Does the fix correctly implement what `dao.jing.md`'s Canonical
  encoding section requires ("equal supported values produce the same
  bytes on every platform... distinct values must address distinctly,
  both across types and within one type")?
- Is rejecting records (rather than silently addressing them as maps) an
  architecturally sound decision, or should it have been handled
  differently? GLM's findings explicitly flag this as open for your
  sign-off.
- Does `canonical-print`'s hand-rolled printer (replacing host `pr-str`
  delegation for collections) introduce any host-isolation risk — does it
  correctly avoid depending on any single host's printer quirks, given it
  was written specifically because ClojureDart's sorted-set printer drops
  metadata?
- Is leaving the byte-array identity-hashing gap and the pathological-
  symbol residual out of THIS commit's scope (deferred to future pinned
  canonical encoding work, per `dao.jing.md`'s own Open Items list) the
  right boundary, or does either rise to a blocker?

## Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended
correction. Also confirm the requested properties that passed review, and
end with an explicit sign-off verdict: APPROVE, APPROVE-WITH-FINDINGS
(nonblocking), or BLOCKED (name the blocking finding).

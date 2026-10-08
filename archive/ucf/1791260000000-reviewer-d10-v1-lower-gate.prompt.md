Created-GMT: 2026-10-06 23:15:00 GMT
Created-Local: 2026-10-07 06:15:00 +0700
Coding-Agent: agy (gemini-3.1-pro-high, plan review)

# Task: gate review, M-next D10 — the version-1 lower (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d10
(branch ucf-d10-v1-lower, based on master af5dc88e). The engineer's
report: /Users/sto/workspace/datomworld-d10/collab/1791253000000
-compiler-engineer-ucf-d10-v1-lower.findings.md. Read it first, then
`git -C /Users/sto/workspace/datomworld-d10 diff` (handoff.cljc and
handoff_v1_test.cljc).

The governing contract is the architect's inputs ruling (staged at
/Users/sto/workspace/datomworld-d10/collab/1791240000000-architect
-d10-lower-inputs-ruling.gpt-6-astra.findings.md) plus the D plan r3
(1791194000000-...r3...findings.md, sections 1.1/1.6/1.7 and the D10
test contract). In brief: after the D7 validation pipeline, a
blocked/parked version-1 root requires explicit grant evidence (the
opts :address, :protection, :grant with the seven consistency
checks); missing evidence is :yin.k/awaiting-grant with no :vm and no
attachment calls; :yin.k/not-holder on contradictory binding or
tenure; :yin.k/unsatisfied on unavailable evidence or protection
mismatch; the counter restores exactly from the body; carried ids
persist on the three retained variants; the root gets
:yin.k/custody with gate :running; children get the gate only; a
halted v1 root restores gated :ended with no grant; the version-0
path is byte-frozen.

## What to attack

1. The seven grant-consistency checks are each implemented and
   tested (checkpoint address, occurrence, lease/holder,
   arbitration transaction identity, portable exact epoch, live
   tenure n < b, dense prefix from zero through frontier - 1).
2. No path returns a runnable machine without grant evidence, and
   every refusal makes zero attachment calls (the tests assert the
   counters).
3. The protection declaration: exactly the three classes; missing
   declaration for any reachable identity (children included)
   refuses; :enrolled agrees with the evidence's enrolled set in
   both directions; enrolled-without-id, id-on-unenrolled,
   enrolled-with-id-preserved, non-enrolled-without-id-preserved.
4. The custody map's contents match the ruling's shape; children
   carry the gate only; halted v1 roots restore :ended with no
   grant and no custody.
5. Receiver isolation: guest stores, module stores, parked records,
   installs and guest module-registry entries cannot supply
   checkpoint state; host module entries and composition resources
   remain available (14.2.4 row 8).
6. The version-0 path is byte-frozen: the v0 fork tests pass
   unchanged, and the D9 restoration fixtures now supply explicit
   grants without losing their assertions.
7. The engineer's disclosed limitations: four-kernel lower coverage
   (the pre-existing :unaddressed-segment lift refusal on
   walker/stack/register — pinned as a kernel-boundary test), the
   next-assigned-id left to D11, and D13's duties. Are these
   correctly scoped, or is anything a defect of this slice?
8. Portability and style of the changed files (the engineer ran
   kondo 0/0 and cljstyle clean; Node and Dart focused runs are
   reported).

Verdict first: READY or NOT READY (with what must change), then
numbered findings with file:line evidence. Read-only: edit nothing,
run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Created-GMT: 2026-10-05 14:50:00 GMT
Created-Local: 2026-10-05 21:50:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D7 — the version-aware reader and the version-1 grammar (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the work in /Users/sto/workspace/datomworld-d7 (branch
ucf-d7-v1-reader, based on master 52a0383b with D1 to D6 landed; the
work is uncommitted in the working tree). The engineer's report:
/Users/sto/workspace/datomworld-d7/collab/1791208000000-compiler
-engineer-ucf-d7-v1-reader.findings.md. Read it first, then
`git -C /Users/sto/workspace/datomworld-d7 diff` (handoff.cljc
+177/-32, new test file handoff_v1_test.cljc).

The contract: D plan r3 section 1.7 as amended by astra's residual 3
(1791192700000-architect-m-next-d-plan-r2-confirm.gpt-6-astra
.findings.md) — two decoding paths (dao.jing.cbor for version 1,
dao.stream.cbor tag-39 for version 0), retain which codec accepted,
enforce that codec's body-version contract before address and
restoration checks; neither decodes = `:yin.k/undecodable`; a v1
structural failure never reinterprets as v0. Plus r3's D7 row: the
version gate precedes the address check; `checkpoint/inspect` for v1
only; full restoration validation after; version 0 keeps its fork
semantics and lowers as today; `handoff-version` becomes `#{0 1}`;
the v0 wire frozen.

## What to attack

1. The two-codec dispatch is deterministic on real bytes, not on
   trial-and-error semantics: the engineer probed that v0 stream bytes
   always carry tag 39 (jing refuses) and jing bytes tag 27 (the
   stream codec refuses). Verify those claims against
   src/cljc/dao/stream/cbor.cljc and src/cljc/dao/jing/cbor.cljc
   (jing's decoder refusing tag 39), and against the v0 lower path's
   decode — could any real v0 body decode under BOTH codecs, and if
   so, what would the pipeline do?
2. Order: tag check, then the codec's version contract
   (`:stream #{0} :jing #{1}`) on jing's integer kind, then the
   address check (`checkpoint/inspect`) for v1 — a float `1.0`
   version, an absent version, a version-2, a version-0 child in a
   version-1 root (`:yin.k/undecodable`), unsupported-version-wins-
   over-a-wrong-address.
3. The v0 wire frozen: `emitted-version` stays 0; the v0 lower path is
   unchanged; a v0 body still lowers as a fork and is refused when
   `{:exclusive true}`; the stage-1 handoff_test suite is untouched
   and green.
4. The clause-5 grammar added to validate-body: install phase in
   `#{:running :parked}` on both versions, phase and parent required
   at v1, children re-encode codec-aware and skip the custody step
   (ids checked in the root's context). Is skipping the custody step
   for children right, given the inspector's recursion into install
   children with the root's context (C4)?
5. The fixtures: real exports raised to version 1; refusals assert
   zero attach calls and no machine; portability of the new test
   file (:cljd-first, no float traps, canonical bytes not host
   numbers).
6. Scope: only the two named files; kondo 0/0 and cljstyle clean (the
   orchestrator ran them); the engineer's own honest notes (kondo was
   sandbox-blocked for them; the scoped wide lane 2967/228176/0).

Verdict first: READY or NOT READY (with what must change), then
numbered findings with file:line evidence. Read-only: edit nothing,
run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

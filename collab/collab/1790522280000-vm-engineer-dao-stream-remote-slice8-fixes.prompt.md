Created-GMT: 2026-09-27 19:30:00 GMT
Created-Local: 2026-09-28 02:30:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 8 fixes)

# Task: Slice 8 — Fix the gate's three P1 findings

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 8 is
uncommitted). The gate returned REQUEST CHANGES with three P1s: the UCF
facade is a shallow round-trip not wired to the real parked-state
machinery. Read:
collab/1790522276328-architect-dao-stream-remote-slice8-gate.gpt-6-sol.findings.md
Also read: src/cljc/yin/vm/semantic.cljc (:106 the parked wait shape),
src/cljc/yin/vm/engine.cljc (:522 the wait-set/parked entries),
docs/design/yin.vm.universal-continuation-format.md 7.4.3, 7.5.3, and
the 7.11 pending-state blocker.

1. P1 (remote.cljc:119): the facade accepts invented
   {:handle ... :position ...} entries. Fix: lift from ACTUAL parked
   entries -- the engine's wait-set/parked shape uses :stream-id,
   :cursor-ref, and private :resources (semantic.cljc:106,
   engine.cljc:522). The lift reads real wait-set entries; the lower
   creates fresh resource entries and wait-set entries preserving the
   VM context. Tests: park a REAL machine via the engine (a :stream-next
   wait), lift it, lower it into a fresh engine state, and prove the
   wait resumes equivalently (the acceptance row).
2. P1 (remote.cljc:144): lift declares :dao.stream.remote/v1 without
   checking the cursor is portable through the codec. Fix: verify the
   kept cursor survives a codec round trip before publishing UCF data
   (the profile claim made real). Test: a cursor that cannot round
   trip is refused, not published.
3. P1 (remote.cljc:57): every lift uses :yin.k/c-0 in a separate
   table; no frame-level assembly or lowering preserves two waiters
   sharing one cursor cell (7.5.3 and the 7.11 pending-state blocker
   require it). Fix: mint and remap cells across the WHOLE frame set
   -- two waiters sharing one cursor cell lift to shared UCF cells and
   lower back shared; independent cells stay independent. Tests:
   shared-cell and independent-cell round trips.

Constraints: touch only the two slice-8 files (the facade module and
its test). Preserve the confirmed variant completeness (all seven
pending-wait variants have lift/lower branches). ASCII, <= 80 cols,
cljstyle/kondo clean, no commit/stage/checkout/reset/stash, no
diagnostics. Verify: your namespace green on all three lanes plus the
full JVM suite; the tree is mid-flight (concurrent slice-5 rewrite:
embed-test/main-test failures are attributed to that, not yours;
measure the true baseline for YOUR namespaces with focused runs and
report both).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

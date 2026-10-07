Created-GMT: 2026-09-27 20:45:00 GMT
Created-Local: 2026-09-28 03:45:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 8 Confirmation, Round 7 — fresh verification of the structural fix

Role: Lead System Architect (confirmation gate)

Your round-6 review ran concurrently with the fixes it was reviewing
and returned two P1s describing the PRE-fix state (a retained call
resuming against the receiver's fixed call-in with the carried route
unused; :yin.k/c-0 resource keys colliding across separately lowered
frames). The structural fix landed at 2026-09-27 19:11:10 +07; your
review began earlier and cached the older tree.

This round: FRESH verification of the CURRENT tree
(/Users/sto/workspace/datomworld, uncommitted): src/cljc/yin/vm/ucf/
remote.cljc + test/yin/vm/ucf/remote_test.cljc. The fix report:
collab/1790530211570-vm-engineer-dao-stream-remote-slice8-fixes-r4.glm-flash.report.md

The structural fix (verify against the tree, do not trust the report):
- cell-resources UNIFORM: every UCF cell lowers to {:stream-id <fresh
  reflection key>, :cursor kept} under the cell id itself, response
  routes included (remote.cljc:522-535); route-cells and
  cursor-entry-key DELETED; the only vm/call-out-cursor-key reference
  is the LIFT-side emitter resolver call-out-cell (:315-326), reading
  the emitter's table.
- :next and :ffi waits build :cursor-ref {:type :cursor-ref, :id cid}
  with :stream-id = the cell stream's fresh reflection key (:583, :602)
  -- data-driven refs; migrated waits poll routes no new call shares.
- The retained :ffi-request lowers as the engine's own retained shape
  retry-local on the receiver's bridge (:610-621): the receiver's own
  bridge serves the retried request, the response lands on the
  receiver's own call-out, semantic-restore's response wait reads it
  through the receiver's own cursor entry.
- Tests: the two-call program needs NO manual handoff (the re-point
  deleted); the retained-call retry verified landing verbatim on the
  emitter's call-in through the wire; the new call parking on the
  receiver's pair; the program halting with "world!" and an empty
  wait-set; plus the shared/independent cell tests.

Verify BOTH former P1s are structurally impossible in the current
tree (no fixed-key route installation; fresh keys per batch with
in-batch sharing), hunt for new defects at the seams, and issue the
verdict on slice 8's two files. Parallel slice-5/slice-7 work -- not
under this gate.

Orchestrator evidence (do not rerun suites): the facade namespace 18
tests / 163 assertions green on JVM, Node, and Dart (focused runs).

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED

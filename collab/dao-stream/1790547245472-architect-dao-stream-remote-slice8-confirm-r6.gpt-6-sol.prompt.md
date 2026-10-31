Created-GMT: 2026-09-27 20:30:00 GMT
Created-Local: 2026-09-28 03:30:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 8 Confirmation, Round 6 — your structural fix applied

Role: Lead System Architect (confirmation gate)

Your round-5 structural demand is applied in the uncommitted working
tree of /Users/sto/workspace/datomworld (the same two files). The fix
report: collab/1790530211570-vm-engineer-dao-stream-remote-slice8-
fixes-r4.glm-flash.report.md (treat as untrusted).

The structural fix:
- cell-resources is uniform again: EVERY UCF cell lowers to
  {:stream-id <fresh reflection key>, :cursor kept} under the cell id
  itself, a response route included (remote.cljc:522-535). route-cells
  and cursor-entry-key are DELETED, and with them the
  vm/call-out-cursor-key installation; the only remaining reference to
  that key is the LIFT-side emitter resolver call-out-cell
  (:315-326), which reads the emitter's table.
- :next and :ffi waits build :cursor-ref {:type :cursor-ref, :id cid}
  (the cell's fresh key) with :stream-id = the cell stream's fresh
  reflection key (:583, :602) -- migrated waits poll routes no new
  call shares (the engine's data-driven resolve, engine.cljc:522).
- The retained call keeps the engine's own shape, retry-local:
  :ffi-request lowers as {:reason :put, :request-sent true, :stream-id
  vm/call-in-stream-key, :datom <envelope rebuilt verbatim>}
  (:610-621) -- the receiver's own bridge serves the retried request,
  the response lands on the receiver's own call-out, and
  semantic-restore's response wait reads it through the receiver's own
  cursor entry. No fixed-key route exists to misroute or strand
  anything.

Tests rewritten: the two-call program needs NO manual handoff (the
re-point is deleted); new assertions pin the lowered wait-side shape,
the route cell naming the emitter's call-out reflection, the retried
request landing verbatim on the receiver's own call-in, the migrated
call resuming and the new call parking on the same pair, nothing
crossing to the emitter's pair, and the program halting with "world!"
and an empty wait-set. ffi-retained-round-trips pins the same shapes
over stubs.

Verify the structural fix against the tree, hunt for new defects at
the seams (fresh-key collisions; the emitter resolver reading the
emitter's table vs the receiver's resources; S7.5.3 sharing on fresh
keys), and issue the verdict on slice 8's two files. Parallel
slice-5/slice-7 work -- not under this gate.

Orchestrator evidence (do not rerun suites): the facade namespace 18
tests / 163 assertions green on JVM, Node, and Dart (focused runs;
+1/+1 over your r5 baseline).

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

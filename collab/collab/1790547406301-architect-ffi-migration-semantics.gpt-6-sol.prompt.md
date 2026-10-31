Created-GMT: 2026-09-27 20:45:00 GMT
Created-Local: 2026-09-28 03:45:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Ruling — FFI-call migration resume semantics + multi-frame fresh-key remapping

Role: Lead System Architect

The slice-8 UCF facade (src/cljc/yin/vm/ucf/remote.cljc, uncommitted)
has been through four gate rounds; each round peeled a layer and the
latest (round 6, findings:
collab/1790530211571-architect-dao-stream-remote-slice8-confirm-r6.gpt-6-sol.findings.md)
exposed the two DESIGN questions the mechanical fixes cannot settle:

1. **How does a retained FFI call resume?** Round 4 deleted the
   emitter-pair installation and retried on the receiver's fixed
   call-in; round 6 rules that wrong -- a receiver with a different
   FFI handler returns a different result for the migrated call, and
   UCF 7.4.3 (:574) requires the carried endpoints preserved for the
   outstanding call. But the emitter's pair is a REMOTE object: the
   lifted UCF data names it (:yin.k/call-in/:call-out cells naming the
   emitter's reflections), and the receiver may not be able to reach
   it (or the emitter may be gone).
2. **Multi-frame installs.** Every lift restarts cell numbering at
   :yin.k/c-0; lowering uses that ID directly as a resource key. Two
   separately lowered frames in one task overwrite each other's cells.

Read first: the UCF doc 7.4.3 (:574 the carried endpoints), 7.5.3
(cell sharing, :916), 7.11 (the pending-state blocker); the round-4
ruling (collab/1790522276330-architect-dao-stream-remote-slice8-confirm-
r4.gpt-6-sol.findings.md -- the routing rule :614-624); the facade
(src/cljc/yin/vm/ucf/remote.cljc); the engine's data-driven resolve
(engine.cljc:515-530 -- :cursor-ref and :stream-id are plain data the
engine resolves from :resources).

Rule on the design, prescribed verbatim-applicable for the fix round:

1. **Retained-call resume semantics**: where does the outstanding
   call's retry go? The candidates: (a) the emitter's carried pair --
   reachable only while both the emitter's reflections and the
   emitter live; if unreachable, what? (b) the receiver's local
   bridge -- but a different handler returns a different result;
   (c) refuse to lower a retained call whose carried pair is not
   re-establishable (:yin.k/unsatisfied), with the carried route
   data preserved in the UCF entry for a later host that CAN. Or a
   combination (lower if reachable, else unsatisfied). Specify the
   rule, the exact resource keys, and what the wait entry's
   :cursor-ref/:stream-id reference.
2. **Multi-frame fresh-key remapping**: prescribe the remapping rule
   (e.g. lower mints a fresh key per UCF cell id scoped to the frame
   batch -- distinct frames never share a key; same cell id within a
   frame shares per 7.5.3), and the collision rule vs the receiver's
   existing resource table (fresh-key prefix? namespace by frame
   batch id?).
3. What the lift/lower tests must now pin.

Constraints: the fix stays within the two slice-8 files
(src/cljc/yin/vm/ucf/remote.cljc + its test) -- if your ruling
requires engine/semantic.cljc changes, say so explicitly and mark
them as follow-up work for a separate dispatch. Rule on the design;
the fix round implements verbatim.

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

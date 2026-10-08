Created-GMT: 2026-09-27 10:15:00 GMT
Created-Local: 2026-09-27 17:15:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 2 fixes)

# Task: Slice 2 — Fix the gate's two P1 findings

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 2 is
uncommitted). The gate returned REQUEST CHANGES with two P1s. Read:
collab/1790476861675-architect-dao-stream-remote-slice2-gate.gpt-6-sol.findings.md

1. P1 (remote.cljc:278): send-request! registers a descriptor/cursor/
   next request even when the channel writer refuses with full. Later
   calls find the unsent request outstanding (:543); with no
   resend-after they never send it — a transient full strands the
   attach probe or a read indefinitely. Fix: register only accepted
   sends (a refused send leaves nothing outstanding, so the retry
   cases re-send on the next ask, per 2.4's "leaves the request unsent
   and the operation answers as though unanswered"). Add a test: a
   dropping toy writer refuses full; the operation answers
   unanswered-style; when the writer recovers, the next ask sends and
   completes.
2. P1 (remote.cljc:374): installed budget-chase outcomes are keyed only
   by cursor and consumed by any reflection on the shared link (:520).
   Two served identities can use equal cursor values, so a next on one
   reflection returns the other stream's outcome. Fix: key installed
   outcomes by served identity AND cursor, retaining sharing among
   reflections of the same stream (same identity = same key). Add a
   test: two served identities with equal cursor values; each
   reflection's next returns only its own stream's outcomes.

Constraints: touch only src/cljc/dao/stream/remote.cljc and
test/dao/stream/remote_test.cljc. Preserve everything the gate
confirmed (nine readings ratified; mirror dispatch order; no privilege
roles). ASCII, <= 80 cols, cljstyle/kondo clean, no commit/stage/
checkout/reset/stash, no diagnostics. Verify all three lanes
sequentially/solo with exact counts (current: JVM 2,252/183,193/0;
Node 2,164/49,799/0; Dart 2,126).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

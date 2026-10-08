Created-GMT: 2026-09-27 13:15:00 GMT
Created-Local: 2026-09-27 20:15:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 3 fixes)

# Task: Slice 3 — Fix the gate's three findings

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 3 is
uncommitted). The gate returned REQUEST CHANGES with one P1 pair and a
P2. Read: collab/1790489433608-architect-dao-stream-remote-slice3-gate.gpt-6-sol.findings.md

1. P1 (ws_project.cljc:112-113): when the traffic medium ends, step!
   stops the projection but leaves its ring open — a link waiting on
   the ring can block forever. Fix: close the ring on medium end too
   (the projection is over; nothing more can arrive), and add the
   gate's test: the link observes channel loss (transport-error
   channel-gone) when the traffic medium ends.
2. P1 (ws_project.cljc:249-255): closed sessions stay in :sessions and
   are stepped every tick — handles, rings, and projections accumulate
   without bound across repeated connections. Fix: remove a session
   once its projection has closed and its retained requests have been
   handled, and test slot reuse across repeated connections (a second
   connection works and the first's state is gone).
3. P2 (ws_project.cljc:334-350): a second successful dial-attach!
   silently replaces the active channel while reusing its ring and
   original traffic cursor — contradicting the documented
   one-active-attachment reading. Fix: reject a second attach while a
   dial is active (the minimal reading; state the error clearly), or
   explicitly close-and-replace with fresh media and cursors. Choose
   one, document it, and test it.

Constraints: touch only src/cljc/dao/stream/ws_project.cljc and
test/dao/stream/ws_project_test.cljc (plus the jvm test file if a
channel-loss integration test belongs there). Preserve everything the
gate confirmed. ASCII, <= 80 cols, cljstyle/kondo clean, no commit/
stage/checkout/reset/stash, no diagnostics. Verify all three lanes
sequentially/solo, exact counts (current: JVM 2,268/183,303/0;
Node 2,177/49,865/0; Dart 2,139).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

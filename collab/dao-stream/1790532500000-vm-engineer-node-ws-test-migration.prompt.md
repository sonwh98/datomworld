Created-GMT: 2026-09-27 19:20:00 GMT
Created-Local: 2026-09-28 02:20:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (node ws test migration)

# Task: Migrate test/dao/stream/ws/node_test.cljs to the slice-5 wire flow

Role: QA & Verification (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master). The slice-5
REPL service rework changed the served wire flow; the node ws tests
still assert the OLD flow and fail (7 failures). A prior session
"fixed" test predicates BLIND (it cannot run the Node lane: its shell
resolves Java 17, shadow-cljs needs 21) -- its changes made it worse
(4 -> 7 failures). You HAVE the lane: your shell has mise.

Evidence the new flow WORKS (from the current failure output itself):
the traffic log in the a-reflection-round-trips failure shows the full
working protocol -- :ws/opened, the descriptor-probe answer carrying
:dao.stream.remote/surface #{:writer :reader}, a cursor answer (:id 3),
another cursor answer (:id 4), and :ws/value "hello" at :id 5. The wire
is fine; the assertions are stale.

Read first: the failing tests in test/dao/stream/ws/node_test.cljs
(~:465 a-reflection-round-trips..., ~:505 detachment-reattaches...,
~:750-850 transit-and-cbor...), the slice-5 reworked serve flow
(src/cljc/yin/repl/serve.cljc, connect.cljc, adapter.cljc), and
src/cljc/dao/stream/ws_project.cljc (the composition the serve side
drives). The prior session's blind edits are in the working tree --
revert or redo them as the migration requires.

Work items:
1. Migrate the failing tests to the NEW wire flow: assert the actual
   event sequence the new composition emits (opened + descriptor-probe
   answer + cursor answers + value, per the traffic evidence), the
   correct per-identity correlation, and the real terminal states.
   Do NOT weaken assertions to pass: assert the new flow's real
   sequence exactly (the strongest honest form).
2. Fix r10's blind predicate edit if it masks the real flow.
3. detached-reattaches: the "no channel-gone outcome" failure needs
   the new flow's channel-loss semantics (the serve shutdown grace,
   serve.cljc stop-grace-ms) reflected in the test's drive cadence.
4. transit-and-cbor tests: the text/CBOR dual-client round trip through
   one listener -- update to the new flow (the nil results are stale
   positions/shapes, and the cbor codec assertion expects an adapter
   the new composition wires).

Constraints:
- Touch ONLY test/dao/stream/ws/node_test.cljs (reverting r10's blind
  edits counts as part of this). If a failure traces to a REAL bug in
  the slice-5 composition (not the test), STOP and report BLOCKED with
  file:line evidence instead of fixing src/.
- ASCII, <= 80 cols, cljstyle/kondo clean, no commit/stage/checkout/
  reset/stash, no diagnostics.
- Verify with mise: the Node lane to 0 failures (exact counts), JVM
  focused (yin.repl.main-test / yin.repl.embed-test / dao.stream.
  remote-test) green, and one full JVM suite run (attribute any
  concurrent-slice failures).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

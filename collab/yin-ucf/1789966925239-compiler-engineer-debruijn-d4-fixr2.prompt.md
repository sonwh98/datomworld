Created-GMT: 2026-09-21 05:02:05 GMT
Created-Local: 2026-09-21 12:02:05 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D4 session)
# Task: apply the D4 review round's accepted findings
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 12:02:05 +07 | Status: active | Rationale: same implementer, warm session

Review verdict was READY (claude-sonnet-5, no P1) with fold-in
recommendations. Apply in src/cljc/yin/vm/debruijn.cljc and
test/yin/vm/debruijn_test.cljc only:

- **P2-1 (mid-frame retry test)**: add a fixture that fills HALF a graph,
  hits :dao.stream/blocked with :retry, then appends the rest and re-steps
  to the full expected projection — proving the frame is carried across a
  retry (the code re-emits frame/pending unchanged at ~1432-1437; today
  nothing would catch dropping :frame).
- **P2-2 (catch-all, ~1418-1419)**: non-ex-info throwables currently become
  :invalid-input with {} — mislabeling internal defects and host-divergent
  (StackOverflowError escapes on JVM, js/Error is absorbed on CLJS). Change
  the non-ex-info case to {:rule :internal-error, :message (ex-message t)}.
  Test: an internal defect yields :internal-error with the message, not
  :invalid-input.
- **P3-3 (recommended fold-in)**: the adjacent-graphs fixture uses
  (fn [x] x) and (fn [y] y) — alpha-equivalent, so a bug replaying graph
  one's projection for graph two would pass. Make the second graph
  semantically different, e.g. (fn [x] 42).
- **P3-7 first nit**: fix the forward-step docstring (~1331-1358): :retry
  results DO carry :outcome.

Deferred as recorded notes (do NOT implement): P3-4/5 test-breadth rows;
P3-6 reader canonical-spelling gate (architect awareness item); P3-7
remaining nits (dead vector? guard, :pending seq drift, unchanged-cursor
loop — matches dao.stream.forward precedent); P3-8 §1 state-list literal
departure (architect awareness item).

Verification (exact counts): focused JVM, kondo, cljstyle. Orchestrator
reruns the full lanes. One simple command per step.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

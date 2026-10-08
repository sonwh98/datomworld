Created-GMT: 2026-09-27 08:05:00 GMT
Created-Local: 2026-09-27 15:05:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 1 fixes)

# Task: Slice 1 — Fix the gate's three findings

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master; slice 1 is
uncommitted). The gate returned REQUEST CHANGES with two P1s and one P2.
Read: collab/1790469165863-architect-dao-stream-remote-slice1-gate.gpt-6-sol.findings.md

1. P1 (middleware.cljc:334): gate state must be PER-WRAPPED-HANDLE and
   minted AT WRAP, per the spec ("wrap calls cursor with
   :dao.stream/oldest once when it constructs the gate"; "the cursor
   and value are state of the wrapped handle"). Currently the gate
   value owns a volatile minted at its construction, so reusing one
   gate across two wrapped handles shares the decision cursor. Fix:
   initialize independent gate state for each wrap call (the gate map
   becomes a definition; wrap instantiates its state), and change the
   test at middleware_test.cljc:466 to assert the correct lifecycle
   (one gate definition wrapped twice -> independent cursors; mint
   happens per wrap).
2. P1 (middleware.cljc:73): wrap accepts arbitrary out results — an out
   can turn a selected next value into :dao.stream/blocked, expressing
   the prohibited filter. The position rule must be structural, not
   conventional. Fix: wrap validates each out result against the
   permitted changes (outcome kind, :dao.stream/outcome, identity, op,
   id, cursors, anchors, gap-recovery cursors unchanged; element
   sequence preserved; only :dao.stream/value on an ok next and
   (first args) on append! may be replaced; open keys may be added/
   read). An out result violating this raises (host-assembly defect,
   per dao.stream.md Result Convention) — a filter is then not merely
   discouraged but impossible. Add the adversarial filter test: an out
   that returns blocked/changes outcome kind/cursor raises.
   Design note: keep the validation cheap and total (structural checks
   on the outcome map only; no deep equality over elements).
3. P2 (middleware.cljc:371): after a gap recovery read returns
   something other than ok/blocked/end/gap, the catch-all retains any
   cursor in that outcome. Fix: handle recovery gap explicitly (adopt
   the recovery cursor, stay without value); clear cursor and value for
   every other recovery outcome (re-mint next operation), per spec.

Constraints: touch only the two slice-1 files (middleware.cljc,
middleware_test.cljc). Preserve everything the gate confirmed. ASCII,
<= 80 cols, cljstyle/kondo clean, no commit/stage/checkout/reset/stash,
no diagnostics. Verify all three lanes sequentially/solo with exact
counts (current: JVM 2,232/183,003/0; Node 2,144/49,655/0; Dart 2,106).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

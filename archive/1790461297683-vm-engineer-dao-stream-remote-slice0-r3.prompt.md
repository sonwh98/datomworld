Created-GMT: 2026-09-27 06:35:00 GMT
Created-Local: 2026-09-27 13:35:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 0, round 3)

# Task: dao.stream.remote Slice 0, Round 3 — final scope extension

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ d81e50ad,
clean tracked tree). Rounds 1-2 briefs and your BLOCKED analyses are the
evidence base; this round resolves the final conflict and completes
slice 0.

Round-2 contract (collab/1790460651981-vm-engineer-dao-stream-remote-
slice0-r2.prompt.md) remains in force with ONE scope addition:

- src/cljc/dao/jing.cljc is now in the allowed set, for exactly:
  (a) a :refused clause in observe-step!'s case (dao.jing.cljc:683-717)
  mirroring its :defect clause — report
  {:signal :dao.stream/refused, :member i, :result read}, member cursor
  unchanged, :next moves past — per your own round-2 analysis;
  (b) the two docstring touch points (line 636 "the same seven
  outcomes" -> eight; lines 648-649 the refused condition added to the
  k enumeration). Nothing else in that file.

Apply everything else from your round-2 analysis (all of it was
verified ready): the stream.cljc outcome sets (refused between closed
and transport-error), observe.cljc's explicit refused branch + defect
trio, engine.cljc's shaped-refusal branches in handle-put/handle-next
(recognized throw outcomes get explicit branches so v1-compat throws
survive; terminal-resume-outcome and make-woken-run-queue-entries admit
and shape refused per the blocked-must-not-change law), the manifest
exclusions-with-reasons (ringbuffer, memory-log, stream_test fixture),
the waitset plans (:dao.stream/refused :dao.stream/refused, no cursor,
advance 0), the observe_test tables (read :refused; effect :failed),
and the new tests per behavior.

Constraints unchanged: only the listed files; ASCII; <= 80 columns on
added/edited lines; cljstyle and kondo clean on touched files; no
commit/stage/checkout/reset/stash; no leftover diagnostics. Verify all
three lanes sequentially and solo, exact counts (baseline
2,057/180,912/0 plus your new tests).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

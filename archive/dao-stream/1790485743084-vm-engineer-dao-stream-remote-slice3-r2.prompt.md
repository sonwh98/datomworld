Created-GMT: 2026-09-27 12:35:00 GMT
Created-Local: 2026-09-27 19:35:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 3, round 2)

# Task: dao.stream.remote Slice 3, Round 2 — ws-project + composition now; retirement deferred

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ 803c9004,
clean tracked tree). Your BLOCKED analysis is accepted: the retirement
cannot land inside this slice without breaking dao.stream.serving and
the unmodifiable suites. Per your option 1, slice 3 is split:

- THIS round: work items 1 and 2 only — ws-project (per
  dao.stream.remote.md 3.1: cursor keeping on the traffic medium,
  :ws/attachment filtering, :ws/value forwarding to the ring,
  :ws/error diagnostic dropping, terminal/failure events closing the
  ring) and the composition helpers wiring both ends of the section-5
  toy over a real socket (accept side: ws-project then mirror-step per
  accepted connection; dial side: ws-project then the link's drain).
  The retirements (:ws/accept frame, disclaim, served-path table) are
  DEFERRED to slices 4/5, where their consumers are deleted; note the
  deferral in code comments at the retirement sites
  (ws.cljc :ws/accept send at ~:555, the :served map at ~:411-497) so
  the successor slices see it.
- Round-2 contract: your BLOCKED report
  (collab/1790484802056-...slice3 BLOCKED analysis is in your previous
  output; the harness notes) plus the original brief's items 1-2 and
  4. The retirements (original item 3) are OUT of scope.

New constraints replacing the old file list:
- Allowed files: NEW src/cljc/dao/stream/ws_project.cljc (or wherever
  the ws namespace structure suggests — note your choice), its test
  file, and any NEW composition test file. Do NOT modify ws.cljc, the
  adapters, dao.stream.ws.md, or any existing test. The deferral
  comments: if you judge even comments in ws.cljc worth adding, they
  are allowed — but no behavior change there in this round.
- The wire toy test (accept side) must run the mirror against REAL
  dao.stream.ws attachments on one host first (clj in-process ws), and
  the cross-host socket proofs (clj->Node, Node->clj, cljd->clj) are
  run by the orchestrator if your environment cannot — report exactly
  what ran.
- Everything else unchanged: spec fidelity to 3.1 and section 5, ASCII,
  <= 80 cols, cljstyle/kondo clean, no commit/stage/checkout/reset/
  stash, no diagnostics, tri-host lanes sequential/solo with exact
  counts (baselines: JVM 2,255/183,224/0; Node 2,167/49,829/0;
  Dart 2,129).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

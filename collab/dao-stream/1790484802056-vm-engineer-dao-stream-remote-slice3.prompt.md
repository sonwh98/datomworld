Created-GMT: 2026-09-27 12:35:00 GMT
Created-Local: 2026-09-27 19:35:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 3)

# Task: dao.stream.remote Implementation — Slice 3 (WebSocket channel composition)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ 803c9004,
clean tracked tree; slices 0-2 are committed).

Implement Slice 3 exactly as the plan defines it. Read first, in order:
- docs/design/dao.stream.remote.md section 3.1 (WebSocket: what is
  REUSED from dao.stream.ws — attachment model/identity, the deposit
  adapter's event shape {:ws/attachment id :ws/event e :ws/value v},
  the writer handle's framing and bounded acceptance, resolution and
  lifecycle vocabulary — and what is NEW: the ws-project step between
  the deposit medium and the channel, its cursor keeping, event
  filtering by :ws/attachment, value forwarding, diagnostic dropping,
  and terminal/failure events closing the ring buffer) and section 5's
  toy walk-through (the composition: serve = accept connections,
  drive ws-project then mirror-step per accepted connection; the
  remote peer attaches and drives the same pair over its dialed
  attachment)
- docs/design/dao.stream.ws.md — the existing ws layer being reused and
  the retirements this slice makes (:ws/accept, disclaim, the
  served-path table)
- docs/design/dao.stream.remote.implementation-plan.md section 3,
  slice 3 row and section 4's dao.stream.ws.md edits
- src/cljc/dao/stream/remote.cljc + middleware.cljc (your building
  blocks: mirror-step, the link/attach path)
- src/cljc/dao/stream/ws.cljc and the ws host adapters (what exists)

Work items:
1. ws-project: a step the composition drives at its own cadence,
   between the ws deposit medium and a ring buffer channel. Per 3.1:
   holds its reading cursor on the traffic medium; keeps events whose
   :ws/attachment names this channel; appends each :ws/payload's
   :ws/value onto the ring; drops :ws/error diagnostics; closes the
   ring on terminal lifecycle (:ws/closed, :ws/ended) or failure
   resolution (:ws/not-found, :ws/transport-error) — the link then
   observes channel loss as end (2.4).
2. The composition helpers wiring both ends of the toy over a real
   socket: per accepted connection, drive ws-project then mirror-step;
   per dialed attachment, ws-project then the link's drain (the
   reflection path already composed via attacher). The wire toy of
   section 5 must work end to end.
3. The dao.stream.ws retirements the slice names: retire :ws/accept,
   disclaim and the served-path table (section 4 of the plan lists the
   exact dao.stream.ws.md edits; the source retirements are the code
   behind those frames). Keep everything 3.1 marks REUSED.
4. Tests: the plan's proof — the toy across a real socket clj->Node,
   Node->clj, cljd->clj; both directions serving on one channel;
   browser dials and reads (mark browser-only test as skipped where
   the harness cannot run a browser; the other lanes must pass); a
   closed connection observed as the link's end. Plus unit tests for
   ws-project (attachment filtering, value forwarding, diagnostic
   dropping, terminal close).

Constraints:
- Allowed files: NEW src/cljc/dao/stream/ws_project.cljc (or the
  location the existing ws namespace structure suggests — note your
  choice), its test file, src/cljc/dao/stream/ws.cljc and the ws host
  adapters for the retirements, and dao.stream.ws.md for the doc
  edits the plan's section 4 lists. Touch nothing else. If a
  retirement turns out to break a consumer outside this set
  (yin.repl, dao.jing.remote), STOP and report BLOCKED with the list —
  those consumers migrate in later slices.
- The spec is the contract; genuine ambiguities: minimal reading,
  noted in your report.
- Pure ASCII, <= 80 columns on every added/edited line; cljstyle and
  kondo clean; no commit/stage/checkout/reset/stash; no leftover
  diagnostics.
- Verify: JVM full suite green (current baseline 2,255/183,224/0 plus
  your new tests), Node green, Dart green. Sequential, solo. Exact
  counts. The cross-host socket tests (clj<->Node, cljd->clj) follow
  the existing cross-host test patterns in the repo; if a cross-host
  pattern cannot run in your environment, run what is runnable and
  report exactly what ran and what the orchestrator must run.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

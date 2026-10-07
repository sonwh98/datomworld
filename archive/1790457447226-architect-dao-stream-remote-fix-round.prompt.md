Created-GMT: 2026-09-27 03:25:00 GMT
Created-Local: 2026-09-27 10:25:00 +0700
Coding-Agent: codex
Session-ID: resume-of-01a0debb-6229-79e1-890d-4d1e0b7d8565

# Task: Fix Round — your five re-gate findings, in the document set you may edit

Role: Lead System Architect (author of the fix round)

The owner has authorized an autonomous architect mob (you + claude-fable)
to bring the dao.stream.remote spec set to an implementable state. You
wrote the five findings in this thread's re-gate; you now fix them in
the documents. The two P1s are design completions, which is architect
work; fable works the same five findings in parallel from its own
thread, and the two results will be reconciled by the orchestrator into
one final text that both of you then gate.

Edit scope (exact): docs/design/dao.stream.remote.md,
docs/design/dao.stream.middleware.md, docs/design/dao.shibi.md,
docs/design/dao.stream.remote.implementation-plan.md,
docs/dao.space.stigmergy.md, docs/design/dao.stream.md. Nothing else.

Your five findings, restated as work items:

1. P1 middleware.md:102-124: fully specify decision reads. Required:
   how the gate acquires its initial cursor from the decision handle;
   the state transition for EVERY read outcome (element, gap, end);
   repeated-gap behavior (is once-more-once bounded? what after the
   second gap); serialization or concurrency semantics for a wrapped
   handle held by concurrent calls. Keep the shape: decision medium is
   a capacity-1 medium written by a composed index interpreter; the
   gate reads a bounded decision from it.
2. P1 middleware.md:103-120 + dao.shibi.md:25-34 + remote.md:608-618:
   complete or bound the ShiBi fit. Two acceptable outcomes: (a) define
   how dao.space.query consumes requests and publishes a
   request-specific decision through ordinary streams; or (b) declare
   explicitly that verify IS the query interpreter's required adapter
   contract, and state its limits (what a full index+query ShiBi would
   additionally need). Owner direction is that ShiBi is a tuple space
   emerging from two interpreters (dao.space.index, dao.space.query);
   the seam must host it or say precisely what is deferred. Choose one
   outcome, write it, and mark the other as non-goals if relevant.
3. P2 remote.md:623-675 + implementation-plan.md:121-162: right-size to
   under 600 lines WITHOUT dropping a rule: move or condense the fate
   table and the amendment inventory into the implementation plan,
   keeping only the essential successor pointer and any normative rule.
4. P2 docs/dao.space.stigmergy.md:242-245: mark the
   connect-content!/default-handlers passage historical and point to
   the planned dao.jing.content successor.
5. P2 remote.md:406-410 + 270-278: state explicitly that the pair
   adapter turns an in-gap on the pair channel into channel termination
   (end) for the affected link, with resend absorbing the loss
   per the existing text; or define equivalent loss reporting.

Owner invariants that bind every edit (memory-confirmed):
- P2P, no server/client concept, no privileged node; client/server only
  as interpreter convention; dial vs accept is a per-channel
  establishment fact.
- dao.stream is an abstraction boundary; network concepts stay on the
  remote side of the line.
- Authn/authz belong to ShiBi; middleware and remote expose only the
  capability-agnostic seam (opaque credential, mirror-side gate,
  reflection-side present, :dao.stream/refused). No token design here.
- Below the dao.stream boundary, prefer libraries over hand-rolled
  crypto/protocols (relevant to any encryption-caveat wording).
- ShiBi is a tuple space of index and query interpreters; the seam must
  host it or state precisely what is deferred.

Constraints: ASCII; <= 80 columns on every added/edited line (the
existing docs are the width convention to match); preserve all content
the re-gate marked FIXED; do not reopen decided questions (the mirror
and reflection shape, one answer shape, piggyback removal are settled);
no new modules, no new files beyond the listed ones.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize per work item: what you changed, with file:line evidence, and
the resulting line count of remote.md.

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

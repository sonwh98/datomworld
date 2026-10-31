Created-GMT: 2026-09-26 00:07:56 GMT
Created-Local: 2026-09-26 07:07:56 +0700
Coding-Agent: deepseek
Session-ID: resume-of-1b25b60b-b390-41a6-bf52-1f43420f0d3a

# Task: re-review docs/design/dao.stream.serve.md after the author's revision

Role: Adversarial Design Reviewer and Lead System Architect

Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-26 07:07:56 +0700 | Status: active | Rationale: owner directive "send the serve spec to deepseek for review"; this is the confirmation pass on the revision you asked for. The document was authored by claude-fable-5-1.

You are a HEADLESS plan-mode reviewer. Produce the COMPLETE review in your final response
now. Do not wait for approval, do not ask questions, do not end with a plan or a promise of
a verdict. Read-only: do not edit files. Cite file:line of the revised document.

## Owner statements (verbatim quotes)

"send the serve spec to deepseek for review"
"my invariant is any implementation of dao.stream can mechanically be exposed as a websocket or UDP and be able to traverse NAT in a P2P use case. There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"

## What changed
The author revised /Users/sto/workspace/datomworld/docs/design/dao.stream.serve.md (now
1,187 lines; it was 1,085) in answer to your review
(/Users/sto/workspace/datomworld/collab/1790380759345-architect-dao-stream-serve-doc-review.deepseek-v4-pro.findings.md).
The author's account (untrusted; verify against the document): it ACCEPTED your P1 append
dedup and rewrote 3.4 as a per-session high-water mark (request ids strictly increase per
session; above the mark perform, equal answer from the record, below answer
:dao.stream.serve/stale and never perform); it ACCEPTED your P1 open idempotency in 3.2 (a
duplicate open for a live [peer n] with the same identity returns the same :serve/opened and
attachment and leaves the mark and held demands untouched; a different identity is
:serve/close :conflict; open for a closed id is :detached and creates nothing; closed ids are
remembered for the retry horizon); it ACCEPTED your P2 (the next response vector is non-empty
and includes the first non-ok outcome as its last element); it PARTLY REBUTTED your P3 on the
wire :dao.stream/cursor operation (it says the op is not dead: it serves transport-owned
anchors the piggyback cannot answer, such as a byte offset or a timestamp, relaying
invalid-anchor, and the serving side uses it to refresh the anchors it piggybacks); it
accepted the other two P3s, added completion criteria 12 (ended versus detached), 13 (frame
budget and oversize) and 14 (session-control idempotence), and added the two completeness
sentences (the meeting peer must itself be directly reachable; dao.jing.remote.async and
dao.jing.remote/content-client named as surviving pieces). It added no new decision to
section 15.

## What to produce
1. For EACH of your earlier findings (P1 append dedup, P1 open idempotency, P2 batched
   terminal outcome, the three P3s, the two untested behaviors, the two completeness
   items): RESOLVED, PARTLY or NOT RESOLVED, with line evidence. Do the two P1 rules
   COMPOSE (a duplicate open must not reset the dedup mark; a stale append must not be
   performed even after an open, a close and a reopen of the same [peer n])? Is the state
   bounded and is the bound stated?
2. Judge the author's rebuttal on the wire :dao.stream/cursor operation against the
   contract (dao.stream.md) and section 3.6 of the document: is it correct that the op is not
   dead, or was your P3 right?
3. Any NEW inconsistency, overclaim or invariant leak introduced by the revision, including
   in the renumbered completion criteria and any cross-reference that the renumbering broke.
4. Your overall verdict on whether the document is now ready to commit as a design target.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
(meaning: whether the document is ready to commit as a design target.)

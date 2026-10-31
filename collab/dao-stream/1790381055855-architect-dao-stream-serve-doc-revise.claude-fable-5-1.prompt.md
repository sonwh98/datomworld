Created-GMT: 2026-09-26 00:04:15 GMT
Created-Local: 2026-09-26 07:04:15 +0700
Coding-Agent: claude
Session-ID: resume-of-05ce85cc-7cbb-407f-b445-1e9756ad2e35

# Task: revise docs/design/dao.stream.serve.md to answer the DeepSeek review

Role: Lead System Architect (author)

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-26 07:04:15 +0700 | Status: active | Rationale: owner directive to have Fable write the spec; DeepSeek reviewed it and returned REQUEST CHANGES; you revise your own document

Repository: /Users/sto/workspace/datomworld (main checkout, master). You may edit exactly ONE
file: docs/design/dao.stream.serve.md. A backup of the current version is in archive/. Do not stage or
commit. Same format rules as before: ASCII, box tables only (never markdown pipe tables),
no em dashes, prose at about 80 columns, no paths into collab/, no mention of the review
process or which model wrote what.

## Owner statements (verbatim quotes)

"send the serve spec to deepseek for review"
"yes, have fable write it"
"my invariant is any implementation of dao.stream can mechanically be exposed as a websocket or UDP and be able to traverse NAT in a P2P use case. There should be no concept of a server or client. its P2P but client/server model can be implemented by convention. there is no priviledge server or client"

## The review (independent, DeepSeek): REQUEST CHANGES
/Users/sto/workspace/datomworld/collab/1790380759345-architect-dao-stream-serve-doc-review.deepseek-v4-pro.findings.md
It passed the invariant, the contract consistency, NAT feasibility, security honesty, the
dao.jing.remote claim, and sections 15 and 16. It found:
1. P1, section 3.4 and 6.3: the serving side dedups only the id of the LAST append, so a
   delayed retransmit of an earlier append arriving after a later one is performed again.
   This contradicts completion criterion 4 and the claim that reordering is harmless for
   writes.
2. P1, section 3.2: :serve/open has no idempotency rule; a retransmitted open for an
   existing [peer n] could re-mint an attachment or reset the append dedup record.
3. P2, section 3.3 and 4: "stopping at the first non-ok" does not say whether the
   terminating non-ok outcome (blocked, end, gap) is INCLUDED in the response vector; gap
   fidelity depends on it.
4. P3: the wire :dao.stream/cursor operation looks dead given the anchor piggyback of 3.6;
   the section 17 sentence "no reliability layer" should say no READ reliability layer;
   "descriptor" has two meanings in adjacent sections (the source's host-local descriptor
   versus the serve descriptor) and needs disambiguating.
5. Two untested behaviors: ended versus detached close, and oversize/transport-error
   handling and the frame budget. Add completion tests for both.
6. Two one-sentence completeness items: the meeting peer must itself be directly reachable
   for the inbox fallback; name dao.jing.remote's content-client and dao.jing.remote.async
   as surviving jing-level pieces.

## What to do
Verify each finding against the document and the contract (dao.stream.md) yourself, then for
EACH say ACCEPT, REBUT (with the evidence) or PARTLY, and apply the fix in the document.
For 1 and 2, state the exact rule: what the serving side keeps (a bounded set of recent
append ids, or a monotonic per-session sequence with a high-water mark), what a duplicate
or older append and a duplicate open return, and how the bound is sized; check that the
two fixes compose (an open must not reset the dedup state). Do not concede merely to
converge; if a finding is wrong, rebut it in your report and leave the document as it is on
that point. Keep the document self-contained and do not change decisions the owner has not
ruled on (they stay in section 15).

## Report
When the file is revised, return a short final response: for each finding ACCEPT, REBUT or
PARTLY with a one-line reason, the sections you changed, any new decision you had to add to
section 15, and the new line count. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Created-GMT: 2026-09-17 08:08:42 GMT
Created-Local: 2026-09-17 15:08:42 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: a4aea5af-4571-4db4-b3a3-b4ffa5b641b5
Role: Lead System Architect

# Task: Fix Phase 0's grep sweep gap (r2)

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-17 15:08:42 +0700 | Status: active | Rationale: same session, follow-up correction on its own draft

An adversarial review
(`collab/1789630998716-review-dao-stream-v1-retirement-plan.gemini-3.1-pro-high.findings.md`)
of your draft `docs/design/dao.stream.v1-retirement.implementation-plan.md`
came back **NOT READY FOR SIGN-OFF** on exactly one finding, everything else
verified clean (D4, D5, the three dead-code deletions, census completeness,
U3/U4 parallelism, D7):

> Phase 0's mechanism grep sweep must be updated to include
> `bind-stream!|put-frame!` to ensure it catches any newly introduced
> terminal bindings before U3 lands.

Concretely: the second Phase 0 sweep (currently searching for
`register-(reader|writer)-waiter!|drain-one!|take!!|\bds/open!|defopen|
closed\?|:daostream/gap|\{:position [0-9a-z(]`) omits `bind-stream!` and
`put-frame!` — the exact two v1-mechanism call sites (`flutter.cljd:361`,
`web.cljs:82`) your own plan had to add to the census by hand because a
namespace grep misses them. Without those terms in the sweep, a new
`bind-stream!` call introduced anywhere between now and U3 landing would go
undetected.

## Task

1. Add `bind-stream!` and `put-frame!` to Phase 0's second grep sweep in
   `docs/design/dao.stream.v1-retirement.implementation-plan.md`.
2. Check whether any other v1-mechanism call name your census relied on
   finding "by hand" (not by namespace grep) is similarly missing from that
   sweep, and add it too if so — the reviewer only flagged this one, but
   confirm rather than assume it's the only gap.
3. Add one line to the Revision History section recording this as r2: what
   changed and why (the review finding it corrects).
4. Do not change anything else in the document — this is a narrow
   correction, not a re-draft.

Write the file directly. Report back a short confirmation of exactly what
changed.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

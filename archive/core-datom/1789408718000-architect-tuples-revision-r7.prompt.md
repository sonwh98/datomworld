Created-GMT: 2026-09-14 17:58:37 GMT
Created-Local: 2026-09-15 00:58:37 +07
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1 (resume — your own drafting session for docs/design/yin.vm.tuples.md)

# Task: Revise the tuple code-representation design (round 7 — one finding)

Role: Architect — design document only. No implementation, no staging, no commits.

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-15 00:58:37 +07 | Status: active | Rationale: you authored all revisions; the reviewer is gpt-6-astra (different family), independence preserved. The owner has directed iteration until agreement.

## Context

Your round-6 revision was re-reviewed: **REJECT, 1 P1** — the declaration catalogue resolved; every other mechanism stands accepted ("no additional rejection is based on unfinished implementation work"). One allocation defect remains. Full findings at:

`collab/1789408535000-architect-tuples-rereview-r6.gpt-6-astra.findings.md`

Read that file first. The orchestrator verified the reviewer's claims against source: `prepare-tx` (`transact.cljc:189-207`) is a pure function of supplied history (two preparations against one empty history both assign `{-1 16}`); `transact!` (`transactor.cljc:231-254`) allocates only `t` under its write lock and appends already-resolved datoms — it allocates no entity ids; and the contract's own T6 (`dao.space.transactor.md:75`) documents uncoordinated writes as "a documented hazard, not coordinated."

## Task

Revise `docs/design/yin.vm.tuples.md` against exactly this finding:

**The construction record attributes allocation uniqueness to a path that does not provide it (§8.4.1).** The current text claims the log transactor's permanent-id allocation makes construction-record stamped ids unique. It does not: `prepare-tx` is a stateless preparation step, `transact!` performs no id allocation, and serializing commitments through one transactor still admitted two records with entity id 16 (the reviewer's executed result). Fix per the reviewer:

- Specify an **allocation owner per log** that serializes fresh-ID allocation and commitment **together** (one boundary: allocate the id and commit the construction record in the same coordinated act), preserves allocation state across reconstruction, and returns the allocated id to the successful constructor. Name the allocation/write boundary explicitly and require every construction to go through it. This is a new composition obligation — it may be unimplemented and listed in §10, but the specification must state it as such.
- **Or** use the explicitly unique composition token for **all** constructions (no log-allocated ids at all), with the uniqueness warranty the reviewer's round-6 "verified sound" note already grants the fallback ("an explicit composition allocator with a never-reuse warranty can supply incarnation identity without a transacted log"), covering independent constructions and restarts.
- Whichever you choose: **remove the claim that the existing transactor already guarantees uniqueness** (the reviewer requires this explicitly), and keep the retry rule (fresh allocation on construction retry, staged-flush reuse unchanged) consistent with the chosen mechanism. Update §10.3 to match.

Choose the mechanism you can specify most soundly — one path, stated as a rule, not both.

## Constraints

- Edit only `docs/design/yin.vm.tuples.md`. Stage and commit nothing.
- Rules stated as rules; calls made and stated; no option menus. Cite file:line for every load-bearing claim about current code; re-verify before citing.
- If you believe the finding cannot be fixed without an owner ruling, say so explicitly rather than inventing one.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: claude
Session-ID: 8677b374-bb75-4103-afab-4b65727a76c1

Then: the new line count; a note on the single finding (which mechanism chosen, fixed how); sections touched; anything verified against source versus taken from the review.

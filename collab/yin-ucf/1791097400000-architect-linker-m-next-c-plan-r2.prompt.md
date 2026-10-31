Created-GMT: 2026-10-04 06:57:00 GMT
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)

# Task: revise the M-next C plan (round 2). Read-only; final response is the full revised plan.

Role: Architect

Read astra's review: /Users/sto/workspace/datomworld/collab/1791097000000-architect-linker-m-next-c-plan-review.gpt-6-astra.findings.md . Verdict: substrate ACCEPTED with changes; slicing REVISE; owner question item AGREED (settled by the pair, no owner question needed beyond the stated guarantee wording); C/D/E interfaces CLARIFY.
Verify each claim against the tree (e.g. jing file put dedup at src/cljc/dao/jing/file.cljc ~340, transactor T18 and "Where durability lives", lease reclaim at src/cljc/dao/lease.cljc ~1380, attribute-order note at transactor.cljc ~64) and accept or rebut each with evidence. Then produce the REVISED plan, complete and standalone:
- the narrowly scoped transactor exception text; whole-decision serialization; the reclaim adapter stated as a composition contract; whole-authority poison; judge reconstruction (:seen, :answered, accepted proposals, terminal history) owned by C3; journal framing with explicit positions that preserve repeated equal appends; deterministic datom ordering and portable bounds; the precise exactly-once guarantee wording; the full target-stream contract (identity, opaque positions stable across reopen, ordering, retention, append receipt);
- C1 split into three slices (journal grammar+memory backend+poison/reopen; file backend+identity/position persistence+locks+crash recovery; authority fold+enrollment+target projection+internal dedup test seam), dedup-only admit! kept internal;
- the checkpoint-baseline extraction before offer admission (shared pure inspector, canonical fixtures shared with D) so C2 never admits variants first; C5 successor validation; C7 input protocol (durable ack, replay frontier, end-of-prefix, child ordering); result delivery/redelivery contract; remote admission protocol and attribution contracts assigned to C, driver to D, partition/crash execution to E; C8 narrowed wording.
- renumbered slices with files, clauses, dependencies, host test contracts, order, and which can run concurrently; the document-amendment list updated.
Be decisive. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

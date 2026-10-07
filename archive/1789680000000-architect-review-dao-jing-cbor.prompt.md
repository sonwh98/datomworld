Created-GMT: 2026-09-18T01:40:00Z
Created-Local: 2026-09-18 08:40:00 +0700 (Asia/Ho_Chi_Minh)

Session-ID: acf83960-226a-46f7-9c13-a00a012889c2

# Task: Review dao.jing.cbor.md

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-18 08:40:00 +07 | Status: active | Rationale: architecture sign-off gate on an unreviewed implementation plan before code work starts against it

Perform a read-only architecture review of `docs/design/dao.jing.cbor.md`
(status: "implementation plan; not yet implemented" — this plan has never
been reviewed).

Read first:
- docs/design/datom.world.md
- docs/design/dao.jing.cbor.md
- docs/design/dao.jing.md (the design this plan amends)
- docs/design/dao.jing.dht.md
- src/cljc/dao/jing.cljc
- src/cljc/dao/jing/{coordinate,dht,file,mem,remote}.cljc
- src/cljc/dao/jing/dht/{kad,node}.cljc
- test/dao/jing_test.cljc
- test/dao/jing/{dht_test,file_test,mem_test,remote_test}.cljc

Evaluate foundational invariants, ownership boundaries, explicit state and
control flow, concurrency and linearization, dynamic extension, host isolation,
CLJ/CLJS/CLJD portability, migration risk, completion criteria, and design
contradictions. Distinguish architectural defects from implementation gaps or
intentionally deferred work. Do not edit files.

## Additional context for this review

This plan is being relied on right now by a separate, in-progress decision
in `yin.vm.code-as-tuples.implementation-plan.md` (D3 — how `dao.jing`
stores a tuple tree's rows). The orchestrator and the owner used this
plan's content as evidence: (1) that Jing's storage contract is
single-value/per-address throughout, with no batch/pack write primitive
anywhere in it, which was read as supporting "individual rows" over "a
pack per tree" as the row-storage grain; and (2) that the metadata-carry
prerequisite D3 names as blocking (`dao.jing.md:447-464`: no backend or
wire codec carries metadata today, so a row whose payload carries a
metadata-bearing literal is corrupted on replay) is already fully
designed here — CBOR's supported-values set explicitly includes
metadata, preserved via Boring's `clojure/with-meta` mapping — just not
yet implemented.

Both of those readings should be checked as part of this review, not
assumed correct because they were useful: does this plan's byte-store
contract (`{:put-bytes-fn f :get-bytes-fn g :close-fn c}`) actually
preclude or complicate a future pack/bundle write primitive, or would
one compose cleanly on top of it? Is the metadata-preservation mechanism
actually sound and complete as specified, or does it have gaps (e.g., the
carried-forward ClojureDart `list`-producer obligation, the still-open
"intake transport... carries no metadata" limitation) that would still
leave a tuple-row payload's metadata at risk in some path?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended correction.
Also confirm the requested properties that passed review.

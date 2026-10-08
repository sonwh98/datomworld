Created-GMT: 2026-10-04 06:52:00 GMT
Coding-Agent: codex (gpt-6-astra, resume of thread 01a0f878-281b-7253-ac44-ff2402583d35)

# Task: review fable's M-next C implementation plan (read-only; final response is the deliverable)

Role: Architect (reviewer)

Plan under review: /Users/sto/workspace/datomworld/collab/1791096045000-architect-linker-m-next-c-implementation-plan.claude-fable-5-1.findings.md
Sources: docs/design/yin.vm.linker.dht.md section 14; docs/design/yin.vm.universal-continuation-format.md 7.7 to 7.11.1 (as amended in 0c7ee4ee); docs/design/yin.vm.ucf-revisions.md; src/cljc/dao/space/transactor.cljc, src/cljc/dao/lease*.cljc, src/cljc/dao/jing/file.cljc, src/cljc/dao/space/store/fs.cljc; docs/design/datom.world.md invariants.

Judge independently:
1. Is "dao.space transactor over a durable journal stream, enrolled targets as ledger projections" sound against UCF 7.7.2/7.7.3 and the invariants (no privileged node, dao.stream is the complexity boundary, derive-don't-persist, peer observers)? Is a new `dao.stream.journal` transport justified versus the transactor's own stated position that a durable stream transport is not the answer? Any hidden atomicity hole (judge :seen/:answered rebuild, reclaim vs admission race, poison rule)?
2. Does the slice order C1..C8 cover every stage-C clause of 7.11.1 per astra's own stage-ownership table (ucf-revisions section 6)? Name any clause unowned or double-owned, and any slice too large for one engineer round.
3. The owner question (enrolled target = authority-minted stream, exactly-once only inside that boundary): agree with the recommendation, or give the alternative.
4. Boundary with D and E: anything C builds that belongs to D/E, or needs from D that is unstated.
One-line verdict per item first, then specifics. Decisive, no survey.
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
